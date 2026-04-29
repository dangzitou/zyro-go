package com.hmdp.ai;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConversationSummary {
    private String userGoal;
    private List<String> persistentPreferences = new ArrayList<String>();
    private List<String> hardConstraints = new ArrayList<String>();
    private List<String> negativePreferences = new ArrayList<String>();
    private List<String> resolvedFacts = new ArrayList<String>();
    private List<String> openThreads = new ArrayList<String>();
    private List<String> toolOutcomes = new ArrayList<String>();
    private List<String> ragTakeaways = new ArrayList<String>();
    private Instant updatedAt;

    public boolean hasContent() {
        return hasText(userGoal)
                || !persistentPreferences.isEmpty()
                || !hardConstraints.isEmpty()
                || !negativePreferences.isEmpty()
                || !resolvedFacts.isEmpty()
                || !openThreads.isEmpty()
                || !toolOutcomes.isEmpty()
                || !ragTakeaways.isEmpty();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
