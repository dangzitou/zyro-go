package com.hmdp.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentAuditRecord {
    private Instant timestamp;
    private String traceId;
    private Long userId;
    private String conversationId;
    private String message;
    private String answer;
    private String status;
    private String model;
    private String intent;
    private String retrievalQuery;
    private Integer retrievalCount;
    private Integer toolTraceCount;
    private Long latencyMs;
    private List<String> toolTrace;
}
