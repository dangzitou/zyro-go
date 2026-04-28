package com.hmdp.config;

import lombok.Data;

@Data
public class AiRerankProperties {

    /**
     * Whether rerank is enabled for semantic restaurant retrieval.
     */
    private Boolean enabled = true;

    /**
     * DashScope compatible rerank endpoint.
     */
    private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1/rerank";

    /**
     * Optional dedicated API key. Falls back to embedding/chat api key.
     */
    private String apiKey;

    /**
     * Default rerank model.
     */
    private String model = "qwen3-rerank";

    /**
     * Number of documents kept after rerank.
     */
    private Integer topN = 5;
}
