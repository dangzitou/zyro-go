package com.hmdp.ai.embedding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.config.AiEmbeddingProperties;
import org.springframework.ai.embedding.EmbeddingModel;

/**
 * Embedding provider SPI.
 * Main config only depends on this interface, so provider implementations remain hot-pluggable.
 */
public interface EmbeddingProviderFactory {

    /**
     * Stable provider name, for example: openai / huggingface / local-hash.
     */
    String provider();

    /**
     * Build one EmbeddingModel instance from current config.
     */
    EmbeddingModel create(AiEmbeddingProperties properties, ObjectMapper objectMapper);

    /**
     * Whether this provider can participate in auto selection.
     */
    default boolean supportsAuto() {
        return true;
    }

    /**
     * Whether this provider is the last-resort fallback.
     */
    default boolean isFallback() {
        return false;
    }

    /**
     * Lightweight liveness probe used by auto selection and explicit startup checks.
     */
    default void probe(EmbeddingModel embeddingModel) {
        embeddingModel.embed("embedding health check");
    }
}
