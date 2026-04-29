package com.hmdp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.ChatContextRepository;
import com.hmdp.ai.embedding.EmbeddingProviderFactory;
import com.hmdp.ai.LocalLifeAgentTools;
import com.hmdp.ai.RedisChatContextRepository;
import com.hmdp.ai.RedisChatMemoryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;

@Slf4j
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
        if (Boolean.TRUE.equals(aiProperties.getContextCompression().getEnabled())) {
            int recent = Math.max(2, aiProperties.getContextCompression().getRecentTurnPairs() * 2);
            int trigger = Math.max(recent, aiProperties.getContextCompression().getSummaryTriggerMessageCount());
            maxMessages = Math.max(maxMessages, recent + trigger + 2);
        }
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(maxMessages)
                .build();
    }

    @Bean
    public ChatContextRepository chatContextRepository(StringRedisTemplate stringRedisTemplate,
                                                       ObjectMapper objectMapper) {
        return new RedisChatContextRepository(
                stringRedisTemplate,
                objectMapper,
                "ai:agent:summary:",
                "ai:agent:long-memory:"
        );
    }

    @Bean
    public ToolCallbackProvider toolCallbackProvider(LocalLifeAgentTools localLifeAgentTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(localLifeAgentTools)
                .build();
    }

    @Bean
    @Primary
    public EmbeddingModel embeddingModel(AiProperties aiProperties,
                                         ObjectMapper objectMapper,
                                         List<EmbeddingProviderFactory> providerFactories) {
        AiEmbeddingProperties properties = aiProperties.getEmbedding();
        String provider = properties.getProvider() == null ? "auto" : properties.getProvider().trim().toLowerCase();

        EmbeddingProviderFactory fallbackFactory = providerFactories.stream()
                .filter(EmbeddingProviderFactory::isFallback)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No fallback embedding provider registered."));

        if (!"auto".equals(provider)) {
            EmbeddingProviderFactory factory = providerFactories.stream()
                    .filter(candidate -> candidate.provider().equalsIgnoreCase(provider))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown embedding provider: " + provider));
            EmbeddingModel embeddingModel = factory.create(properties, objectMapper);
            factory.probe(embeddingModel);
            log.info("Using embedding provider {} with model {}.", factory.provider(), properties.getModel());
            return embeddingModel;
        }

        for (EmbeddingProviderFactory factory : providerFactories) {
            if (!factory.supportsAuto() || factory.isFallback()) {
                continue;
            }
            try {
                EmbeddingModel embeddingModel = factory.create(properties, objectMapper);
                factory.probe(embeddingModel);
                log.info("Embedding provider probe succeeded, using provider {} with model {}.",
                        factory.provider(), properties.getModel());
                return embeddingModel;
            } catch (Exception e) {
                log.warn("Embedding provider probe failed, provider={}, model={}, message={}",
                        factory.provider(), properties.getModel(), e.getMessage());
            }
        }

        EmbeddingModel fallbackModel = fallbackFactory.create(properties, objectMapper);
        log.warn("All remote embedding providers failed, fallback to provider {}.", fallbackFactory.provider());
        return fallbackModel;
    }
}
