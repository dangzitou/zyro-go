package com.hmdp.ai.embedding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.config.AiEmbeddingProperties;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * OpenAI-compatible provider factory.
 */
@Component
@Order(100)
public class OpenAiEmbeddingProviderFactory implements EmbeddingProviderFactory {

    @Override
    public String provider() {
        return "openai";
    }

    @Override
    public EmbeddingModel create(AiEmbeddingProperties properties, ObjectMapper objectMapper) {
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
