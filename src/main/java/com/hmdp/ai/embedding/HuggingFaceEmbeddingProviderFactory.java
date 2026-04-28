package com.hmdp.ai.embedding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.config.AiEmbeddingProperties;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Hugging Face Inference provider factory.
 * Registered through SPI instead of hardcoded in main config.
 */
@Component
@Order(200)
public class HuggingFaceEmbeddingProviderFactory implements EmbeddingProviderFactory {

    @Override
    public String provider() {
        return "huggingface";
    }

    @Override
    public EmbeddingModel create(AiEmbeddingProperties properties, ObjectMapper objectMapper) {
        String baseUrl = firstNonBlank(
                properties.getBaseUrl(),
                System.getenv("AI_EMBEDDING_BASE_URL"),
                "https://router.huggingface.co/hf-inference/models"
        );
        String apiKey = firstNonBlank(
                properties.getApiKey(),
                System.getenv("AI_EMBEDDING_API_KEY"),
                System.getenv("HF_TOKEN"),
                ""
        );
        String model = firstNonBlank(
                properties.getModel(),
                System.getenv("AI_EMBEDDING_MODEL"),
                "BAAI/bge-base-zh-v1.5"
        );
        return new HuggingFaceEmbeddingModel(baseUrl, apiKey, model, objectMapper);
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
