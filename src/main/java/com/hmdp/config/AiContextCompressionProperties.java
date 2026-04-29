package com.hmdp.config;

import lombok.Data;

@Data
public class AiContextCompressionProperties {

    /**
     * Master switch for layered context compression.
     */
    private Boolean enabled = true;

    /**
     * Number of recent user/assistant pairs retained as raw transcript.
     */
    private Integer recentTurnPairs = 4;

    /**
     * Soft token budget. Crossing this limit triggers summary compaction.
     */
    private Integer softTokenBudget = 5000;

    /**
     * Hard token budget for assembled context.
     */
    private Integer hardTokenBudget = 6500;

    /**
     * Message count threshold that triggers summary generation.
     */
    private Integer summaryTriggerMessageCount = 18;

    /**
     * Target token budget for summary payload itself.
     */
    private Integer summaryMaxTokens = 1200;

    /**
     * Whether stable user memory facts are enabled.
     */
    private Boolean longTermMemoryEnabled = true;

    /**
     * Number of memory facts injected for each request.
     */
    private Integer memoryTopK = 6;

    /**
     * Optional future override for dedicated summary model.
     */
    private String summaryModel;

    /**
     * Optional future override for dedicated compression model.
     */
    private String compressionModel;

    /**
     * Minimum confidence required for long-term memory persistence.
     */
    private Double factMinConfidence = 0.72D;
}
