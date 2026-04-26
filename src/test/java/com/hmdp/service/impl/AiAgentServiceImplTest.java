package com.hmdp.service.impl;

import com.hmdp.ai.AgentTraceContext;
import com.hmdp.ai.LocalLifeAgentTools;
import com.hmdp.ai.rag.LocalLifeRagService;
import com.hmdp.config.AiProperties;
import com.hmdp.dto.AgentExecutionPlan;
import com.hmdp.dto.AiChatRequest;
import com.hmdp.dto.AiChatResponse;
import com.hmdp.dto.AiRetrievalHit;
import com.hmdp.dto.UserDTO;
import com.hmdp.service.IAgentAuditService;
import com.hmdp.service.IAgentPlanningService;
import com.hmdp.service.IAiKnowledgeService;
import com.hmdp.service.IUserInfoService;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiAgentServiceImplTest {

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
    }

    @Test
    void shouldReturnDisabledMessageWhenAgentIsOff() {
        AiProperties properties = new AiProperties();
        properties.setEnabled(false);

        IAiKnowledgeService knowledgeService = mock(IAiKnowledgeService.class);
        IAgentPlanningService planningService = mock(IAgentPlanningService.class);
        when(planningService.plan(anyString())).thenReturn(new AgentExecutionPlan());
        IAgentAuditService auditService = mock(IAgentAuditService.class);
        LocalLifeRagService ragService = mock(LocalLifeRagService.class);
        LocalLifeAgentTools localLifeAgentTools = mock(LocalLifeAgentTools.class);
        IUserInfoService userInfoService = mock(IUserInfoService.class);
        stubEmptyTools(localLifeAgentTools);

        ToolCallbackProvider toolCallbackProvider = () -> new org.springframework.ai.tool.ToolCallback[0];
        ChatMemory chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(8)
                .build();

        AiAgentServiceImpl service = new AiAgentServiceImpl(
                properties,
                emptyProvider(),
                toolCallbackProvider,
                chatMemory,
                knowledgeService,
                planningService,
                auditService,
                ragService,
                new StaticListableBeanFactory().getBeanProvider(org.springframework.ai.model.tool.ToolCallingManager.class),
                new AgentTraceContext(),
                localLifeAgentTools,
                userInfoService
        );

        AiChatRequest request = new AiChatRequest();
        request.setMessage("帮我推荐附近火锅");

        AiChatResponse response = service.chat(request);
        assertEquals("AI Agent 当前未启用，请先设置 AI_ENABLED=true。", response.getAnswer());
        assertEquals("default", response.getConversationId());
    }

    @Test
    void shouldCallSpringAiClientAndPersistConversationMemoryWhenNoPrefetchedData() {
        AiProperties properties = new AiProperties();
        properties.setEnabled(true);
        properties.setKnowledgeEnabled(true);
        properties.setKnowledgeTopK(3);
        properties.setMemoryTurns(6);
        properties.setDefaultConversationPrefix("agent-session");
        properties.setRecursiveToolLoopEnabled(false);

        IAiKnowledgeService knowledgeService = mock(IAiKnowledgeService.class);
        IAgentPlanningService planningService = mock(IAgentPlanningService.class);
        IAgentAuditService auditService = mock(IAgentAuditService.class);
        LocalLifeRagService ragService = mock(LocalLifeRagService.class);
        LocalLifeAgentTools localLifeAgentTools = mock(LocalLifeAgentTools.class);
        IUserInfoService userInfoService = mock(IUserInfoService.class);
        stubEmptyTools(localLifeAgentTools);

        when(knowledgeService.retrieve(anyString(), anyInt())).thenReturn(List.of(
                new AiRetrievalHit("shop", 1L, "辣府火锅", "评分4.8，套餐力度大", 1.8D)
        ));
        when(planningService.plan(anyString())).thenReturn(
                new AgentExecutionPlan("recommendation", true, true, "火锅", "concise", "compare_candidates",
                        List.of("recommend_shops", "get_shop_coupons"))
        );
        when(ragService.isReady()).thenReturn(false);

        FixedChatModel chatModel = new FixedChatModel("推荐你试试辣府火锅，券和评分都不错。");
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("chatClientBuilder", ChatClient.builder(chatModel));

        ToolCallbackProvider toolCallbackProvider = () -> new org.springframework.ai.tool.ToolCallback[0];
        ChatMemory chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(12)
                .build();

        AiAgentServiceImpl service = new AiAgentServiceImpl(
                properties,
                beanFactory.getBeanProvider(ChatClient.Builder.class),
                toolCallbackProvider,
                chatMemory,
                knowledgeService,
                planningService,
                auditService,
                ragService,
                beanFactory.getBeanProvider(org.springframework.ai.model.tool.ToolCallingManager.class),
                new AgentTraceContext(),
                localLifeAgentTools,
                userInfoService
        );

        UserDTO user = new UserDTO();
        user.setId(7L);
        user.setNickName("tester");
        UserHolder.saveUser(user);

        AiChatRequest request = new AiChatRequest();
        request.setMessage("给我推荐附近的火锅");
        request.setUseKnowledge(true);

        AiChatResponse response = service.chat(request);

        assertEquals("default", response.getConversationId());
        assertEquals("推荐你试试辣府火锅，券和评分都不错。", response.getAnswer());
        assertEquals(1, response.getRetrievalHits().size());
        assertEquals("recommendation", response.getPlan().getIntent());
        assertTrue(chatModel.lastPrompt.getSystemMessage().getText().contains("辣府火锅"));
        assertFalse(chatMemory.get("agent-session:7:default").isEmpty());

        service.clearSession("default");
        assertTrue(chatMemory.get("agent-session:7:default").isEmpty());
    }

    private void stubEmptyTools(LocalLifeAgentTools localLifeAgentTools) {
        when(localLifeAgentTools.searchShops(anyString(), any(), anyInt())).thenReturn(List.of());
        when(localLifeAgentTools.getHotBlogs(anyInt())).thenReturn(List.of());
        when(localLifeAgentTools.recommendShops(anyString(), any(), any(), any(), any(), any(), anyInt())).thenReturn(List.of());
    }

    private ObjectProvider<ChatClient.Builder> emptyProvider() {
        return new StaticListableBeanFactory().getBeanProvider(ChatClient.Builder.class);
    }

    private static class FixedChatModel implements ChatModel {
        private final String answer;
        private Prompt lastPrompt;

        private FixedChatModel(String answer) {
            this.answer = answer;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            this.lastPrompt = prompt;
            return new ChatResponse(List.of(new Generation(new AssistantMessage(answer))));
        }
    }
}
