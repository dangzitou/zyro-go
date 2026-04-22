package com.hmdp.service;

import com.hmdp.dto.AgentExecutionPlan;

public interface IAgentPlanningService {
    AgentExecutionPlan plan(String message);
}
