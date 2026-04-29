package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import com.hmdp.ai.AssembledChatContext;
import com.hmdp.ai.AgentTraceContext;
import com.hmdp.ai.ContextCompressionService;
import com.hmdp.ai.LocalLifeAgentTools;
import com.hmdp.ai.OpenAiCompatibleStreamBridge;
import com.hmdp.ai.rag.BaiduMapGeoService;
import com.hmdp.ai.rag.LocalLifeRagService;
import com.hmdp.config.AiProperties;
import com.hmdp.config.RequestTraceFilter;
import com.hmdp.dto.AgentAuditRecord;
import com.hmdp.dto.AgentExecutionPlan;
import com.hmdp.dto.AiChatRequest;
import com.hmdp.dto.AiChatResponse;
import com.hmdp.dto.AiRetrievalHit;
import com.hmdp.dto.ShopRecommendationDTO;
import com.hmdp.dto.ShopRecommendationQuery;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.UserInfo;
import com.hmdp.service.IAgentAuditService;
import com.hmdp.service.IAgentPlanningService;
import com.hmdp.service.IAiAgentService;
import com.hmdp.service.IAiKnowledgeService;
import com.hmdp.service.IUserInfoService;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class AiAgentServiceImpl implements IAiAgentService {

    private static final String DEFAULT_CONVERSATION_ID = "default";
    private static final String SYSTEM_PROMPT = """
            You are Zyro's local-life agent for Chinese local services.
            Answer with grounded business facts.

            Rules:
            1. Use tool data or prefetched business data for shops, prices, opening hours, coupons and recommendations.
            2. Treat RAG as background only. Dynamic business facts should not be guessed.
            3. If information is still missing, say what is missing instead of inventing.
            4. Reply in concise, natural Chinese.
            """;
    private static final Pattern BUDGET_PATTERN =
            Pattern.compile("(?:人均|预算|不超过|控制在|价格)?\\s*(\\d{2,4})\\s*(?:元|块)?\\s*(?:以内|以下|之内)?");
    private static final String[] PRICE_HINTS = {
            "不贵", "便宜", "平价", "实惠", "划算", "性价比高", "学生党"
    };

    private final AiProperties aiProperties;
    private final ObjectProvider<ChatClient.Builder> chatClientBuilderProvider;
    private final ToolCallbackProvider toolCallbackProvider;
    private final IAiKnowledgeService aiKnowledgeService;
    private final IAgentPlanningService agentPlanningService;
    private final IAgentAuditService agentAuditService;
    private final LocalLifeRagService localLifeRagService;
    private final ObjectProvider<ToolCallingManager> toolCallingManagerProvider;
    private final AgentTraceContext traceContext;
    private final LocalLifeAgentTools localLifeAgentTools;
    private final IUserInfoService userInfoService;
    private final BaiduMapGeoService baiduMapGeoService;
    private final ContextCompressionService contextCompressionService;
    private final OpenAiCompatibleStreamBridge openAiCompatibleStreamBridge;

    @Autowired
    public AiAgentServiceImpl(AiProperties aiProperties,
                              ObjectProvider<ChatClient.Builder> chatClientBuilderProvider,
                              ToolCallbackProvider toolCallbackProvider,
                              IAiKnowledgeService aiKnowledgeService,
                              IAgentPlanningService agentPlanningService,
                              IAgentAuditService agentAuditService,
                              LocalLifeRagService localLifeRagService,
                              ObjectProvider<ToolCallingManager> toolCallingManagerProvider,
                              AgentTraceContext traceContext,
                              LocalLifeAgentTools localLifeAgentTools,
                              IUserInfoService userInfoService,
                              BaiduMapGeoService baiduMapGeoService,
                              ContextCompressionService contextCompressionService,
                              OpenAiCompatibleStreamBridge openAiCompatibleStreamBridge) {
        this.aiProperties = aiProperties;
        this.chatClientBuilderProvider = chatClientBuilderProvider;
        this.toolCallbackProvider = toolCallbackProvider;
        this.aiKnowledgeService = aiKnowledgeService;
        this.agentPlanningService = agentPlanningService;
        this.agentAuditService = agentAuditService;
        this.localLifeRagService = localLifeRagService;
        this.toolCallingManagerProvider = toolCallingManagerProvider;
        this.traceContext = traceContext;
        this.localLifeAgentTools = localLifeAgentTools;
        this.userInfoService = userInfoService;
        this.baiduMapGeoService = baiduMapGeoService;
        this.contextCompressionService = contextCompressionService;
        this.openAiCompatibleStreamBridge = openAiCompatibleStreamBridge;
    }

    public AiAgentServiceImpl(AiProperties aiProperties,
                              ObjectProvider<ChatClient.Builder> chatClientBuilderProvider,
                              ToolCallbackProvider toolCallbackProvider,
                              IAiKnowledgeService aiKnowledgeService,
                              IAgentPlanningService agentPlanningService,
                              IAgentAuditService agentAuditService,
                              LocalLifeRagService localLifeRagService,
                              ObjectProvider<ToolCallingManager> toolCallingManagerProvider,
                              AgentTraceContext traceContext,
                              LocalLifeAgentTools localLifeAgentTools,
                              IUserInfoService userInfoService) {
        this(aiProperties, chatClientBuilderProvider, toolCallbackProvider, aiKnowledgeService,
                agentPlanningService, agentAuditService, localLifeRagService, toolCallingManagerProvider,
                traceContext, localLifeAgentTools, userInfoService, null, null, null);
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

        AgentExecutionPlan executionPlan = resolveExecutionPlan(request.getMessage());
        boolean useKnowledge = shouldUseKnowledge(request, executionPlan);
        int topK = resolveTopK(request);
        String retrievalQuery = resolveRetrievalQuery(request, executionPlan);
        executionPlan.setUseKnowledge(useKnowledge);
        executionPlan.setRetrievalQuery(retrievalQuery);
        List<AiRetrievalHit> retrievalHits = useKnowledge
                ? aiKnowledgeService.retrieve(retrievalQuery, topK)
                : Collections.emptyList();

        traceContext.reset();
        traceContext.record("plan(intent=" + executionPlan.getIntent()
                + ", useKnowledge=" + useKnowledge
                + ", preferredTools=" + executionPlan.getPreferredTools() + ")");
        if (useKnowledge) {
            traceContext.record("knowledge(query=" + retrievalQuery + ", hits=" + retrievalHits.size()
                    + ", vectorRagReady=" + localLifeRagService.isReady() + ")");
        }

        try {
            PrefetchedToolContext prefetched = prefetchToolContext(request.getMessage(), executionPlan, currentUser);
            if (prefetched.hasData()) {
                traceContext.record("prefetch_context(answerLines=" + prefetched.lineCount() + ")");
            }
            String storageConversationId = storageConversationId(currentUser.getId(), clientConversationId);
            AssembledChatContext assembledContext = assembleContext(
                    storageConversationId,
                    currentUser.getId(),
                    request.getMessage().trim(),
                    executionPlan,
                    retrievalHits,
                    prefetched.promptContext()
            );

            ChatClient.Builder builder = chatClientBuilderProvider.getIfAvailable();
            if (builder == null) {
                if (prefetched.hasData()) {
                    traceContext.record("fallback_prefetch(reason=no_model)");
                    AiChatResponse response = new AiChatResponse(
                            clientConversationId,
                            prefetched.directAnswer(),
                            traceContext.snapshot(),
                            retrievalHits,
                            executionPlan
                    );
                    recordTurnQuietly(storageConversationId, currentUser.getId(), request.getMessage().trim(), response.getAnswer(), executionPlan);
                    audit(currentUser, request, response, startedAt, "SUCCESS_WITH_PREFETCH_FALLBACK");
                    return response;
                }
                return failResponse(clientConversationId, "AI 模型尚未初始化，请检查 spring.ai.openai 配置。");
            }

            ChatClient.ChatClientRequestSpec requestSpec = buildRequestSpec(
                    builder,
                    request.getMessage().trim(),
                    executionPlan,
                    retrievalHits,
                    useKnowledge,
                    retrievalQuery,
                    topK,
                    prefetched.promptContext(),
                    assembledContext
            );

            String answer = collectStreamContent(
                    requestSpec,
                    buildSystemPrompt(executionPlan, retrievalHits, localLifeRagService.isReady() && useKnowledge, prefetched.promptContext(), assembledContext),
                    request.getMessage().trim()
            );
            if (StrUtil.isBlank(answer)) {
                answer = prefetched.hasData()
                        ? prefetched.directAnswer()
                        : "我这次没有拿到稳定的模型回答，你可以换一种更具体的问法继续问我。";
            }

            AiChatResponse response = new AiChatResponse(
                    clientConversationId,
                    answer,
                    traceContext.snapshot(),
                    retrievalHits,
                    executionPlan
            );
            recordTurnQuietly(storageConversationId, currentUser.getId(), request.getMessage().trim(), response.getAnswer(), executionPlan);
            audit(currentUser, request, response, startedAt, "SUCCESS");
            return response;
        } catch (Exception e) {
            log.error("AI agent chat failed, conversationId={}", clientConversationId, e);
            PrefetchedToolContext prefetched = prefetchToolContext(request.getMessage(), executionPlan, currentUser);
            String storageConversationId = storageConversationId(currentUser.getId(), clientConversationId);
            if (prefetched.hasData()) {
                traceContext.record("fallback_prefetch(reason=exception)");
                AiChatResponse response = new AiChatResponse(
                        clientConversationId,
                        prefetched.directAnswer(),
                        traceContext.snapshot(),
                        retrievalHits,
                        executionPlan
                );
                recordTurnQuietly(storageConversationId, currentUser.getId(), request.getMessage().trim(), response.getAnswer(), executionPlan);
                audit(currentUser, request, response, startedAt, "SUCCESS_WITH_PREFETCH_FALLBACK");
                return response;
            }
            AiChatResponse response = new AiChatResponse(
                    clientConversationId,
                    "Agent 调用失败，请检查模型网关、API Key 或稍后重试。",
                    traceContext.snapshot(),
                    retrievalHits,
                    executionPlan
            );
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

        AgentExecutionPlan executionPlan = resolveExecutionPlan(request.getMessage());
        boolean useKnowledge = shouldUseKnowledge(request, executionPlan);
        int topK = resolveTopK(request);
        String retrievalQuery = resolveRetrievalQuery(request, executionPlan);
        executionPlan.setUseKnowledge(useKnowledge);
        executionPlan.setRetrievalQuery(retrievalQuery);
        List<AiRetrievalHit> retrievalHits = useKnowledge
                ? aiKnowledgeService.retrieve(retrievalQuery, topK)
                : Collections.emptyList();

        traceContext.reset();
        traceContext.record("plan(intent=" + executionPlan.getIntent()
                + ", useKnowledge=" + useKnowledge
                + ", preferredTools=" + executionPlan.getPreferredTools() + ")");
        if (useKnowledge) {
            traceContext.record("knowledge(query=" + retrievalQuery + ", hits=" + retrievalHits.size()
                    + ", vectorRagReady=" + localLifeRagService.isReady() + ")");
        }

        try {
            sendEvent(emitter, "meta", Map.of(
                    "conversationId", clientConversationId,
                    "plan", executionPlan,
                    "retrievalHits", retrievalHits
            ));

            PrefetchedToolContext prefetched = prefetchToolContext(request.getMessage(), executionPlan, currentUser);
            if (prefetched.hasData()) {
                traceContext.record("prefetch_context(answerLines=" + prefetched.lineCount() + ")");
            }
            String storageConversationId = storageConversationId(currentUser.getId(), clientConversationId);
            AssembledChatContext assembledContext = assembleContext(
                    storageConversationId,
                    currentUser.getId(),
                    request.getMessage().trim(),
                    executionPlan,
                    retrievalHits,
                    prefetched.promptContext()
            );

            ChatClient.Builder builder = chatClientBuilderProvider.getIfAvailable();
            if (builder == null) {
                if (prefetched.hasData()) {
                    streamPlainAnswer(emitter, clientConversationId, storageConversationId, prefetched.directAnswer(), retrievalHits, executionPlan, currentUser, request, startedAt);
                    return emitter;
                }
                completeWithSingleEvent(emitter, "error", Map.of("message", "AI 模型尚未初始化，请检查 spring.ai.openai 配置。"));
                traceContext.clear();
                return emitter;
            }

            ChatClient.ChatClientRequestSpec requestSpec = buildRequestSpec(
                    builder,
                    request.getMessage().trim(),
                    executionPlan,
                    retrievalHits,
                    useKnowledge,
                    retrievalQuery,
                    topK,
                    prefetched.promptContext(),
                    assembledContext
            );

            StringBuilder answerBuilder = new StringBuilder();
            Disposable disposable = requestSpec.stream().content().subscribe(
                    chunk -> {
                        answerBuilder.append(chunk);
                        sendEventQuietly(emitter, "chunk", Map.of("content", chunk));
                    },
                    error -> {
                        log.error("AI agent stream failed, conversationId={}", clientConversationId, error);
                        if (prefetched.hasData()) {
                            streamPlainAnswer(emitter, clientConversationId, storageConversationId, prefetched.directAnswer(), retrievalHits, executionPlan, currentUser, request, startedAt);
                            return;
                        }
                        sendEventQuietly(emitter, "error", Map.of("message", "流式调用失败，请稍后重试。"));
                        AiChatResponse response = new AiChatResponse(clientConversationId, answerBuilder.toString(), traceContext.snapshot(), retrievalHits, executionPlan);
                        audit(currentUser, request, response, startedAt, "FAILED");
                        traceContext.clear();
                        emitter.complete();
                    },
                    () -> {
                        String finalAnswer = StrUtil.blankToDefault(answerBuilder.toString(), prefetched.directAnswer());
                        AiChatResponse response = new AiChatResponse(clientConversationId, finalAnswer, traceContext.snapshot(), retrievalHits, executionPlan);
                        recordTurnQuietly(storageConversationId, currentUser.getId(), request.getMessage().trim(), finalAnswer, executionPlan);
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
            log.error("AI agent stream init failed, conversationId={}", clientConversationId, e);
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
        String storageConversationId = storageConversationId(currentUser.getId(), resolveClientConversationId(conversationId));
        if (contextCompressionService != null) {
            contextCompressionService.clearConversation(storageConversationId);
        }
    }

    private AgentExecutionPlan resolveExecutionPlan(String message) {
        AgentExecutionPlan plan = agentPlanningService.plan(message);
        return plan == null ? new AgentExecutionPlan() : plan;
    }

    private boolean shouldUseKnowledge(AiChatRequest request, AgentExecutionPlan executionPlan) {
        if (request.getUseKnowledge() != null) {
            return request.getUseKnowledge();
        }
        if (executionPlan.getUseKnowledge() != null) {
            return executionPlan.getUseKnowledge();
        }
        if ("recommendation".equals(executionPlan.getIntent())) {
            return false;
        }
        return Boolean.TRUE.equals(aiProperties.getKnowledgeEnabled());
    }

    private int resolveTopK(AiChatRequest request) {
        Integer topK = request.getKnowledgeTopK() != null ? request.getKnowledgeTopK() : aiProperties.getKnowledgeTopK();
        return topK == null ? 4 : Math.max(1, Math.min(topK, 8));
    }

    private String resolveRetrievalQuery(AiChatRequest request, AgentExecutionPlan executionPlan) {
        if (StrUtil.isNotBlank(executionPlan.getRetrievalQuery())) {
            return executionPlan.getRetrievalQuery();
        }
        return request.getMessage();
    }

    private ChatClient.ChatClientRequestSpec buildRequestSpec(ChatClient.Builder builder,
                                                              String message,
                                                              AgentExecutionPlan executionPlan,
                                                              List<AiRetrievalHit> retrievalHits,
                                                              boolean useKnowledge,
                                                              String retrievalQuery,
                                                              int topK,
                                                              String prefetchedContext,
                                                              AssembledChatContext assembledContext) {
        ChatClient.Builder chatClientBuilder = builder.clone()
                .defaultToolCallbacks(toolCallbackProvider)
                .defaultSystem(buildSystemPrompt(executionPlan, retrievalHits, localLifeRagService.isReady() && useKnowledge, prefetchedContext, assembledContext));

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
        ChatClient.ChatClientRequestSpec requestSpec = chatClient.prompt()
                .user(message);

        if (useKnowledge) {
            QuestionAnswerAdvisor questionAnswerAdvisor = localLifeRagService.buildAdvisor(retrievalQuery, topK);
            if (questionAnswerAdvisor != null) {
                requestSpec = requestSpec.advisors(questionAnswerAdvisor);
            }
        }

        if (executionPlan.getPreferredTools() != null && !executionPlan.getPreferredTools().isEmpty()) {
            requestSpec = requestSpec.toolNames(executionPlan.getPreferredTools().toArray(new String[0]));
        }
        return requestSpec;
    }

    private String buildSystemPrompt(AgentExecutionPlan executionPlan,
                                     List<AiRetrievalHit> retrievalHits,
                                     boolean ragAdvisorEnabled,
                                     String prefetchedContext,
                                     AssembledChatContext assembledContext) {
        StringBuilder builder = new StringBuilder(SYSTEM_PROMPT);
        if (executionPlan != null) {
            builder.append("\nCurrent plan: intent=")
                    .append(StrUtil.blankToDefault(executionPlan.getIntent(), "general"))
                    .append(", responseStyle=")
                    .append(StrUtil.blankToDefault(executionPlan.getResponseStyle(), "concise"))
                    .append(", reasoningFocus=")
                    .append(StrUtil.blankToDefault(executionPlan.getReasoningFocus(), "grounded_facts"));
            if (executionPlan.getPreferredTools() != null && !executionPlan.getPreferredTools().isEmpty()) {
                builder.append(", preferredTools=").append(executionPlan.getPreferredTools());
            }
            builder.append(".");
        }
        if (StrUtil.isNotBlank(prefetchedContext)) {
            builder.append("\nPrefetched business facts:\n")
                    .append(prefetchedContext)
                    .append("\nUse these facts directly, and organize them into a concise Chinese answer.");
        }
        if (assembledContext != null && StrUtil.isNotBlank(assembledContext.getPromptContext())) {
            builder.append("\nCompressed conversation context:\n")
                    .append(assembledContext.getPromptContext());
        }
        if (ragAdvisorEnabled) {
            builder.append("\nRAG is enabled for background context. Dynamic facts should still follow business data.");
            return builder.toString();
        }
        if (retrievalHits == null || retrievalHits.isEmpty()) {
            return builder.toString();
        }
        builder.append("\nBackground knowledge snippets:\n");
        int index = 1;
        for (AiRetrievalHit hit : retrievalHits) {
            builder.append(index++)
                    .append(". [")
                    .append(hit.getSourceType())
                    .append("#")
                    .append(hit.getSourceId())
                    .append("] ")
                    .append(StrUtil.blankToDefault(hit.getTitle(), "Untitled"))
                    .append(" | score=")
                    .append(hit.getScore())
                    .append(" | ")
                    .append(hit.getSnippet())
                    .append("\n");
        }
        return builder.toString();
    }

    private PrefetchedToolContext prefetchToolContext(String message, AgentExecutionPlan executionPlan, UserDTO currentUser) {
        if (!Boolean.TRUE.equals(executionPlan.getUseTools())
                || executionPlan.getPreferredTools() == null
                || executionPlan.getPreferredTools().isEmpty()) {
            return PrefetchedToolContext.empty();
        }

        boolean couponOnly = containsAny(message.toLowerCase(Locale.ROOT), "优惠", "券", "折扣", "coupon", "discount");
        boolean nearbyRequested = Boolean.TRUE.equals(executionPlan.getNearby())
                || containsAny(message.toLowerCase(Locale.ROOT), "附近", "周边", "nearby");
        UserLocationContext userLocation = loadUserLocationContext(currentUser);
        Double effectiveX = userLocation.longitude();
        Double effectiveY = userLocation.latitude();
        BaiduMapGeoService.GeoPoint explicitGeoPoint = resolveExplicitGeoPoint(executionPlan, userLocation);
        if (explicitLocationOverridesCurrentUser(executionPlan, userLocation)) {
            effectiveX = explicitGeoPoint == null ? null : explicitGeoPoint.lng();
            effectiveY = explicitGeoPoint == null ? null : explicitGeoPoint.lat();
        }

        ShopRecommendationQuery recommendationQuery = buildRecommendationQuery(
                message, executionPlan, couponOnly, effectiveX, effectiveY
        );
        if (StrUtil.isBlank(recommendationQuery.getCity())
                && StrUtil.isNotBlank(recommendationQuery.getLocationHint())
                && StrUtil.isNotBlank(userLocation.city())) {
            recommendationQuery.setCity(userLocation.city());
        }

        List<String> preferredTools = executionPlan.getPreferredTools();
        List<String> contextLines = new ArrayList<String>();
        StringBuilder answer = new StringBuilder();

        if (preferredTools.contains("recommend_shops")) {
            List<ShopRecommendationDTO> recommendations = localLifeAgentTools.recommendShops(recommendationQuery);
            if (!recommendations.isEmpty()) {
                if (userLocation.available()) {
                    contextLines.add("user_location: city=" + safe(userLocation.city())
                            + ", address=" + safe(userLocation.address())
                            + ", longitude=" + safe(userLocation.longitude())
                            + ", latitude=" + safe(userLocation.latitude()));
                }
                if (explicitGeoPoint != null) {
                    contextLines.add("explicit_location: address=" + safe(explicitGeoPoint.resolvedAddress())
                            + ", longitude=" + safe(explicitGeoPoint.lng())
                            + ", latitude=" + safe(explicitGeoPoint.lat()));
                }
                int index = 1;
                for (ShopRecommendationDTO item : recommendations) {
                    contextLines.add("recommendation#" + index + ": shopId=" + item.getShopId()
                            + ", name=" + item.getName()
                            + ", area=" + safe(item.getArea())
                            + ", address=" + safe(item.getAddress())
                            + ", avgPrice=" + safe(item.getAvgPrice())
                            + ", score=" + safe(item.getScore())
                            + ", distanceMeters=" + safe(item.getDistanceMeters())
                            + ", couponCount=" + safe(item.getCouponCount())
                            + ", couponSummary=" + safe(item.getCouponSummary())
                            + ", blogSummary=" + safe(item.getBlogSummary())
                            + ", reasonTags=" + safe(item.getReasonTags()));
                    answer.append(index++)
                            .append(". ")
                            .append(item.getName())
                            .append("，区域：").append(safe(item.getArea()))
                            .append("，人均：").append(formatPrice(item.getAvgPrice()))
                            .append("，评分：").append(formatScore(item.getScore()));
                    if (item.getDistanceMeters() != null) {
                        answer.append("，距离：").append(formatDistance(item.getDistanceMeters()));
                    }
                    if (item.getCouponCount() != null && item.getCouponCount() > 0) {
                        answer.append("，优惠：").append(item.getCouponCount()).append(" 张");
                    }
                    if (StrUtil.isNotBlank(item.getAddress())) {
                        answer.append("，地址：").append(item.getAddress());
                    }
                    answer.append("\n");
                }
                return new PrefetchedToolContext(String.join("\n", contextLines), answer.toString().trim(), contextLines.size());
            }
            if (nearbyRequested && userLocation.available()) {
                contextLines.add("user_location: city=" + safe(userLocation.city())
                        + ", address=" + safe(userLocation.address())
                        + ", longitude=" + safe(userLocation.longitude())
                        + ", latitude=" + safe(userLocation.latitude())
                        + ", nearbyResult=empty");
                return new PrefetchedToolContext(
                        String.join("\n", contextLines),
                        "我按你当前地址附近查了一遍，但暂时没有找到满足条件的候选店。你可以放宽预算、换一个品类，或者去掉优惠限制再试一次。",
                        contextLines.size()
                );
            }
        }

        if (preferredTools.contains("search_shops") || preferredTools.contains("get_shop_detail")) {
            if (nearbyRequested && userLocation.available()) {
                return PrefetchedToolContext.empty();
            }
            String keyword = resolveKeyword(message, executionPlan);
            Integer typeId = resolveTypeId(message, executionPlan);
            List<LocalLifeAgentTools.ShopCard> shops = localLifeAgentTools.searchShops(keyword, typeId, 1);
            if (!shops.isEmpty()) {
                int index = 1;
                for (LocalLifeAgentTools.ShopCard item : shops) {
                    contextLines.add("shop#" + index + ": shopId=" + item.shopId()
                            + ", name=" + item.name()
                            + ", area=" + safe(item.area())
                            + ", address=" + safe(item.address())
                            + ", avgPrice=" + safe(item.avgPrice())
                            + ", score=" + safe(item.score())
                            + ", openHours=" + safe(item.openHours()));
                    answer.append(index++)
                            .append(". ")
                            .append(item.name())
                            .append("，区域：").append(safe(item.area()))
                            .append("，人均：").append(formatPrice(item.avgPrice()))
                            .append("，评分：").append(formatScore(item.score()));
                    if (StrUtil.isNotBlank(item.openHours())) {
                        answer.append("，营业时间：").append(item.openHours());
                    }
                    if (StrUtil.isNotBlank(item.address())) {
                        answer.append("，地址：").append(item.address());
                    }
                    answer.append("\n");
                }
                return new PrefetchedToolContext(String.join("\n", contextLines), answer.toString().trim(), contextLines.size());
            }
        }

        if (preferredTools.contains("get_hot_blogs")) {
            List<LocalLifeAgentTools.BlogCard> blogs = localLifeAgentTools.getHotBlogs(5);
            if (!blogs.isEmpty()) {
                int index = 1;
                for (LocalLifeAgentTools.BlogCard item : blogs) {
                    contextLines.add("blog#" + index + ": blogId=" + item.blogId()
                            + ", title=" + safe(item.title())
                            + ", liked=" + safe(item.liked())
                            + ", shopId=" + safe(item.shopId())
                            + ", snippet=" + safe(item.snippet()));
                    answer.append(index++)
                            .append(". ")
                            .append(item.title())
                            .append("，点赞：").append(safe(item.liked()))
                            .append("，摘要：").append(safe(item.snippet()))
                            .append("\n");
                }
                return new PrefetchedToolContext(String.join("\n", contextLines), answer.toString().trim(), contextLines.size());
            }
        }

        return PrefetchedToolContext.empty();
    }

    private UserLocationContext loadUserLocationContext(UserDTO currentUser) {
        if (currentUser == null || currentUser.getId() == null) {
            return UserLocationContext.empty();
        }
        UserInfo userInfo = userInfoService.getById(currentUser.getId());
        if (userInfo == null) {
            return UserLocationContext.empty();
        }
        return new UserLocationContext(
                userInfo.getCity(),
                userInfo.getAddress(),
                userInfo.getLocationX(),
                userInfo.getLocationY()
        );
    }

    private boolean explicitLocationOverridesCurrentUser(AgentExecutionPlan executionPlan, UserLocationContext userLocation) {
        if (executionPlan == null || (!StrUtil.isNotBlank(executionPlan.getCity()) && !StrUtil.isNotBlank(executionPlan.getLocationHint()))) {
            return false;
        }
        if (!userLocation.available()) {
            return true;
        }
        String currentAddress = StrUtil.blankToDefault(userLocation.address(), "");
        if (StrUtil.isNotBlank(executionPlan.getCity()) && !currentAddress.contains(executionPlan.getCity())) {
            return true;
        }
        return StrUtil.isNotBlank(executionPlan.getLocationHint()) && !currentAddress.contains(executionPlan.getLocationHint());
    }

    private BaiduMapGeoService.GeoPoint resolveExplicitGeoPoint(AgentExecutionPlan executionPlan, UserLocationContext userLocation) {
        if (executionPlan == null || baiduMapGeoService == null) {
            return null;
        }
        String city = executionPlan.getCity();
        if (StrUtil.isBlank(city) && userLocation != null) {
            city = userLocation.city();
        }
        return baiduMapGeoService.geocode(city, executionPlan.getLocationHint());
    }

    private ShopRecommendationQuery buildRecommendationQuery(String message,
                                                             AgentExecutionPlan executionPlan,
                                                             boolean couponOnly,
                                                             Double effectiveX,
                                                             Double effectiveY) {
        ShopRecommendationQuery query = new ShopRecommendationQuery();
        query.setKeyword(resolveKeyword(message, executionPlan));
        query.setTypeId(resolveTypeId(message, executionPlan));
        query.setMaxBudget(resolveBudget(message, executionPlan));
        query.setCity(executionPlan == null ? null : executionPlan.getCity());
        query.setLocationHint(executionPlan == null ? null : executionPlan.getLocationHint());
        query.setX(effectiveX);
        query.setY(effectiveY);
        query.setCouponOnly(couponOnly);
        query.setLimit(5);
        query.setSubcategory(executionPlan == null ? null : executionPlan.getSubcategory());
        query.setQualityPreference(executionPlan == null ? null : executionPlan.getQualityPreference());
        query.setPartySize(executionPlan == null ? null : executionPlan.getPartySize());
        if (executionPlan != null && executionPlan.getExcludedCategories() != null) {
            query.setExcludedCategories(new ArrayList<String>(executionPlan.getExcludedCategories()));
        }
        if (executionPlan != null && executionPlan.getNegativePreferences() != null) {
            query.setNegativePreferences(new ArrayList<String>(executionPlan.getNegativePreferences()));
        }
        return query;
    }

    private String extractKeyword(String message, AgentExecutionPlan executionPlan) {
        String normalized = StrUtil.blankToDefault(message, "")
                .replace("帮我", "")
                .replace("请帮我", "")
                .replace("给我", "")
                .replace("推荐", "")
                .replace("附近", "")
                .replace("周边", "")
                .replace("一带", "")
                .replace("人均", "")
                .replace("预算", "")
                .replace("优惠券", "")
                .replace("优惠", "")
                .replace("营业时间", "")
                .replace("价格", "")
                .replace("好吃", "")
                .replace("评价高", "")
                .replace("评价好的", "")
                .replace("评分高", "")
                .replace("餐厅", "")
                .replace("馆子", "")
                .replace("小馆", "")
                .replace("饭店", "")
                .replace("店", "")
                .replace("，", " ")
                .replace("。", " ")
                .replace("！", " ")
                .replace("？", " ")
                .replace(",", " ")
                .replace(".", " ")
                .replace("!", " ")
                .replace("?", " ")
                .trim();
        if (executionPlan != null) {
            if (StrUtil.isNotBlank(executionPlan.getCity())) {
                normalized = normalized.replace(executionPlan.getCity(), "");
            }
            if (StrUtil.isNotBlank(executionPlan.getLocationHint())) {
                normalized = normalized.replace(executionPlan.getLocationHint(), "");
            }
            if (executionPlan.getExcludedCategories() != null) {
                for (String excludedCategory : executionPlan.getExcludedCategories()) {
                    normalized = normalized.replace(excludedCategory, "");
                }
            }
        }
        for (String priceHint : PRICE_HINTS) {
            normalized = normalized.replace(priceHint, "");
        }
        return normalized.trim();
    }

    private String resolveKeyword(String message, AgentExecutionPlan executionPlan) {
        List<String> parts = new ArrayList<String>();
        if (executionPlan != null) {
            if (StrUtil.isNotBlank(executionPlan.getCity())) {
                parts.add(executionPlan.getCity());
            }
            if (StrUtil.isNotBlank(executionPlan.getLocationHint())) {
                parts.add(executionPlan.getLocationHint());
            }
            if (StrUtil.isNotBlank(executionPlan.getSubcategory())) {
                parts.add(executionPlan.getSubcategory());
            } else if (StrUtil.isNotBlank(executionPlan.getCategory())) {
                parts.add(executionPlan.getCategory());
            }
            if (StrUtil.isNotBlank(executionPlan.getPricePreference()) && "cheap".equals(executionPlan.getPricePreference())) {
                parts.add("便宜");
            }
            if (StrUtil.isNotBlank(executionPlan.getQualityPreference())) {
                parts.add(executionPlan.getQualityPreference());
            }
        }
        String structured = String.join(" ", parts).trim();
        return StrUtil.isNotBlank(structured) ? structured : extractKeyword(message, executionPlan);
    }

    private Integer resolveTypeId(String message, AgentExecutionPlan executionPlan) {
        String category = executionPlan == null ? null : StrUtil.blankToDefault(executionPlan.getSubcategory(), executionPlan.getCategory());
        if (StrUtil.isNotBlank(category)) {
            String normalized = category.toLowerCase(Locale.ROOT);
            if (containsAny(normalized, "餐厅", "海鲜", "火锅", "烧烤", "快餐", "咖啡", "奶茶", "西餐", "日料", "家常菜", "粤菜", "川湘")) {
                return 1;
            }
        }
        return guessTypeId(message);
    }

    private Long resolveBudget(String message, AgentExecutionPlan executionPlan) {
        if (executionPlan != null && executionPlan.getBudgetMax() != null) {
            return executionPlan.getBudgetMax();
        }
        return extractBudget(message);
    }

    private Integer guessTypeId(String message) {
        String normalized = StrUtil.blankToDefault(message, "").toLowerCase(Locale.ROOT);
        if (containsAny(normalized, "ktv")) {
            return 2;
        }
        if (containsAny(normalized, "美发", "理发")) {
            return 3;
        }
        if (containsAny(normalized, "健身", "运动")) {
            return 4;
        }
        if (containsAny(normalized, "按摩", "足疗")) {
            return 5;
        }
        if (containsAny(normalized, "美容", "spa")) {
            return 6;
        }
        if (containsAny(normalized, "亲子", "游乐")) {
            return 7;
        }
        if (containsAny(normalized, "酒吧", "bar", "pub")) {
            return 8;
        }
        if (containsAny(normalized, "美甲", "美睫")) {
            return 10;
        }
        return 1;
    }

    private Long extractBudget(String message) {
        Matcher matcher = BUDGET_PATTERN.matcher(message);
        if (matcher.find()) {
            return Long.parseLong(matcher.group(1));
        }
        String normalized = StrUtil.blankToDefault(message, "").toLowerCase(Locale.ROOT);
        if (containsAny(normalized, "不贵", "便宜", "平价", "实惠", "学生党")) {
            return 100L;
        }
        return null;
    }

    private boolean containsAny(String source, String... values) {
        for (String value : values) {
            if (source.contains(value)) {
                return true;
            }
        }
        return false;
    }

    private void streamPlainAnswer(SseEmitter emitter,
                                   String conversationId,
                                   String storageConversationId,
                                   String answer,
                                   List<AiRetrievalHit> retrievalHits,
                                   AgentExecutionPlan executionPlan,
                                   UserDTO currentUser,
                                   AiChatRequest request,
                                   long startedAt) {
        try {
            for (int i = 0; i < answer.length(); i += 24) {
                String chunk = answer.substring(i, Math.min(i + 24, answer.length()));
                sendEvent(emitter, "chunk", Map.of("content", chunk));
            }
            AiChatResponse response = new AiChatResponse(conversationId, answer, traceContext.snapshot(), retrievalHits, executionPlan);
            recordTurnQuietly(storageConversationId, currentUser == null ? null : currentUser.getId(),
                    request == null ? null : request.getMessage(), answer, executionPlan);
            sendEvent(emitter, "done", response);
            audit(currentUser, request, response, startedAt, "SUCCESS");
        } catch (IOException e) {
            log.debug("Failed to stream prefetched answer", e);
        } finally {
            traceContext.clear();
            emitter.complete();
        }
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
                .model(System.getenv().getOrDefault("AI_MODEL", "gpt-5.4-mini"))
                .intent(response.getPlan() == null ? null : response.getPlan().getIntent())
                .retrievalQuery(response.getPlan() == null ? null : response.getPlan().getRetrievalQuery())
                .retrievalCount(response.getRetrievalHits() == null ? 0 : response.getRetrievalHits().size())
                .toolTraceCount(response.getToolTrace() == null ? 0 : response.getToolTrace().size())
                .latencyMs(System.currentTimeMillis() - startedAt)
                .toolTrace(response.getToolTrace())
                .build());
    }

    private AiChatResponse failResponse(String conversationId, String message) {
        return new AiChatResponse(conversationId, message, Collections.emptyList(), Collections.emptyList(), null);
    }

    private String resolveClientConversationId(String conversationId) {
        return StrUtil.blankToDefault(conversationId, DEFAULT_CONVERSATION_ID);
    }

    private String storageConversationId(Long userId, String clientConversationId) {
        return aiProperties.getDefaultConversationPrefix() + ":" + userId + ":" + clientConversationId;
    }

    private String safe(Object value) {
        return value == null ? "-" : String.valueOf(value);
    }

    private String formatPrice(Long price) {
        return price == null ? "未知" : price + " 元";
    }

    private String formatScore(Double score) {
        return score == null ? "未知" : String.format(Locale.US, "%.1f", score);
    }

    private String formatDistance(Double distanceMeters) {
        if (distanceMeters == null) {
            return "未知";
        }
        if (distanceMeters < 1000D) {
            return Math.round(distanceMeters) + " 米";
        }
        return String.format(Locale.US, "%.1f 公里", distanceMeters / 1000D);
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

    private String collectStreamContent(ChatClient.ChatClientRequestSpec requestSpec,
                                        String systemPrompt,
                                        String userPrompt) {
        try {
            String content = requestSpec.stream()
                    .content()
                    .reduce(new StringBuilder(), StringBuilder::append)
                    .map(StringBuilder::toString)
                    .block();
            return content == null ? "" : content;
        } catch (Exception ex) {
            if (openAiCompatibleStreamBridge != null && openAiCompatibleStreamBridge.available()) {
                traceContext.record("llm_stream_bridge_fallback(reason=" + ex.getClass().getSimpleName() + ")");
                String content = openAiCompatibleStreamBridge.chat(systemPrompt, userPrompt);
                return content == null ? "" : content;
            }
            if (ex instanceof UnsupportedOperationException) {
                String content = requestSpec.call().content();
                return content == null ? "" : content;
            }
            throw ex;
        }
    }

    private AssembledChatContext assembleContext(String storageConversationId,
                                                 Long userId,
                                                 String message,
                                                 AgentExecutionPlan executionPlan,
                                                 List<AiRetrievalHit> retrievalHits,
                                                 String prefetchedContext) {
        if (contextCompressionService == null) {
            return AssembledChatContext.empty();
        }
        AssembledChatContext context = contextCompressionService.assemble(
                storageConversationId,
                userId,
                message,
                executionPlan,
                SYSTEM_PROMPT,
                prefetchedContext,
                retrievalHits
        );
        traceContext.record("context_compression(recentTurns=" + context.getRecentTurnCount()
                + ", summaryHits=" + context.getSummaryHitCount()
                + ", longTermMemoryHits=" + context.getLongTermMemoryHitCount()
                + ", tokensBefore=" + context.getEstimatedTokensBefore()
                + ", tokensAfter=" + context.getEstimatedTokensAfter()
                + ", summaryUpdated=" + context.isSummaryUpdated() + ")");
        return context;
    }

    private void recordTurnQuietly(String storageConversationId,
                                   Long userId,
                                   String message,
                                   String answer,
                                   AgentExecutionPlan executionPlan) {
        if (contextCompressionService == null || StrUtil.isBlank(storageConversationId)) {
            return;
        }
        try {
            contextCompressionService.recordTurn(storageConversationId, userId, message, answer, executionPlan);
        } catch (Exception e) {
            log.debug("Failed to record conversation turn for {}", storageConversationId, e);
        }
    }

    private record PrefetchedToolContext(String promptContext, String directAnswer, int lineCount) {
        private static PrefetchedToolContext empty() {
            return new PrefetchedToolContext("", "", 0);
        }

        private boolean hasData() {
            return StrUtil.isNotBlank(directAnswer);
        }
    }

    private record UserLocationContext(String city, String address, Double longitude, Double latitude) {
        private static UserLocationContext empty() {
            return new UserLocationContext("", "", null, null);
        }

        private boolean available() {
            return StrUtil.isNotBlank(address) && longitude != null && latitude != null;
        }
    }
}
