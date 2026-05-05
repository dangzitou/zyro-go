package com.hmdp.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.config.AiProperties;
import com.hmdp.dto.AgentExecutionPlan;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextCompressionServiceTest {

    @Test
    void shouldCompactOldMessagesAndRetainSummaryAndRecentTurns() {
        AiProperties properties = new AiProperties();
        properties.getContextCompression().setEnabled(true);
        properties.getContextCompression().setRecentTurnPairs(2);
        properties.getContextCompression().setSummaryTriggerMessageCount(6);
        properties.getContextCompression().setSoftTokenBudget(100);
        properties.getContextCompression().setHardTokenBudget(180);

        ChatMemory chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(20)
                .build();
        String conversationId = "agent-session:1:test";
        for (int i = 0; i < 6; i++) {
            chatMemory.add(conversationId, new UserMessage("我在广州，第" + i + "次补充需求，预算80，不要火锅，想安静一点"));
            chatMemory.add(conversationId, new AssistantMessage("收到，第" + i + "次记录需求"));
        }

        ContextCompressionService service = buildService(properties, chatMemory, new InMemoryChatContextRepository());

        AgentExecutionPlan plan = new AgentExecutionPlan();
        plan.setCity("广州市");
        plan.setBudgetMax(80L);
        plan.setQualityPreference("安静,适合约会");
        plan.setExcludedCategories(List.of("火锅"));

        AssembledChatContext context = service.assemble(
                conversationId,
                1L,
                "那就在正佳附近再推荐一次",
                plan,
                "system prompt",
                "",
                List.of()
        );

        assertTrue(context.isSummaryUpdated());
        assertTrue(context.getEstimatedTokensAfter() <= properties.getContextCompression().getHardTokenBudget());
        assertFalse(context.getPromptContext().isBlank());
        assertTrue(chatMemory.get(conversationId).size() <= properties.getContextCompression().getRecentTurnPairs() * 2);
    }

    @Test
    void shouldMicroCompactLargeHistoricalToolResultsBeforeSummary() {
        AiProperties properties = new AiProperties();
        properties.getContextCompression().setEnabled(true);
        properties.getContextCompression().setRecentTurnPairs(2);
        properties.getContextCompression().setSummaryTriggerMessageCount(50);
        properties.getContextCompression().setSoftTokenBudget(2000);
        properties.getContextCompression().setHardTokenBudget(2200);
        properties.getContextCompression().setMicroCompactEnabled(true);

        ChatMemory chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(40)
                .build();
        String conversationId = "agent-session:2:test";
        chatMemory.add(conversationId, new UserMessage("帮我看附近吃什么"));
        chatMemory.add(conversationId, new AssistantMessage("""
                recommendation#1: shopId=1, name=A店, avgPrice=88, address=天河路1号, couponSummary=满100减20
                recommendation#2: shopId=2, name=B店, avgPrice=66, address=体育西路2号, couponSummary=学生优惠
                recommendation#3: shopId=3, name=C店, avgPrice=72, address=正佳广场3层, couponSummary=双人餐
                recommendation#4: shopId=4, name=D店, avgPrice=58, address=天环广场4层, couponSummary=工作日套餐
                """));
        chatMemory.add(conversationId, new UserMessage("预算80以内，不要火锅"));
        chatMemory.add(conversationId, new AssistantMessage("收到，我会继续按预算和排除项筛。"));
        chatMemory.add(conversationId, new UserMessage("最好安静一点"));
        chatMemory.add(conversationId, new AssistantMessage("明白，我会优先保留适合安静聊天的店。"));

        ContextCompressionService service = buildService(properties, chatMemory, new InMemoryChatContextRepository());

        AgentExecutionPlan plan = new AgentExecutionPlan();
        plan.setBudgetMax(80L);
        plan.setExcludedCategories(List.of("火锅"));
        plan.setQualityPreference("安静");

        AssembledChatContext context = service.assemble(
                conversationId,
                2L,
                "继续推荐",
                plan,
                "system prompt",
                "",
                List.of()
        );

        assertTrue(context.isMicroCompactTriggered());
        assertTrue(context.getMicroCompactedItems().stream().anyMatch(item -> item.contains("折叠")));
        assertTrue(context.getPromptContext().contains("Recent conversation transcript"));
    }

    private ContextCompressionService buildService(AiProperties properties,
                                                   ChatMemory chatMemory,
                                                   InMemoryChatContextRepository repository) {
        return new ContextCompressionService(
                properties,
                chatMemory,
                repository,
                new TokenBudgetEstimator(),
                new MemoryExtractionService(properties),
                new StaticListableBeanFactory().getBeanProvider(org.springframework.ai.chat.client.ChatClient.Builder.class),
                new StaticListableBeanFactory().getBeanProvider(com.hmdp.ai.rag.DashScopeRerankService.class),
                new ObjectMapper()
        );
    }

    static class InMemoryChatContextRepository implements ChatContextRepository {
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
