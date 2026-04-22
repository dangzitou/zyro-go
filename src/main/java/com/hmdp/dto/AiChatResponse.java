package com.hmdp.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AiChatResponse {
    private String conversationId;
    private String answer;
    private List<String> toolTrace;
    private List<AiRetrievalHit> retrievalHits;
    private AgentExecutionPlan plan;
}
