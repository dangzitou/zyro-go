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

        ContextCompressionService service = new ContextCompressionService(
                properties,
                chatMemory,
                new InMemoryChatContextRepository(),
                new TokenBudgetEstimator(),
                new MemoryExtractionService(properties),
                new StaticListableBeanFactory().getBeanProvider(org.springframework.ai.chat.client.ChatClient.Builder.class),
                new StaticListableBeanFactory().getBeanProvider(com.hmdp.ai.rag.DashScopeRerankService.class),
                new ObjectMapper()
        );

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
