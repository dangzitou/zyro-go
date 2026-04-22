package com.hmdp.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.config.AiProperties;
import com.hmdp.dto.AgentAuditRecord;
import com.hmdp.service.IAgentAuditService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.connection.RedisStreamCommands;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;

@Slf4j
@Service
public class AgentAuditServiceImpl implements IAgentAuditService {

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final AiProperties aiProperties;
    private final Executor agentAuditExecutor;

    public AgentAuditServiceImpl(StringRedisTemplate stringRedisTemplate,
                                 ObjectMapper objectMapper,
                                 AiProperties aiProperties,
                                 @Qualifier("agentAuditExecutor") Executor agentAuditExecutor) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
        this.aiProperties = aiProperties;
        this.agentAuditExecutor = agentAuditExecutor;
    }

    @Override
    public void record(AgentAuditRecord auditRecord) {
        if (!aiProperties.getAudit().isEnabled() || auditRecord == null) {
            return;
        }
        agentAuditExecutor.execute(() -> {
            try {
                Map<String, String> payload = new LinkedHashMap<>();
                payload.put("timestamp", String.valueOf(auditRecord.getTimestamp()));
                payload.put("traceId", safe(auditRecord.getTraceId()));
                payload.put("userId", String.valueOf(auditRecord.getUserId()));
                payload.put("conversationId", safe(auditRecord.getConversationId()));
                payload.put("message", safe(auditRecord.getMessage()));
                payload.put("status", safe(auditRecord.getStatus()));
                payload.put("model", safe(auditRecord.getModel()));
                payload.put("intent", safe(auditRecord.getIntent()));
                payload.put("retrievalQuery", safe(auditRecord.getRetrievalQuery()));
                payload.put("retrievalCount", String.valueOf(auditRecord.getRetrievalCount()));
                payload.put("toolTraceCount", String.valueOf(auditRecord.getToolTraceCount()));
                payload.put("latencyMs", String.valueOf(auditRecord.getLatencyMs()));
                payload.put("toolTrace", objectMapper.writeValueAsString(auditRecord.getToolTrace()));
                if (aiProperties.getAudit().isIncludeAnswer()) {
                    payload.put("answer", safe(auditRecord.getAnswer()));
                }

                String streamKey = aiProperties.getAudit().getStreamKey();
                stringRedisTemplate.opsForStream().add(
                        StreamRecords.newRecord()
                                .in(streamKey)
                                .ofMap(payload),
                        RedisStreamCommands.XAddOptions.maxlen(aiProperties.getAudit().getMaxLen()).approximateTrimming(true)
                );
            } catch (Exception e) {
                log.warn("Failed to record agent audit", e);
            }
        });
    }

    private String safe(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
