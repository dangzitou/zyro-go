package com.hmdp.config;

import lombok.Data;

@Data
public class AiBlogRagProperties {
    /**
     * Whether experience-oriented blog knowledge should participate in RAG.
     */
    private boolean enabled = true;

    /**
     * Only recent blogs within this window are embedded into the vector store.
     */
    private int recentDays = 180;

    /**
     * Skip low-signal blogs with too few likes.
     */
    private int minLiked = 10;

    /**
     * Maximum number of blogs loaded into the blog RAG source.
     */
    private int maxDocuments = 300;

    /**
     * Number of vector candidates eligible for blog rerank bonuses.
     */
    private int rerankCandidateLimit = 12;
}
