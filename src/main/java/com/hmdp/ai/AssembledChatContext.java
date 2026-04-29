package com.hmdp.ai;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AssembledChatContext {
    private String promptContext;
    private int recentTurnCount;
    private int summaryHitCount;
    private int longTermMemoryHitCount;
    private int estimatedTokensBefore;
    private int estimatedTokensAfter;
    private boolean summaryUpdated;

    public static AssembledChatContext empty() {
        return new AssembledChatContext("", 0, 0, 0, 0, 0, false);
    }
}
