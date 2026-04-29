package com.hmdp.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;

@Slf4j
public class RedisChatContextRepository implements ChatContextRepository {

    private static final TypeReference<List<LongTermMemoryFact>> MEMORY_LIST_TYPE =
            new TypeReference<List<LongTermMemoryFact>>() {
            };

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final String summaryKeyPrefix;
    private final String memoryKeyPrefix;

    public RedisChatContextRepository(StringRedisTemplate stringRedisTemplate,
                                      ObjectMapper objectMapper,
                                      String summaryKeyPrefix,
                                      String memoryKeyPrefix) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
        this.summaryKeyPrefix = summaryKeyPrefix;
        this.memoryKeyPrefix = memoryKeyPrefix;
    }

    @Override
    public ConversationSummary loadConversationSummary(String conversationId) {
        String payload = stringRedisTemplate.opsForValue().get(summaryKey(conversationId));
        if (payload == null || payload.isBlank()) {
            return new ConversationSummary();
        }
        try {
            return objectMapper.readValue(payload, ConversationSummary.class);
        } catch (Exception e) {
            log.warn("Failed to load conversation summary for {}", conversationId, e);
            stringRedisTemplate.delete(summaryKey(conversationId));
            return new ConversationSummary();
        }
    }

    @Override
    public void saveConversationSummary(String conversationId, ConversationSummary summary) {
        if (summary == null || !summary.hasContent()) {
            clearConversationSummary(conversationId);
            return;
        }
        try {
            stringRedisTemplate.opsForValue().set(summaryKey(conversationId), objectMapper.writeValueAsString(summary));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to save conversation summary", e);
        }
    }

    @Override
    public void clearConversationSummary(String conversationId) {
        stringRedisTemplate.delete(summaryKey(conversationId));
    }

    @Override
    public List<LongTermMemoryFact> loadLongTermMemories(Long userId) {
        if (userId == null) {
            return new ArrayList<LongTermMemoryFact>();
        }
        String payload = stringRedisTemplate.opsForValue().get(memoryKey(userId));
        if (payload == null || payload.isBlank()) {
            return new ArrayList<LongTermMemoryFact>();
        }
        try {
            return objectMapper.readValue(payload, MEMORY_LIST_TYPE);
        } catch (Exception e) {
            log.warn("Failed to load long-term memories for user {}", userId, e);
            stringRedisTemplate.delete(memoryKey(userId));
            return new ArrayList<LongTermMemoryFact>();
        }
    }

    @Override
    public void saveLongTermMemories(Long userId, List<LongTermMemoryFact> facts) {
        if (userId == null || facts == null || facts.isEmpty()) {
            stringRedisTemplate.delete(memoryKey(userId));
            return;
        }
        try {
            stringRedisTemplate.opsForValue().set(memoryKey(userId), objectMapper.writeValueAsString(facts));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to save long-term memories", e);
        }
    }

    private String summaryKey(String conversationId) {
        return summaryKeyPrefix + conversationId;
    }

    private String memoryKey(Long userId) {
        return memoryKeyPrefix + userId;
    }
}
