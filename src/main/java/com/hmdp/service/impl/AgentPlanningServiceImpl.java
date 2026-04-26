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
            You are the planner for Zyro's local-life agent.
            Do not answer the user directly.
            Only output one structured execution plan object that matches the target schema exactly.

            The plan must contain these fields only:
            - intent
            - useKnowledge
            - useTools
            - retrievalQuery
            - responseStyle
            - reasoningFocus
            - preferredTools

            Rules:
            1. intent must be one of: recommendation, factual_lookup, social_discovery, general
            2. useKnowledge decides whether background knowledge retrieval is needed
            3. useTools decides whether business tools are needed
            4. retrievalQuery should be a short searchable query in Chinese
            5. responseStyle should usually be concise
            6. reasoningFocus should describe the main reasoning objective briefly
            7. preferredTools can only contain:
               - search_shops
               - get_shop_detail
               - get_shop_coupons
               - get_hot_blogs
               - recommend_shops
            8. Do not include any extra fields
            9. Dynamic business facts such as shop details, prices, coupons, ratings and opening hours should prefer tools

            Output only the structured plan object.
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
        AgentExecutionPlan heuristic = fallbackPlan(message);

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
            plan.setRetrievalQuery(heuristic.getRetrievalQuery());
        }
        if (StrUtil.isBlank(plan.getResponseStyle())) {
            plan.setResponseStyle("concise");
        }
        if (StrUtil.isBlank(plan.getReasoningFocus())) {
            plan.setReasoningFocus(heuristic.getReasoningFocus());
        }
        if (plan.getPreferredTools() == null) {
            plan.setPreferredTools(List.of());
        }
        if ((plan.getPreferredTools().isEmpty() || !Boolean.TRUE.equals(plan.getUseTools()))
                && heuristic.getPreferredTools() != null
                && !heuristic.getPreferredTools().isEmpty()) {
            plan.setIntent(heuristic.getIntent());
            plan.setUseTools(Boolean.TRUE);
            plan.setPreferredTools(heuristic.getPreferredTools());
            plan.setReasoningFocus(heuristic.getReasoningFocus());
        }
        return plan;
    }

    private AgentExecutionPlan fallbackPlan(String message) {
        String normalized = StrUtil.blankToDefault(message, "").toLowerCase(Locale.ROOT);
        AgentExecutionPlan plan = new AgentExecutionPlan();
        plan.setRetrievalQuery(extractRetrievalQuery(message));
        plan.setResponseStyle("concise");
        plan.setReasoningFocus("grounded_facts");
        plan.setUseKnowledge(Boolean.TRUE);
        plan.setUseTools(Boolean.TRUE);

        if (containsAny(normalized, "推荐", "附近", "适合", "吃什么", "去哪里", "recommend", "nearby")) {
            plan.setIntent("recommendation");
            plan.setPreferredTools(List.of("recommend_shops", "get_shop_coupons", "get_shop_detail"));
            plan.setReasoningFocus("compare_candidates");
            return plan;
        }
        if (containsAny(normalized, "优惠", "券", "折扣", "便宜", "discount", "coupon", "营业时间", "评分", "地址")) {
            plan.setIntent("factual_lookup");
            plan.setPreferredTools(List.of("search_shops", "get_shop_coupons", "get_shop_detail"));
            plan.setReasoningFocus("grounded_lookup");
            return plan;
        }
        if (containsAny(normalized, "热门", "笔记", "博客", "探店", "blog", "review")) {
            plan.setIntent("social_discovery");
            plan.setPreferredTools(List.of("get_hot_blogs"));
            plan.setReasoningFocus("social_proof");
            return plan;
        }

        plan.setIntent("general");
        plan.setPreferredTools(List.of("search_shops", "get_shop_detail"));
        plan.setReasoningFocus("grounded_facts");
        return plan;
    }

    private String extractRetrievalQuery(String message) {
        if (StrUtil.isBlank(message)) {
            return "";
        }
        return message.replace("请问", "")
                .replace("帮我", "")
                .replace("你好", "")
                .replace("请", "")
                .trim();
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
