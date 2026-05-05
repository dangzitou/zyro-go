package com.hmdp.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.hmdp.dto.ShopRecommendationDTO;
import com.hmdp.dto.ShopRecommendationQuery;
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

        AiAgentServiceImpl service = new AiAgentServiceImpl(
                properties,
                emptyProvider(),
                emptyToolCallbackProvider(),
                knowledgeService,
                planningService,
                auditService,
                ragService,
                new StaticListableBeanFactory().getBeanProvider(org.springframework.ai.model.tool.ToolCallingManager.class),
                new AgentTraceContext(),
                localLifeAgentTools,
                userInfoService,
                null,
                contextCompressionService(properties, defaultChatMemory(), new InMemoryChatContextRepository()),
                null
        );

        AiChatRequest request = new AiChatRequest();
        request.setMessage("帮我推荐附近火锅");

        AiChatResponse response = service.chat(request);
        assertTrue(response.getAnswer().contains("AI Agent"));
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

        ChatMemory chatMemory = defaultChatMemory(12);
        AiAgentServiceImpl service = new AiAgentServiceImpl(
                properties,
                beanFactory.getBeanProvider(ChatClient.Builder.class),
                emptyToolCallbackProvider(),
                knowledgeService,
                planningService,
                auditService,
                ragService,
                beanFactory.getBeanProvider(org.springframework.ai.model.tool.ToolCallingManager.class),
                new AgentTraceContext(),
                localLifeAgentTools,
                userInfoService,
                null,
                contextCompressionService(properties, chatMemory, new InMemoryChatContextRepository()),
                null
        );

        UserHolder.saveUser(currentUser(7L, "tester"));

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
    void shouldUseBrowserLocationAndReverseGeocodeForNearbyRecommendation() {
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

        AgentExecutionPlan plan = new AgentExecutionPlan("recommendation", false, true, "附近 餐厅", "concise", "compare_candidates",
                List.of("recommend_shops"));
        plan.setNearby(true);
        when(planningService.plan(anyString())).thenReturn(plan);
        when(baiduMapGeoService.reverseGeocode(113.328970D, 23.136996D))
                .thenReturn(new BaiduMapGeoService.ReverseGeoPoint(
                        113.328970D,
                        23.136996D,
                        "广州市",
                        "天河区",
                        "体育西路",
                        "体育西商圈",
                        "广州市天河区体育西路附近"
                ));
        when(localLifeAgentTools.recommendShops(any(ShopRecommendationQuery.class))).thenReturn(List.of(
                recommendation("潮汕牛肉", 320D)
        ));

        AiAgentServiceImpl service = new AiAgentServiceImpl(
                properties,
                emptyProvider(),
                emptyToolCallbackProvider(),
                knowledgeService,
                planningService,
                auditService,
                ragService,
                new StaticListableBeanFactory().getBeanProvider(org.springframework.ai.model.tool.ToolCallingManager.class),
                new AgentTraceContext(),
                localLifeAgentTools,
                userInfoService,
                baiduMapGeoService,
                contextCompressionService(properties, defaultChatMemory(), new InMemoryChatContextRepository()),
                null
        );

        UserHolder.saveUser(currentUser(12L, "geo-user"));
        AiChatRequest request = new AiChatRequest();
        request.setMessage("帮我看看我附近有什么吃的");
        request.setCurrentLocation(browserLocation(113.328970D, 23.136996D, 20D));

        AiChatResponse response = service.chat(request);

        verify(baiduMapGeoService).reverseGeocode(113.328970D, 23.136996D);
        verify(localLifeAgentTools).recommendShops(argThat(query ->
                query != null
                        && Double.valueOf(113.328970D).equals(query.getX())
                        && Double.valueOf(23.136996D).equals(query.getY())));
        assertTrue(response.getToolTrace().stream().anyMatch(line -> line.contains("request_user_location() -> available=true")));
        assertTrue(response.getToolTrace().stream().anyMatch(line -> line.contains("prefetch_context")));
    }

    @Test
    void shouldReturnNearbyEmptyMessageInsteadOfGenericFailureWhenNothingMatches() {
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

        AgentExecutionPlan plan = new AgentExecutionPlan("recommendation", false, true, "附近 火锅", "concise", "compare_candidates",
                List.of("recommend_shops", "search_shops"));
        plan.setNearby(true);
        when(planningService.plan(anyString())).thenReturn(plan);
        when(baiduMapGeoService.reverseGeocode(113.328970D, 23.136996D))
                .thenReturn(new BaiduMapGeoService.ReverseGeoPoint(
                        113.328970D,
                        23.136996D,
                        "广州市",
                        "天河区",
                        "体育西路",
                        "体育西商圈",
                        "广州市天河区体育西路附近"
                ));
        when(localLifeAgentTools.recommendShops(any(ShopRecommendationQuery.class))).thenReturn(List.of());

        AiAgentServiceImpl service = new AiAgentServiceImpl(
                properties,
                emptyProvider(),
                emptyToolCallbackProvider(),
                knowledgeService,
                planningService,
                auditService,
                ragService,
                new StaticListableBeanFactory().getBeanProvider(org.springframework.ai.model.tool.ToolCallingManager.class),
                new AgentTraceContext(),
                localLifeAgentTools,
                userInfoService,
                baiduMapGeoService,
                contextCompressionService(properties, defaultChatMemory(), new InMemoryChatContextRepository()),
                null
        );

        UserHolder.saveUser(currentUser(1L, "tester"));
        AiChatRequest request = new AiChatRequest();
        request.setMessage("推荐我附近的人均150以内火锅店");
        request.setCurrentLocation(browserLocation(113.328970D, 23.136996D, 35D));

        AiChatResponse response = service.chat(request);

        assertTrue(response.getAnswer().contains("暂时没有"));
        assertTrue(response.getToolTrace().stream().anyMatch(line -> line.contains("prefetch_context")));
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
            if (message.contains("辣")) {
                plan.setNegativePreferences(List.of("太辣"));
            }
            return plan;
        });

        FixedChatModel chatModel = new FixedChatModel("已收到，我会按你的条件继续推荐。");
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("chatClientBuilder", ChatClient.builder(chatModel));

        ChatMemory chatMemory = defaultChatMemory(80);
        InMemoryChatContextRepository contextRepository = new InMemoryChatContextRepository();
        AiAgentServiceImpl service = new AiAgentServiceImpl(
                properties,
                beanFactory.getBeanProvider(ChatClient.Builder.class),
                emptyToolCallbackProvider(),
                knowledgeService,
                planningService,
                auditService,
                ragService,
                beanFactory.getBeanProvider(org.springframework.ai.model.tool.ToolCallingManager.class),
                new AgentTraceContext(),
                localLifeAgentTools,
                userInfoService,
                null,
                contextCompressionService(properties, chatMemory, contextRepository),
                null
        );

        UserHolder.saveUser(currentUser(99L, "pressure-user"));

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
        boolean droppedContextObserved = false;

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
            droppedContextObserved = droppedContextObserved
                    || lastResponse.getToolTrace().stream().anyMatch(line -> line.contains("droppedContextKinds="));
        }

        assertTrue(compressionTriggered);
        assertTrue(summaryHitObserved);
        assertTrue(memoryHitObserved);
        assertTrue(droppedContextObserved);
        assertTrue(lastResponse.getToolTrace().stream().anyMatch(line -> line.contains("tokensBefore=")));
        assertTrue(contextRepository.loadConversationSummary("agent-session:99:stress-ctx").hasContent());
        assertFalse(contextRepository.loadLongTermMemories(99L).isEmpty());
    }

    private ShopRecommendationDTO recommendation(String name, Double distanceMeters) {
        ShopRecommendationDTO dto = new ShopRecommendationDTO();
        dto.setShopId(1L);
        dto.setName(name);
        dto.setArea("天河");
        dto.setAddress("广州大道1号");
        dto.setAvgPrice(88L);
        dto.setScore(4.8D);
        dto.setDistanceMeters(distanceMeters);
        dto.setCouponCount(1);
        dto.setCouponSummary("双人套餐");
        dto.setReasonTags(List.of("近", "性价比"));
        return dto;
    }

    private AiChatRequest.BrowserLocation browserLocation(Double longitude, Double latitude, Double accuracy) {
        AiChatRequest.BrowserLocation location = new AiChatRequest.BrowserLocation();
        location.setLongitude(longitude);
        location.setLatitude(latitude);
        location.setAccuracyMeters(accuracy);
        return location;
    }

    private UserDTO currentUser(Long userId, String nickName) {
        UserDTO user = new UserDTO();
        user.setId(userId);
        user.setNickName(nickName);
        return user;
    }

    private void stubEmptyTools(LocalLifeAgentTools localLifeAgentTools) {
        when(localLifeAgentTools.searchShops(anyString(), any(), anyInt())).thenReturn(List.of());
        when(localLifeAgentTools.getHotBlogs(anyInt())).thenReturn(List.of());
        when(localLifeAgentTools.recommendShops(anyString(), any(), any(), any(), any(), any(), anyInt())).thenReturn(List.of());
        when(localLifeAgentTools.recommendShops(any(ShopRecommendationQuery.class))).thenReturn(List.of());
    }

    private ObjectProvider<ChatClient.Builder> emptyProvider() {
        return new StaticListableBeanFactory().getBeanProvider(ChatClient.Builder.class);
    }

    private ToolCallbackProvider emptyToolCallbackProvider() {
        return () -> new org.springframework.ai.tool.ToolCallback[0];
    }

    private ChatMemory defaultChatMemory() {
        return defaultChatMemory(8);
    }

    private ChatMemory defaultChatMemory(int maxMessages) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(maxMessages)
                .build();
    }

    private ContextCompressionService contextCompressionService(AiProperties properties,
                                                               ChatMemory chatMemory,
                                                               InMemoryChatContextRepository repository) {
        return new ContextCompressionService(
                properties,
                chatMemory,
                repository,
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
