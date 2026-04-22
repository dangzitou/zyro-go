package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import com.hmdp.ai.AgentTraceContext;
import com.hmdp.ai.rag.LocalLifeRagService;
import com.hmdp.config.AiProperties;
import com.hmdp.config.RequestTraceFilter;
import com.hmdp.dto.AgentAuditRecord;
import com.hmdp.dto.AgentExecutionPlan;
import com.hmdp.dto.AiChatRequest;
import com.hmdp.dto.AiChatResponse;
import com.hmdp.dto.AiRetrievalHit;
import com.hmdp.dto.UserDTO;
import com.hmdp.service.IAiAgentService;
import com.hmdp.service.IAgentPlanningService;
import com.hmdp.service.IAgentAuditService;
import com.hmdp.service.IAiKnowledgeService;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallAdvisor;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class AiAgentServiceImpl implements IAiAgentService {

    private static final String DEFAULT_CONVERSATION_ID = "default";
    private static final String SYSTEM_PROMPT = """
            你是黑马点评 Agent，服务于本地生活场景。
            你的目标不是凭空编造业务信息，而是优先调用工具获取事实，再基于工具结果给用户明确建议。
            处理规则：
            1. 涉及店铺、价格、评分、营业时间、优惠券、热门探店内容时，优先调用工具核实。
            2. 推荐类问题优先使用 recommend_shops，不要自己在上下文里凭空排序。
            3. 当 RAG 或知识召回片段存在时，把它当作补充背景，但动态事实仍要以工具结果为准。
            4. 信息不足时明确说“不确定”并指出还缺什么，不要编造。
            5. 回答使用简洁、专业、自然的中文。
            """;

    private final AiProperties aiProperties;
    private final ObjectProvider<ChatClient.Builder> chatClientBuilderProvider;
    private final ToolCallbackProvider toolCallbackProvider;
    private final ChatMemory chatMemory;
    private final IAiKnowledgeService aiKnowledgeService;
    private final IAgentPlanningService agentPlanningService;
    private final IAgentAuditService agentAuditService;
    private final LocalLifeRagService localLifeRagService;
    private final ObjectProvider<ToolCallingManager> toolCallingManagerProvider;
    private final AgentTraceContext traceContext;

    public AiAgentServiceImpl(AiProperties aiProperties,
                              ObjectProvider<ChatClient.Builder> chatClientBuilderProvider,
                              ToolCallbackProvider toolCallbackProvider,
                              ChatMemory chatMemory,
                              IAiKnowledgeService aiKnowledgeService,
                              IAgentPlanningService agentPlanningService,
                              IAgentAuditService agentAuditService,
                              LocalLifeRagService localLifeRagService,
                              ObjectProvider<ToolCallingManager> toolCallingManagerProvider,
                              AgentTraceContext traceContext) {
        this.aiProperties = aiProperties;
        this.chatClientBuilderProvider = chatClientBuilderProvider;
        this.toolCallbackProvider = toolCallbackProvider;
        this.chatMemory = chatMemory;
        this.aiKnowledgeService = aiKnowledgeService;
        this.agentPlanningService = agentPlanningService;
        this.agentAuditService = agentAuditService;
        this.localLifeRagService = localLifeRagService;
        this.toolCallingManagerProvider = toolCallingManagerProvider;
        this.traceContext = traceContext;
    }

    @Override
    public AiChatResponse chat(AiChatRequest request) {
        long startedAt = System.currentTimeMillis();
        String clientConversationId = resolveClientConversationId(request == null ? null : request.getConversationId());
        if (!aiProperties.isEnabled()) {
            return failResponse(clientConversationId, "AI Agent 当前未启用，请先设置 AI_ENABLED=true。");
        }
        if (request == null || StrUtil.isBlank(request.getMessage())) {
            return failResponse(clientConversationId, "message cannot be blank");
        }

        UserDTO currentUser = UserHolder.getUser();
        if (currentUser == null) {
            return failResponse(clientConversationId, "当前用户未登录，无法使用 Agent 对话。");
        }

        ChatClient.Builder builder = chatClientBuilderProvider.getIfAvailable();
        if (builder == null) {
            return failResponse(clientConversationId, "AI 模型尚未完成初始化，请检查 spring.ai.openai 配置。");
        }

        String storageConversationId = storageConversationId(currentUser.getId(), clientConversationId);
        AgentExecutionPlan executionPlan = agentPlanningService.plan(request.getMessage());
        if (executionPlan == null) {
            executionPlan = new AgentExecutionPlan();
        }
        final AgentExecutionPlan finalExecutionPlan = executionPlan;
        boolean useKnowledge = shouldUseKnowledge(request, finalExecutionPlan);
        int topK = resolveTopK(request);
        String retrievalQuery = resolveRetrievalQuery(request, finalExecutionPlan);
        List<AiRetrievalHit> retrievalHits = useKnowledge
                ? aiKnowledgeService.retrieve(retrievalQuery, topK)
                : Collections.emptyList();
        final List<AiRetrievalHit> finalRetrievalHits = retrievalHits;

        traceContext.reset();
        traceContext.record("plan(intent=" + finalExecutionPlan.getIntent()
                + ", useKnowledge=" + useKnowledge
                + ", preferredTools=" + finalExecutionPlan.getPreferredTools() + ")");
        if (useKnowledge) {
            traceContext.record("knowledge(query=" + retrievalQuery + ", hits=" + retrievalHits.size()
                    + ", vectorRagReady=" + localLifeRagService.isReady() + ")");
        }
        try {
            ChatClient.Builder chatClientBuilder = builder.clone()
                    .defaultToolCallbacks(toolCallbackProvider)
                    .defaultSystem(buildSystemPrompt(finalExecutionPlan, finalRetrievalHits, localLifeRagService.isReady() && useKnowledge));

            ToolCallingManager toolCallingManager = toolCallingManagerProvider.getIfAvailable();
            if (toolCallingManager != null && Boolean.TRUE.equals(aiProperties.getRecursiveToolLoopEnabled())) {
                chatClientBuilder.defaultAdvisors(
                        ToolCallAdvisor.builder()
                                .toolCallingManager(toolCallingManager)
                                .advisorOrder(100)
                                .build()
                );
            }
            ChatClient chatClient = chatClientBuilder.build();

            MessageChatMemoryAdvisor memoryAdvisor = MessageChatMemoryAdvisor.builder(chatMemory)
                    .conversationId(storageConversationId)
                    .build();

            ChatClient.ChatClientRequestSpec requestSpec = chatClient.prompt()
                    .user(request.getMessage().trim())
                    .advisors(memoryAdvisor);

            if (useKnowledge) {
                QuestionAnswerAdvisor questionAnswerAdvisor = localLifeRagService.buildAdvisor(retrievalQuery, topK);
                if (questionAnswerAdvisor != null) {
                    requestSpec = requestSpec.advisors(questionAnswerAdvisor);
                }
            }

            if (finalExecutionPlan.getPreferredTools() != null && !finalExecutionPlan.getPreferredTools().isEmpty()) {
                requestSpec = requestSpec.toolNames(finalExecutionPlan.getPreferredTools().toArray(new String[0]));
            }

            String answer = requestSpec.call().content();

            if (StrUtil.isBlank(answer)) {
                answer = "我已经拿到了相关业务数据，但这次还不足以给出稳定结论。你可以换一种更具体的问法继续问我。";
            }

            AiChatResponse response = new AiChatResponse(clientConversationId, answer, traceContext.snapshot(), finalRetrievalHits, finalExecutionPlan);
            audit(currentUser, request, response, startedAt, "SUCCESS");
            return response;
        } catch (Exception e) {
            log.error("AI agent chat failed, conversationId={}", storageConversationId, e);
            String message = "Agent 调用失败，请检查模型网关、API Key 或稍后重试。";
            AiChatResponse response = new AiChatResponse(clientConversationId, message, traceContext.snapshot(), finalRetrievalHits, finalExecutionPlan);
            audit(currentUser, request, response, startedAt, "FAILED");
            return response;
        } finally {
            traceContext.clear();
        }
    }

    @Override
    public SseEmitter chatStream(AiChatRequest request) {
        long startedAt = System.currentTimeMillis();
        String clientConversationId = resolveClientConversationId(request == null ? null : request.getConversationId());
        SseEmitter emitter = new SseEmitter(0L);

        if (!aiProperties.isEnabled()) {
            completeWithSingleEvent(emitter, "error", Map.of("message", "AI Agent 当前未启用，请先设置 AI_ENABLED=true。"));
            return emitter;
        }
        if (request == null || StrUtil.isBlank(request.getMessage())) {
            completeWithSingleEvent(emitter, "error", Map.of("message", "message cannot be blank"));
            return emitter;
        }

        UserDTO currentUser = UserHolder.getUser();
        if (currentUser == null) {
            completeWithSingleEvent(emitter, "error", Map.of("message", "当前用户未登录，无法使用 Agent 对话。"));
            return emitter;
        }

        ChatClient.Builder builder = chatClientBuilderProvider.getIfAvailable();
        if (builder == null) {
            completeWithSingleEvent(emitter, "error", Map.of("message", "AI 模型尚未完成初始化，请检查 spring.ai.openai 配置。"));
            return emitter;
        }

        String storageConversationId = storageConversationId(currentUser.getId(), clientConversationId);
        AgentExecutionPlan executionPlan = agentPlanningService.plan(request.getMessage());
        if (executionPlan == null) {
            executionPlan = new AgentExecutionPlan();
        }
        final AgentExecutionPlan finalExecutionPlan = executionPlan;
        boolean useKnowledge = shouldUseKnowledge(request, finalExecutionPlan);
        int topK = resolveTopK(request);
        String retrievalQuery = resolveRetrievalQuery(request, finalExecutionPlan);
        List<AiRetrievalHit> retrievalHits = useKnowledge
                ? aiKnowledgeService.retrieve(retrievalQuery, topK)
                : Collections.emptyList();
        final List<AiRetrievalHit> finalRetrievalHits = retrievalHits;

        traceContext.reset();
        traceContext.record("plan(intent=" + finalExecutionPlan.getIntent()
                + ", useKnowledge=" + useKnowledge
                + ", preferredTools=" + finalExecutionPlan.getPreferredTools() + ")");
        if (useKnowledge) {
            traceContext.record("knowledge(query=" + retrievalQuery + ", hits=" + retrievalHits.size()
                    + ", vectorRagReady=" + localLifeRagService.isReady() + ")");
        }

        try {
            sendEvent(emitter, "meta", Map.of(
                    "conversationId", clientConversationId,
                    "plan", finalExecutionPlan,
                    "retrievalHits", finalRetrievalHits
            ));

            ChatClient.ChatClientRequestSpec requestSpec = buildRequestSpec(
                    builder,
                    storageConversationId,
                    request.getMessage().trim(),
                    finalExecutionPlan,
                    finalRetrievalHits,
                    useKnowledge,
                    retrievalQuery,
                    topK
            );

            StringBuilder answerBuilder = new StringBuilder();
            Disposable disposable = requestSpec.stream().content().subscribe(
                    chunk -> {
                        answerBuilder.append(chunk);
                        sendEventQuietly(emitter, "chunk", Map.of("content", chunk));
                    },
                    error -> {
                        log.error("AI agent stream failed, conversationId={}", storageConversationId, error);
                        sendEventQuietly(emitter, "error", Map.of("message", "流式调用失败，请稍后重试。"));
                        AiChatResponse response = new AiChatResponse(
                                clientConversationId,
                                answerBuilder.toString(),
                                traceContext.snapshot(),
                                finalRetrievalHits,
                                finalExecutionPlan
                        );
                        audit(currentUser, request, response, startedAt, "FAILED");
                        traceContext.clear();
                        emitter.complete();
                    },
                    () -> {
                        AiChatResponse response = new AiChatResponse(
                                clientConversationId,
                                answerBuilder.toString(),
                                traceContext.snapshot(),
                                finalRetrievalHits,
                                finalExecutionPlan
                        );
                        sendEventQuietly(emitter, "done", response);
                        audit(currentUser, request, response, startedAt, "SUCCESS");
                        traceContext.clear();
                        emitter.complete();
                    }
            );

            emitter.onCompletion(disposable::dispose);
            emitter.onTimeout(() -> {
                disposable.dispose();
                traceContext.clear();
                emitter.complete();
            });
        } catch (Exception e) {
            log.error("AI agent stream init failed, conversationId={}", storageConversationId, e);
            sendEventQuietly(emitter, "error", Map.of("message", "流式调用初始化失败，请检查模型配置。"));
            traceContext.clear();
            emitter.complete();
        }
        return emitter;
    }

    @Override
    public void clearSession(String conversationId) {
        UserDTO currentUser = UserHolder.getUser();
        if (currentUser == null) {
            return;
        }
        String clientConversationId = resolveClientConversationId(conversationId);
        chatMemory.clear(storageConversationId(currentUser.getId(), clientConversationId));
    }

    private boolean shouldUseKnowledge(AiChatRequest request, AgentExecutionPlan executionPlan) {
        if (request.getUseKnowledge() != null) {
            return request.getUseKnowledge();
        }
        if (executionPlan != null && executionPlan.getUseKnowledge() != null) {
            return executionPlan.getUseKnowledge();
        }
        return Boolean.TRUE.equals(aiProperties.getKnowledgeEnabled());
    }

    private int resolveTopK(AiChatRequest request) {
        Integer topK = request.getKnowledgeTopK() != null ? request.getKnowledgeTopK() : aiProperties.getKnowledgeTopK();
        if (topK == null) {
            return 4;
        }
        return Math.max(1, Math.min(topK, 8));
    }

    private String resolveRetrievalQuery(AiChatRequest request, AgentExecutionPlan executionPlan) {
        if (executionPlan != null && StrUtil.isNotBlank(executionPlan.getRetrievalQuery())) {
            return executionPlan.getRetrievalQuery();
        }
        return request.getMessage();
    }

    private String buildSystemPrompt(AgentExecutionPlan executionPlan, List<AiRetrievalHit> retrievalHits, boolean ragAdvisorEnabled) {
        StringBuilder builder = new StringBuilder(SYSTEM_PROMPT);
        if (executionPlan != null) {
            builder.append("\n本轮计划：intent=")
                    .append(StrUtil.blankToDefault(executionPlan.getIntent(), "general"))
                    .append("，responseStyle=")
                    .append(StrUtil.blankToDefault(executionPlan.getResponseStyle(), "concise"))
                    .append("，reasoningFocus=")
                    .append(StrUtil.blankToDefault(executionPlan.getReasoningFocus(), "grounded_facts"))
                    .append("。");
            if (executionPlan.getPreferredTools() != null && !executionPlan.getPreferredTools().isEmpty()) {
                builder.append(" 优先工具=").append(executionPlan.getPreferredTools()).append("。");
            }
        }
        if (ragAdvisorEnabled) {
            builder.append("\n本轮已启用向量 RAG，请优先利用检索到的上下文回答背景知识，但涉及动态事实仍要依赖工具结果。");
            return builder.toString();
        }
        if (retrievalHits == null || retrievalHits.isEmpty()) {
            return builder.toString();
        }
        builder.append("\n补充知识片段（仅作背景参考）:\n");
        int index = 1;
        for (AiRetrievalHit hit : retrievalHits) {
            builder.append(index++)
                    .append(". [")
                    .append(hit.getSourceType())
                    .append("#")
                    .append(hit.getSourceId())
                    .append("] ")
                    .append(StrUtil.blankToDefault(hit.getTitle(), "未命名"))
                    .append(" | score=")
                    .append(hit.getScore())
                    .append(" | ")
                    .append(hit.getSnippet())
                    .append("\n");
        }
        return builder.toString();
    }

    private AiChatResponse failResponse(String conversationId, String message) {
        return new AiChatResponse(
                conversationId,
                message,
                Collections.emptyList(),
                Collections.emptyList(),
                null
        );
    }

    private String resolveClientConversationId(String conversationId) {
        return StrUtil.blankToDefault(conversationId, DEFAULT_CONVERSATION_ID);
    }

    private String storageConversationId(Long userId, String clientConversationId) {
        return aiProperties.getDefaultConversationPrefix() + ":" + userId + ":" + clientConversationId;
    }

    private ChatClient.ChatClientRequestSpec buildRequestSpec(ChatClient.Builder builder,
                                                              String storageConversationId,
                                                              String message,
                                                              AgentExecutionPlan executionPlan,
                                                              List<AiRetrievalHit> retrievalHits,
                                                              boolean useKnowledge,
                                                              String retrievalQuery,
                                                              int topK) {
        ChatClient.Builder chatClientBuilder = builder.clone()
                .defaultToolCallbacks(toolCallbackProvider)
                .defaultSystem(buildSystemPrompt(executionPlan, retrievalHits, localLifeRagService.isReady() && useKnowledge));

        ToolCallingManager toolCallingManager = toolCallingManagerProvider.getIfAvailable();
        if (toolCallingManager != null && Boolean.TRUE.equals(aiProperties.getRecursiveToolLoopEnabled())) {
            chatClientBuilder.defaultAdvisors(
                    ToolCallAdvisor.builder()
                            .toolCallingManager(toolCallingManager)
                            .advisorOrder(100)
                            .build()
            );
        }
        ChatClient chatClient = chatClientBuilder.build();

        MessageChatMemoryAdvisor memoryAdvisor = MessageChatMemoryAdvisor.builder(chatMemory)
                .conversationId(storageConversationId)
                .build();

        ChatClient.ChatClientRequestSpec requestSpec = chatClient.prompt()
                .user(message)
                .advisors(memoryAdvisor);

        if (useKnowledge) {
            QuestionAnswerAdvisor questionAnswerAdvisor = localLifeRagService.buildAdvisor(retrievalQuery, topK);
            if (questionAnswerAdvisor != null) {
                requestSpec = requestSpec.advisors(questionAnswerAdvisor);
            }
        }

        if (executionPlan != null && executionPlan.getPreferredTools() != null && !executionPlan.getPreferredTools().isEmpty()) {
            requestSpec = requestSpec.toolNames(executionPlan.getPreferredTools().toArray(new String[0]));
        }
        return requestSpec;
    }

    private void audit(UserDTO currentUser, AiChatRequest request, AiChatResponse response, long startedAt, String status) {
        agentAuditService.record(AgentAuditRecord.builder()
                .timestamp(Instant.now())
                .traceId(MDC.get(RequestTraceFilter.TRACE_ID_KEY))
                .userId(currentUser == null ? null : currentUser.getId())
                .conversationId(response.getConversationId())
                .message(request == null ? null : request.getMessage())
                .answer(response.getAnswer())
                .status(status)
                .model(System.getenv().getOrDefault("AI_MODEL", "gpt-4.1-mini"))
                .intent(response.getPlan() == null ? null : response.getPlan().getIntent())
                .retrievalQuery(response.getPlan() == null ? null : response.getPlan().getRetrievalQuery())
                .retrievalCount(response.getRetrievalHits() == null ? 0 : response.getRetrievalHits().size())
                .toolTraceCount(response.getToolTrace() == null ? 0 : response.getToolTrace().size())
                .latencyMs(System.currentTimeMillis() - startedAt)
                .toolTrace(response.getToolTrace())
                .build());
    }

    private void completeWithSingleEvent(SseEmitter emitter, String event, Object data) {
        sendEventQuietly(emitter, event, data);
        emitter.complete();
    }

    private void sendEventQuietly(SseEmitter emitter, String event, Object data) {
        try {
            sendEvent(emitter, event, data);
        } catch (IOException e) {
            log.debug("Failed to send SSE event {}", event, e);
        }
    }

    private void sendEvent(SseEmitter emitter, String event, Object data) throws IOException {
        emitter.send(SseEmitter.event().name(event).data(data));
    }
}
