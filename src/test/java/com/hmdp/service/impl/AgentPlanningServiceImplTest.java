package com.hmdp.service.impl;

import com.hmdp.config.AiProperties;
import com.hmdp.dto.AgentExecutionPlan;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentPlanningServiceImplTest {

    @Test
    void shouldFallbackToRecommendationPlanWhenPlannerDisabled() {
        AiProperties aiProperties = new AiProperties();
        aiProperties.setEnabled(false);

        AgentPlanningServiceImpl service = new AgentPlanningServiceImpl(
                aiProperties,
                new StaticListableBeanFactory().getBeanProvider(org.springframework.ai.chat.client.ChatClient.Builder.class)
        );

        AgentExecutionPlan plan = service.plan("推荐附近有券的火锅");

        assertEquals("recommendation", plan.getIntent());
        assertTrue(plan.getPreferredTools().contains("recommend_shops"));
    }
}
