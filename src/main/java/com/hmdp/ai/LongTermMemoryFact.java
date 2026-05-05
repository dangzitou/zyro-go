package com.hmdp.ai;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LongTermMemoryFact {
    private Long userId;
    private String memoryClass;
    private String type;
    private String content;
    private Double confidence;
    private String sourceConversationId;
    private Instant lastUpdatedAt;
    private Instant expiresAt;
    private Boolean stale;

    public LongTermMemoryFact(Long userId,
                              String memoryClass,
                              String type,
                              String content,
                              Double confidence,
                              String sourceConversationId,
                              Instant lastUpdatedAt,
                              Instant expiresAt) {
        this.userId = userId;
        this.memoryClass = memoryClass;
        this.type = type;
        this.content = content;
        this.confidence = confidence;
        this.sourceConversationId = sourceConversationId;
        this.lastUpdatedAt = lastUpdatedAt;
        this.expiresAt = expiresAt;
        this.stale = Boolean.FALSE;
    }

    public String toRetrievableText() {
        StringBuilder builder = new StringBuilder();
        if (memoryClass != null && !memoryClass.isBlank()) {
            builder.append(memoryClass).append(" | ");
        }
        builder.append(type).append(": ").append(content);
        return builder.toString();
    }

    public boolean isExpired(Instant now) {
        return expiresAt != null && now != null && expiresAt.isBefore(now);
    }
}
