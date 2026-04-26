package com.hmdp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.embedding.LocalHashEmbeddingModel;
import com.hmdp.ai.LocalLifeAgentTools;
import com.hmdp.ai.RedisChatMemoryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.StringUtils;

import java.time.Duration;

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

    @Bean
    @Primary
    public EmbeddingModel embeddingModel(AiProperties aiProperties) {
        AiEmbeddingProperties properties = aiProperties.getEmbedding();
        LocalHashEmbeddingModel localFallback = new LocalHashEmbeddingModel(properties.getLocalDimensions());
        String provider = properties.getProvider() == null ? "auto" : properties.getProvider().trim().toLowerCase();

        if ("local-hash".equals(provider)) {
            log.info("Using local-hash embedding model with {} dimensions.", localFallback.dimensions());
            return localFallback;
        }

        OpenAiEmbeddingModel openAiEmbeddingModel = buildOpenAiEmbeddingModel(properties);
        if ("openai".equals(provider)) {
            log.info("Using OpenAI-compatible embedding model {}.", properties.getModel());
            return openAiEmbeddingModel;
        }

        try {
            openAiEmbeddingModel.embed("embedding health check");
            log.info("Embedding gateway probe succeeded, using remote embedding model {}.", properties.getModel());
            return openAiEmbeddingModel;
        } catch (Exception e) {
            log.warn("Embedding gateway probe failed, fallback to local-hash embeddings. provider={}, model={}, message={}",
                    provider, properties.getModel(), e.getMessage());
            return localFallback;
        }
    }

    private OpenAiEmbeddingModel buildOpenAiEmbeddingModel(AiEmbeddingProperties properties) {
        String baseUrl = firstNonBlank(
                properties.getBaseUrl(),
                System.getenv("AI_EMBEDDING_BASE_URL"),
                System.getenv("AI_BASE_URL"),
                "https://api.openai.com"
        );
        String apiKey = firstNonBlank(
                properties.getApiKey(),
                System.getenv("AI_EMBEDDING_API_KEY"),
                System.getenv("AI_API_KEY"),
                ""
        );
        String model = firstNonBlank(
                properties.getModel(),
                System.getenv("AI_EMBEDDING_MODEL"),
                "text-embedding-3-small"
        );

        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .build();

        OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
                .model(model)
                .build();
        return new OpenAiEmbeddingModel(api, MetadataMode.NONE, options);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }
}
