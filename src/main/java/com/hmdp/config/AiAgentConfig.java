package com.hmdp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.LocalLifeAgentTools;
import com.hmdp.ai.RedisChatMemoryRepository;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

@Configuration
public class AiAgentConfig {

    @Bean
    public ChatMemoryRepository chatMemoryRepository(StringRedisTemplate stringRedisTemplate,
                                                     ObjectMapper objectMapper,
                                                     AiProperties aiProperties) {
        int ttlHours = Math.max(1, aiProperties.getMemoryTtlHours());
        return new RedisChatMemoryRepository(
                stringRedisTemplate,
                objectMapper,
                Duration.ofHours(ttlHours),
                "ai:agent:memory:"
        );
    }

    @Bean
    public ChatMemory chatMemory(ChatMemoryRepository chatMemoryRepository, AiProperties aiProperties) {
        int maxMessages = Math.max(4, aiProperties.getMemoryTurns() * 2);
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(maxMessages)
                .build();
    }

    @Bean
    public ToolCallbackProvider toolCallbackProvider(LocalLifeAgentTools localLifeAgentTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(localLifeAgentTools)
                .build();
    }
}
