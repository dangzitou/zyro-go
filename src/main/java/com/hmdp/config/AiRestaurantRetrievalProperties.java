package com.hmdp.config;

import lombok.Data;

@Data
public class AiRestaurantRetrievalProperties {

    /**
     * Whether semantic restaurant retrieval is enabled.
     */
    private Boolean enabled = true;

    /**
     * Rebuild semantic restaurant vector index on startup.
     */
    private Boolean rebuildOnStartup = true;

    /**
     * Store file for restaurant vector index.
     */
    private String storeFile = "data/ai/shop-vector-store.json";

    /**
     * Similarity threshold before rerank.
     */
    private Double similarityThreshold = 0.30D;

    /**
     * Candidate count from vector recall before rerank.
     */
    private Integer candidateLimit = 12;

    /**
     * Nearby hard filter in meters for "附近" type questions.
     */
    private Double nearbyDistanceLimitMeters = 50000D;
}
