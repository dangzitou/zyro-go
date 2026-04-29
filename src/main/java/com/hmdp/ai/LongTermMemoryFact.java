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
    private String type;
    private String content;
    private Double confidence;
    private String sourceConversationId;
    private Instant lastUpdatedAt;
    private Instant expiresAt;

    public String toRetrievableText() {
        return type + ": " + content;
    }
}
