package com.hmdp.service.impl;

import com.hmdp.ai.AgentTraceContext;
import com.hmdp.ai.ChatContextRepository;
import com.hmdp.ai.ContextCompressionService;
import com.hmdp.ai.ConversationSummary;
import com.hmdp.ai.LocalLifeAgentTools;
import com.hmdp.ai.LongTermMemoryFact;
import com.hmdp.ai.MemoryExtractionService;
import com.hmdp.ai.TokenBudgetEstimator;
import com.hmdp.ai.rag.BaiduMapGeoService;
import com.hmdp.ai.rag.LocalLifeRagService;
import com.hmdp.config.AiProperties;
import com.hmdp.dto.AgentExecutionPlan;
import com.hmdp.dto.AiChatRequest;
import com.hmdp.dto.AiChatResponse;
import com.hmdp.dto.AiRetrievalHit;
import com.hmdp.dto.ShopRecommendationQuery;
import com.hmdp.dto.ShopRecommendationDTO;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.UserInfo;
import com.hmdp.service.IAgentAuditService;
import com.hmdp.service.IAgentPlanningService;
import com.hmdp.service.IAiKnowledgeService;
import com.hmdp.service.IUserInfoService;
import com.hmdp.utils.UserHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
                knowledgeService,
                planningService,
                auditService,
                ragService,
                new StaticListableBeanFactory().getBeanProvider(org.springframework.ai.model.tool.ToolCallingManager.class),
                new AgentTraceContext(),
                localLifeAgentTools,
                userInfoService,
                null,
                contextCompressionService(properties, chatMemory),
                null
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
                knowledgeService,
                planningService,
                auditService,
                ragService,
                beanFactory.getBeanProvider(org.springframework.ai.model.tool.ToolCallingManager.class),
                new AgentTraceContext(),
                localLifeAgentTools,
                userInfoService,
                null,
                contextCompressionService(properties, chatMemory),
                null
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

    @Test
    void shouldReturnNearbyEmptyMessageInsteadOfGlobalFallback() {
        AiProperties properties = new AiProperties();
        properties.setEnabled(true);
        properties.setKnowledgeEnabled(false);
        properties.setRecursiveToolLoopEnabled(false);

        IAiKnowledgeService knowledgeService = mock(IAiKnowledgeService.class);
        IAgentPlanningService planningService = mock(IAgentPlanningService.class);
        IAgentAuditService auditService = mock(IAgentAuditService.class);
        LocalLifeRagService ragService = mock(LocalLifeRagService.class);
        LocalLifeAgentTools localLifeAgentTools = mock(LocalLifeAgentTools.class);
        IUserInfoService userInfoService = mock(IUserInfoService.class);
        stubEmptyTools(localLifeAgentTools);

        when(planningService.plan(anyString())).thenReturn(
                new AgentExecutionPlan("recommendation", false, true, "附近 火锅", "concise", "compare_candidates",
                        List.of("recommend_shops", "search_shops"))
        );
        when(localLifeAgentTools.recommendShops(anyString(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.<ShopRecommendationDTO>of());
        when(localLifeAgentTools.searchShops(anyString(), any(), anyInt()))
                .thenReturn(List.of());

        UserInfo userInfo = new UserInfo();
        userInfo.setUserId(1L);
        userInfo.setCity("广州市");
        userInfo.setAddress("广州市天河区枫叶路加拿大小区");
        userInfo.setLocationX(113.328970D);
        userInfo.setLocationY(23.136996D);
        when(userInfoService.getById(1L)).thenReturn(userInfo);

        ToolCallbackProvider toolCallbackProvider = () -> new org.springframework.ai.tool.ToolCallback[0];
        ChatMemory chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(8)
                .build();

        AiAgentServiceImpl service = new AiAgentServiceImpl(
                properties,
                emptyProvider(),
                toolCallbackProvider,
                knowledgeService,
                planningService,
                auditService,
                ragService,
                new StaticListableBeanFactory().getBeanProvider(org.springframework.ai.model.tool.ToolCallingManager.class),
                new AgentTraceContext(),
                localLifeAgentTools,
                userInfoService,
                null,
                contextCompressionService(properties, chatMemory),
                null
        );

        UserDTO user = new UserDTO();
        user.setId(1L);
        user.setNickName("tester");
        UserHolder.saveUser(user);

        AiChatRequest request = new AiChatRequest();
        request.setMessage("推荐我附近的人均150以内火锅店");

        AiChatResponse response = service.chat(request);
        assertTrue(response.getAnswer().contains("暂时没有找到满足条件的候选店"));
        assertTrue(response.getToolTrace().stream().anyMatch(line -> line.contains("fallback_prefetch")));
    }

    @Test
    void shouldGeocodeCurrentUserAddressWhenCoordinatesAreMissing() {
        AiProperties properties = new AiProperties();
        properties.setEnabled(true);
        properties.setKnowledgeEnabled(false);
        properties.setRecursiveToolLoopEnabled(false);

        IAiKnowledgeService knowledgeService = mock(IAiKnowledgeService.class);
        IAgentPlanningService planningService = mock(IAgentPlanningService.class);
        IAgentAuditService auditService = mock(IAgentAuditService.class);
        LocalLifeRagService ragService = mock(LocalLifeRagService.class);
        LocalLifeAgentTools localLifeAgentTools = mock(LocalLifeAgentTools.class);
        IUserInfoService userInfoService = mock(IUserInfoService.class);
        BaiduMapGeoService baiduMapGeoService = mock(BaiduMapGeoService.class);
        stubEmptyTools(localLifeAgentTools);

        when(planningService.plan(anyString())).thenReturn(
                new AgentExecutionPlan("recommendation", false, true, "附近 餐厅", "concise", "compare_candidates",
                        List.of("recommend_shops"))
        );
        when(localLifeAgentTools.recommendShops(any(ShopRecommendationQuery.class))).thenReturn(List.of());

        UserInfo userInfo = new UserInfo();
        userInfo.setUserId(12L);
        userInfo.setCity("广州市");
        userInfo.setAddress("广州市天河区枫叶路加拿大小区");
        when(userInfoService.getById(12L)).thenReturn(userInfo);
        when(baiduMapGeoService.geocodeAddress("广州市", "广州市天河区枫叶路加拿大小区"))
                .thenReturn(new BaiduMapGeoService.GeoPoint(113.328970D, 23.136996D, "广州市天河区枫叶路加拿大小区"));

        ToolCallbackProvider toolCallbackProvider = () -> new org.springframework.ai.tool.ToolCallback[0];
        ChatMemory chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(8)
                .build();

        AiAgentServiceImpl service = new AiAgentServiceImpl(
                properties,
                emptyProvider(),
                toolCallbackProvider,
                knowledgeService,
                planningService,
                auditService,
                ragService,
                new StaticListableBeanFactory().getBeanProvider(org.springframework.ai.model.tool.ToolCallingManager.class),
                new AgentTraceContext(),
                localLifeAgentTools,
                userInfoService,
                baiduMapGeoService,
                contextCompressionService(properties, chatMemory),
                null
        );

        UserDTO user = new UserDTO();
        user.setId(12L);
        user.setNickName("geo-user");
        UserHolder.saveUser(user);

        AiChatRequest request = new AiChatRequest();
        request.setMessage("帮我看看我附近有什么吃的");

        service.chat(request);

        verify(baiduMapGeoService).geocodeAddress("广州市", "广州市天河区枫叶路加拿大小区");
        verify(localLifeAgentTools).recommendShops(argThat(query ->
                query != null
                        && Double.valueOf(113.328970D).equals(query.getX())
                        && Double.valueOf(23.136996D).equals(query.getY())));
    }

    @Test
    void shouldUseCurrentUserLocationToolBeforeNearbyRecommendation() {
        AiProperties properties = new AiProperties();
        properties.setEnabled(true);
        properties.setKnowledgeEnabled(false);
        properties.setRecursiveToolLoopEnabled(false);

        IAiKnowledgeService knowledgeService = mock(IAiKnowledgeService.class);
        IAgentPlanningService planningService = mock(IAgentPlanningService.class);
        IAgentAuditService auditService = mock(IAgentAuditService.class);
        LocalLifeRagService ragService = mock(LocalLifeRagService.class);
        LocalLifeAgentTools localLifeAgentTools = mock(LocalLifeAgentTools.class);
        IUserInfoService userInfoService = mock(IUserInfoService.class);
        stubEmptyTools(localLifeAgentTools);

        AgentExecutionPlan plan = new AgentExecutionPlan("recommendation", false, true, "附近 火锅", "concise", "compare_candidates",
                List.of("get_current_user_location", "recommend_shops"));
        plan.setNearby(true);
        when(planningService.plan(anyString())).thenReturn(plan);
        when(localLifeAgentTools.getCurrentUserLocation()).thenReturn(
                new LocalLifeAgentTools.UserLocationCard(
                        true,
                        "广州市",
                        "广州市天河区枫叶路加拿大小区",
                        113.328970D,
                        23.136996D,
                        "ok"
                )
        );
        when(localLifeAgentTools.recommendShops(any(ShopRecommendationQuery.class))).thenReturn(List.of());

        ToolCallbackProvider toolCallbackProvider = () -> new org.springframework.ai.tool.ToolCallback[0];
        ChatMemory chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(8)
                .build();

        AiAgentServiceImpl service = new AiAgentServiceImpl(
                properties,
                emptyProvider(),
                toolCallbackProvider,
                knowledgeService,
                planningService,
                auditService,
                ragService,
                new StaticListableBeanFactory().getBeanProvider(org.springframework.ai.model.tool.ToolCallingManager.class),
                new AgentTraceContext(),
                localLifeAgentTools,
                userInfoService,
                null,
                contextCompressionService(properties, chatMemory),
                null
        );

        UserDTO user = new UserDTO();
        user.setId(21L);
        user.setNickName("nearby-user");
        UserHolder.saveUser(user);

        AiChatRequest request = new AiChatRequest();
        request.setMessage("帮我推荐附近适合聚餐、人均100左右、最好还有优惠券的火锅店");

        service.chat(request);

        verify(localLifeAgentTools).getCurrentUserLocation();
        verify(localLifeAgentTools).recommendShops(argThat(query ->
                query != null
                        && Double.valueOf(113.328970D).equals(query.getX())
                        && Double.valueOf(23.136996D).equals(query.getY())));
    }

    @Test
    void shouldTriggerContextCompressionAfterTwentyPlusPrompts() {
        AiProperties properties = new AiProperties();
        properties.setEnabled(true);
        properties.setKnowledgeEnabled(false);
        properties.setRecursiveToolLoopEnabled(false);
        properties.getContextCompression().setEnabled(true);
        properties.getContextCompression().setRecentTurnPairs(3);
        properties.getContextCompression().setSummaryTriggerMessageCount(10);
        properties.getContextCompression().setSoftTokenBudget(220);
        properties.getContextCompression().setHardTokenBudget(320);

        IAiKnowledgeService knowledgeService = mock(IAiKnowledgeService.class);
        IAgentPlanningService planningService = mock(IAgentPlanningService.class);
        IAgentAuditService auditService = mock(IAgentAuditService.class);
        LocalLifeRagService ragService = mock(LocalLifeRagService.class);
        LocalLifeAgentTools localLifeAgentTools = mock(LocalLifeAgentTools.class);
        IUserInfoService userInfoService = mock(IUserInfoService.class);
        stubEmptyTools(localLifeAgentTools);
        when(ragService.isReady()).thenReturn(false);
        lenient().when(planningService.plan(anyString())).thenAnswer(invocation -> {
            String message = invocation.getArgument(0, String.class);
            AgentExecutionPlan plan = new AgentExecutionPlan();
            plan.setIntent("recommendation");
            plan.setUseKnowledge(false);
            plan.setUseTools(false);
            plan.setResponseStyle("concise");
            plan.setReasoningFocus("compare_candidates");
            plan.setPreferredTools(List.of());
            if (message.contains("广州")) {
                plan.setCity("广州市");
            }
            if (message.contains("正佳")) {
                plan.setLocationHint("正佳广场");
            }
            if (message.contains("火锅")) {
                plan.setExcludedCategories(List.of("火锅"));
            }
            if (message.contains("80")) {
                plan.setBudgetMax(80L);
            }
            if (message.contains("两个人")) {
                plan.setPartySize(2);
            }
            if (message.contains("安静") || message.contains("约会")) {
                plan.setQualityPreference("安静,适合约会");
            }
            return plan;
        });

        FixedChatModel chatModel = new FixedChatModel("已收到，我会按你的条件继续推荐。");
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("chatClientBuilder", ChatClient.builder(chatModel));

        ToolCallbackProvider toolCallbackProvider = () -> new org.springframework.ai.tool.ToolCallback[0];
        ChatMemory chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(80)
                .build();
        InMemoryChatContextRepository contextRepository = new InMemoryChatContextRepository();

        AiAgentServiceImpl service = new AiAgentServiceImpl(
                properties,
                beanFactory.getBeanProvider(ChatClient.Builder.class),
                toolCallbackProvider,
                knowledgeService,
                planningService,
                auditService,
                ragService,
                beanFactory.getBeanProvider(org.springframework.ai.model.tool.ToolCallingManager.class),
                new AgentTraceContext(),
                localLifeAgentTools,
                userInfoService,
                null,
                new ContextCompressionService(
                        properties,
                        chatMemory,
                        contextRepository,
                        new TokenBudgetEstimator(),
                        new MemoryExtractionService(properties),
                        beanFactory.getBeanProvider(ChatClient.Builder.class),
                        new StaticListableBeanFactory().getBeanProvider(com.hmdp.ai.rag.DashScopeRerankService.class),
                        new ObjectMapper()
                ),
                null
        );

        UserDTO user = new UserDTO();
        user.setId(99L);
        user.setNickName("pressure-user");
        UserHolder.saveUser(user);

        List<String> prompts = List.of(
                "我在广州",
                "想在天河这边找吃的",
                "最好别太贵",
                "预算控制在80以内",
                "两个人吃",
                "想安静一点",
                "适合约会更好",
                "不要火锅",
                "可以偏海鲜一点",
                "最好离正佳不要太远",
                "评分高一点",
                "环境好一点",
                "不要太吵",
                "如果有团购更好",
                "人均别超80",
                "我不想吃太辣",
                "正佳附近优先",
                "如果没有海鲜就简餐也行",
                "但还是想适合约会",
                "要安静",
                "不要火锅和串串",
                "那你继续按这些条件想"
        );

        AiChatResponse lastResponse = null;
        boolean compressionTriggered = false;
        boolean summaryHitObserved = false;
        boolean memoryHitObserved = false;

        for (String prompt : prompts) {
            AiChatRequest request = new AiChatRequest();
            request.setConversationId("stress-ctx");
            request.setMessage(prompt);
            lastResponse = service.chat(request);
            compressionTriggered = compressionTriggered
                    || lastResponse.getToolTrace().stream().anyMatch(line -> line.contains("summaryUpdated=true"));
            summaryHitObserved = summaryHitObserved
                    || lastResponse.getToolTrace().stream().anyMatch(line -> line.contains("summaryHits=") && !line.contains("summaryHits=0"));
            memoryHitObserved = memoryHitObserved
                    || lastResponse.getToolTrace().stream().anyMatch(line -> line.contains("longTermMemoryHits=") && !line.contains("longTermMemoryHits=0"));
        }

        assertTrue(compressionTriggered, "Expected summary compaction to trigger after 20+ prompts");
        assertTrue(summaryHitObserved, "Expected compressed summary context to be injected");
        assertTrue(memoryHitObserved, "Expected long-term memory facts to be injected");
        assertTrue(lastResponse.getToolTrace().stream().anyMatch(line -> line.contains("tokensBefore=")));
        assertTrue(contextRepository.loadConversationSummary("agent-session:99:stress-ctx").hasContent());
        assertFalse(contextRepository.loadLongTermMemories(99L).isEmpty());
    }

    private void stubEmptyTools(LocalLifeAgentTools localLifeAgentTools) {
        when(localLifeAgentTools.searchShops(anyString(), any(), anyInt())).thenReturn(List.of());
        when(localLifeAgentTools.getHotBlogs(anyInt())).thenReturn(List.of());
        when(localLifeAgentTools.recommendShops(anyString(), any(), any(), any(), any(), any(), anyInt())).thenReturn(List.of());
    }

    private ObjectProvider<ChatClient.Builder> emptyProvider() {
        return new StaticListableBeanFactory().getBeanProvider(ChatClient.Builder.class);
    }

    private ContextCompressionService contextCompressionService(AiProperties properties, ChatMemory chatMemory) {
        return new ContextCompressionService(
                properties,
                chatMemory,
                new InMemoryChatContextRepository(),
                new TokenBudgetEstimator(),
                new MemoryExtractionService(properties),
                emptyProvider(),
                new StaticListableBeanFactory().getBeanProvider(com.hmdp.ai.rag.DashScopeRerankService.class),
                new ObjectMapper()
        );
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

    private static class InMemoryChatContextRepository implements ChatContextRepository {
        private final Map<String, ConversationSummary> summaries = new LinkedHashMap<String, ConversationSummary>();
        private final Map<Long, List<LongTermMemoryFact>> memories = new LinkedHashMap<Long, List<LongTermMemoryFact>>();

        @Override
        public ConversationSummary loadConversationSummary(String conversationId) {
            return summaries.getOrDefault(conversationId, new ConversationSummary());
        }

        @Override
        public void saveConversationSummary(String conversationId, ConversationSummary summary) {
            summaries.put(conversationId, summary);
        }

        @Override
        public void clearConversationSummary(String conversationId) {
            summaries.remove(conversationId);
        }

        @Override
        public List<LongTermMemoryFact> loadLongTermMemories(Long userId) {
            return new ArrayList<LongTermMemoryFact>(memories.getOrDefault(userId, List.of()));
        }

        @Override
        public void saveLongTermMemories(Long userId, List<LongTermMemoryFact> facts) {
            memories.put(userId, new ArrayList<LongTermMemoryFact>(facts));
        }
    }
}
