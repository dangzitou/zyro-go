package com.hmdp.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentExecutionPlan {
    private String intent = "general";
    private Boolean useKnowledge = Boolean.TRUE;
    private Boolean useTools = Boolean.TRUE;
    private String retrievalQuery;
    private String responseStyle = "concise";
    private String reasoningFocus;
    /**
     * Structured query understanding fields.
     */
    private String city;
    private String locationHint;
    private Boolean nearby;
    private String category;
    private String subcategory;
    private String pricePreference;
    private String qualityPreference;
    private Integer partySize;
    private Long budgetMax;
    private List<String> excludedCategories = new ArrayList<>();
    private List<String> negativePreferences = new ArrayList<>();
    private List<String> preferredTools = new ArrayList<>();

    public AgentExecutionPlan(String intent,
                              Boolean useKnowledge,
                              Boolean useTools,
                              String retrievalQuery,
                              String responseStyle,
                              String reasoningFocus,
                              List<String> preferredTools) {
        this.intent = intent;
        this.useKnowledge = useKnowledge;
        this.useTools = useTools;
        this.retrievalQuery = retrievalQuery;
        this.responseStyle = responseStyle;
        this.reasoningFocus = reasoningFocus;
        this.preferredTools = preferredTools;
    }
}
