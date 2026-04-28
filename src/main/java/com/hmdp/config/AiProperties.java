package com.hmdp.config;

import lombok.Data;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "hmdp.ai")
public class AiProperties {
    private boolean enabled = false;
    private Integer memoryTurns = 6;
    private Integer memoryTtlHours = 12;
    private Boolean knowledgeEnabled = true;
    private Integer knowledgeTopK = 4;
    private String defaultConversationPrefix = "agent-session";
    private Boolean ragEnabled = true;
    private Boolean ragRebuildOnStartup = false;
    private String ragStoreFile = "data/ai/hmdp-vector-store.json";
    private Double ragSimilarityThreshold = 0.62D;
    private Boolean plannerEnabled = true;
    private Boolean plannerValidationEnabled = true;
    private Boolean recursiveToolLoopEnabled = true;
    @NestedConfigurationProperty
    private AiBlogRagProperties blogRag = new AiBlogRagProperties();
    @NestedConfigurationProperty
    private AiEmbeddingProperties embedding = new AiEmbeddingProperties();
    @NestedConfigurationProperty
    private AiRerankProperties rerank = new AiRerankProperties();
    @NestedConfigurationProperty
    private AiRestaurantRetrievalProperties restaurantRetrieval = new AiRestaurantRetrievalProperties();
    @NestedConfigurationProperty
    private BaiduMapProperties baiduMap = new BaiduMapProperties();
    @NestedConfigurationProperty
    private AiRateLimitProperties rateLimit = new AiRateLimitProperties();
    @NestedConfigurationProperty
    private AiAuditProperties audit = new AiAuditProperties();
}
