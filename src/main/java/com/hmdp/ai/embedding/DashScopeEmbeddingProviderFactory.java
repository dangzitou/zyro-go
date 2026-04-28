package com.hmdp.ai.embedding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.config.AiEmbeddingProperties;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@Order(50)
public class DashScopeEmbeddingProviderFactory implements EmbeddingProviderFactory {

    @Override
    public String provider() {
        return "dashscope";
    }

    @Override
    public EmbeddingModel create(AiEmbeddingProperties properties, ObjectMapper objectMapper) {
        String endpoint = firstNonBlank(
                properties.getBaseUrl(),
                System.getenv("AI_EMBEDDING_BASE_URL"),
                "https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings"
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
                "text-embedding-v3"
        );
        return new DashScopeEmbeddingModel(endpoint, apiKey, model, 1024, objectMapper);
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
