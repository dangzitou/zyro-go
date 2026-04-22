package com.hmdp.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
public class RedisChatMemoryRepository implements ChatMemoryRepository {

    private static final TypeReference<List<StoredChatMessage>> STORED_MESSAGE_LIST = new TypeReference<List<StoredChatMessage>>() {
    };

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final Duration ttl;
    private final String keyPrefix;

    public RedisChatMemoryRepository(StringRedisTemplate stringRedisTemplate, ObjectMapper objectMapper,
                                     Duration ttl, String keyPrefix) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
        this.ttl = ttl;
        this.keyPrefix = keyPrefix;
    }

    @Override
    public List<String> findConversationIds() {
        Set<String> keys = stringRedisTemplate.keys(keyPrefix + "*");
        if (keys == null || keys.isEmpty()) {
            return Collections.emptyList();
        }
        return keys.stream()
                .map(key -> key.substring(keyPrefix.length()))
                .sorted()
                .collect(Collectors.toList());
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        String payload = stringRedisTemplate.opsForValue().get(key(conversationId));
        if (payload == null || payload.isBlank()) {
            return Collections.emptyList();
        }
        try {
            List<StoredChatMessage> storedMessages = objectMapper.readValue(payload, STORED_MESSAGE_LIST);
            return storedMessages.stream()
                    .map(this::toMessage)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("Failed to load chat memory for conversation {}", conversationId, e);
            stringRedisTemplate.delete(key(conversationId));
            return Collections.emptyList();
        }
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            deleteByConversationId(conversationId);
            return;
        }
        try {
            List<StoredChatMessage> storedMessages = messages.stream()
                    .map(message -> new StoredChatMessage(message.getMessageType().name(), message.getText()))
                    .collect(Collectors.toList());
            String payload = objectMapper.writeValueAsString(storedMessages);
            stringRedisTemplate.opsForValue().set(key(conversationId), payload, ttl);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to persist chat memory", e);
        }
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        stringRedisTemplate.delete(key(conversationId));
    }

    private Message toMessage(StoredChatMessage storedChatMessage) {
        MessageType messageType = MessageType.valueOf(storedChatMessage.type());
        return switch (messageType) {
            case USER -> new UserMessage(storedChatMessage.text());
            case SYSTEM -> new SystemMessage(storedChatMessage.text());
            case ASSISTANT, TOOL -> new AssistantMessage(storedChatMessage.text());
        };
    }

    private String key(String conversationId) {
        return keyPrefix + conversationId;
    }

    private record StoredChatMessage(String type, String text) {
    }
}
