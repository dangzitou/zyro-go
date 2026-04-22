package com.hmdp.service;

import com.hmdp.dto.AgentAuditRecord;

public interface IAgentAuditService {
    void record(AgentAuditRecord auditRecord);
}
