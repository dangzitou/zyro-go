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
    private List<String> preferredTools = new ArrayList<>();
}
