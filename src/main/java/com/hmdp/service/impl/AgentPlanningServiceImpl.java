package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import com.hmdp.config.AiProperties;
import com.hmdp.dto.AgentExecutionPlan;
import com.hmdp.service.IAgentPlanningService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.AdvisorParams;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.StructuredOutputValidationAdvisor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

@Slf4j
@Service
public class AgentPlanningServiceImpl implements IAgentPlanningService {

    private static final String PLANNER_PROMPT = """
            你是本地生活 Agent 的规划器，不直接回答用户问题，只输出执行计划。
            你需要根据用户问题判断：
            1. 是否需要知识补充
            2. 是否应该允许工具调用
            3. 当前意图属于 recommendation / factual_lookup / social_discovery / general
            4. 最适合的 preferredTools
            5. 更适合检索的 retrievalQuery
            6. 这次回答更应该强调什么 reasoningFocus

            preferredTools 只能从下面这些工具里选：
            - search_shops
            - get_shop_detail
            - get_shop_coupons
            - get_hot_blogs
            - recommend_shops

            输出必须是结构化计划，不要输出解释文字。
            """;

    private final AiProperties aiProperties;
    private final ObjectProvider<ChatClient.Builder> chatClientBuilderProvider;

    public AgentPlanningServiceImpl(AiProperties aiProperties,
                                    ObjectProvider<ChatClient.Builder> chatClientBuilderProvider) {
        this.aiProperties = aiProperties;
        this.chatClientBuilderProvider = chatClientBuilderProvider;
    }

    @Override
    public AgentExecutionPlan plan(String message) {
        if (StrUtil.isBlank(message)) {
            return fallbackPlan("");
        }
        if (!aiProperties.isEnabled() || !Boolean.TRUE.equals(aiProperties.getPlannerEnabled())) {
            return fallbackPlan(message);
        }
        ChatClient.Builder builder = chatClientBuilderProvider.getIfAvailable();
        if (builder == null) {
            return fallbackPlan(message);
        }

        try {
            ChatClient.ChatClientRequestSpec requestSpec = builder.clone()
                    .build()
                    .prompt()
                    .system(PLANNER_PROMPT)
                    .user(message)
                    .advisors(AdvisorParams.ENABLE_NATIVE_STRUCTURED_OUTPUT);

            if (Boolean.TRUE.equals(aiProperties.getPlannerValidationEnabled())) {
                requestSpec = requestSpec.advisors(
                        StructuredOutputValidationAdvisor.builder()
                                .outputType(AgentExecutionPlan.class)
                                .maxRepeatAttempts(2)
                                .build()
                );
            }

            AgentExecutionPlan plan = requestSpec.call().entity(AgentExecutionPlan.class);
            return sanitize(plan, message);
        } catch (Exception e) {
            log.warn("Planning failed, fallback to heuristic plan", e);
            return fallbackPlan(message);
        }
    }

    private AgentExecutionPlan sanitize(AgentExecutionPlan plan, String message) {
        if (plan == null) {
            return fallbackPlan(message);
        }
        if (StrUtil.isBlank(plan.getIntent())) {
            plan.setIntent("general");
        }
        if (plan.getUseKnowledge() == null) {
            plan.setUseKnowledge(Boolean.TRUE);
        }
        if (plan.getUseTools() == null) {
            plan.setUseTools(Boolean.TRUE);
        }
        if (StrUtil.isBlank(plan.getRetrievalQuery())) {
            plan.setRetrievalQuery(message);
        }
        if (StrUtil.isBlank(plan.getResponseStyle())) {
            plan.setResponseStyle("concise");
        }
        if (plan.getPreferredTools() == null) {
            plan.setPreferredTools(List.of());
        }
        return plan;
    }

    private AgentExecutionPlan fallbackPlan(String message) {
        String normalized = message.toLowerCase(Locale.ROOT);
        AgentExecutionPlan plan = new AgentExecutionPlan();
        plan.setRetrievalQuery(message);
        plan.setResponseStyle("concise");
        plan.setReasoningFocus("grounded_facts");

        if (containsAny(normalized, "推荐", "附近", "适合", "吃什么", "去哪")) {
            plan.setIntent("recommendation");
            plan.setPreferredTools(List.of("recommend_shops", "get_shop_coupons", "get_shop_detail"));
            plan.setReasoningFocus("compare_candidates");
            return plan;
        }
        if (containsAny(normalized, "优惠", "券", "折扣", "便宜")) {
            plan.setIntent("factual_lookup");
            plan.setPreferredTools(List.of("search_shops", "get_shop_coupons", "get_shop_detail"));
            plan.setReasoningFocus("coupon_comparison");
            return plan;
        }
        if (containsAny(normalized, "热门", "笔记", "博客", "探店")) {
            plan.setIntent("social_discovery");
            plan.setPreferredTools(List.of("get_hot_blogs", "search_shops"));
            plan.setReasoningFocus("social_proof");
            return plan;
        }
        plan.setIntent("general");
        plan.setPreferredTools(List.of("search_shops", "get_shop_detail"));
        return plan;
    }

    private boolean containsAny(String source, String... candidates) {
        for (String candidate : candidates) {
            if (source.contains(candidate)) {
                return true;
            }
        }
        return false;
    }
}
