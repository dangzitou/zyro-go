package com.hmdp.config;

import lombok.Data;

@Data
public class AiEmbeddingProperties {

    /**
     * auto | openai | huggingface | local-hash
     */
    private String provider = "auto";

    /**
     * Optional dedicated embedding gateway. Falls back to the chat gateway when blank.
     */
    private String baseUrl;

    /**
     * Optional dedicated API key. Falls back to the chat API key when blank.
     */
    private String apiKey;

    /**
     * Optional dedicated embedding model id.
     */
    private String model = "text-embedding-3-small";

    /**
     * Dimensions used by the local deterministic fallback embedding model.
     */
    private Integer localDimensions = 256;
}
