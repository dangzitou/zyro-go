package com.hmdp.ai;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

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
    private boolean microCompactTriggered;
    private List<String> microCompactedItems;
    private List<String> selectedSummaryKinds;
    private List<String> selectedMemoryKinds;
    private List<String> droppedContextKinds;

    public static AssembledChatContext empty() {
        return new AssembledChatContext(
                "",
                0,
                0,
                0,
                0,
                0,
                false,
                false,
                new ArrayList<String>(),
                new ArrayList<String>(),
                new ArrayList<String>(),
                new ArrayList<String>()
        );
    }
}
