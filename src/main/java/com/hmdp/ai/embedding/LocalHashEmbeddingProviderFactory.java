package com.hmdp.ai.embedding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.config.AiEmbeddingProperties;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Deterministic local fallback provider.
 */
@Component
@Order(1000)
public class LocalHashEmbeddingProviderFactory implements EmbeddingProviderFactory {

    @Override
    public String provider() {
        return "local-hash";
    }

    @Override
    public EmbeddingModel create(AiEmbeddingProperties properties, ObjectMapper objectMapper) {
        return new LocalHashEmbeddingModel(properties.getLocalDimensions());
    }

    @Override
    public boolean supportsAuto() {
        return false;
    }

    @Override
    public boolean isFallback() {
        return true;
    }

    @Override
    public void probe(EmbeddingModel embeddingModel) {
        // Local fallback is always available, no outbound probe needed.
    }
}
