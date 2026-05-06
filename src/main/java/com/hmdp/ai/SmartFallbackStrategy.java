package com.hmdp.ai;

import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.AgentExecutionPlan;
import com.hmdp.dto.ShopRecommendationDTO;
import com.hmdp.dto.ShopRecommendationQuery;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 智能降级策略
 * 当推荐结果为空时，自动放宽条件重试，并提供友好的用户反馈
 */
@Component
public class SmartFallbackStrategy {

    /**
     * 降级策略结果
     */
    public static class FallbackResult {
        private final List<ShopRecommendationDTO> recommendations;
        private final String userMessage;
        private final List<String> relaxedConditions;
        private final boolean fallbackTriggered;

        public FallbackResult(List<ShopRecommendationDTO> recommendations,
                            String userMessage,
                            List<String> relaxedConditions,
                            boolean fallbackTriggered) {
            this.recommendations = recommendations;
            this.userMessage = userMessage;
            this.relaxedConditions = relaxedConditions;
            this.fallbackTriggered = fallbackTriggered;
        }

        public List<ShopRecommendationDTO> getRecommendations() {
            return recommendations;
        }

        public String getUserMessage() {
            return userMessage;
        }

        public List<String> getRelaxedConditions() {
            return relaxedConditions;
        }

        public boolean isFallbackTriggered() {
            return fallbackTriggered;
        }
    }

    /**
     * 执行降级策略
     * @param originalQuery 原始查询
     * @param originalResults 原始结果
     * @param plan 执行计划
     * @param recommendationFunction 推荐函数
     * @return 降级结果
     */
    public FallbackResult executeFallback(ShopRecommendationQuery originalQuery,
                                         List<ShopRecommendationDTO> originalResults,
                                         AgentExecutionPlan plan,
                                         java.util.function.Function<ShopRecommendationQuery, List<ShopRecommendationDTO>> recommendationFunction) {
        // 如果原始结果不为空，直接返回
        if (originalResults != null && !originalResults.isEmpty()) {
            return new FallbackResult(originalResults, null, null, false);
        }

        List<String> relaxedConditions = new ArrayList<>();
        ShopRecommendationQuery fallbackQuery = cloneQuery(originalQuery);

        // 策略 1: 放宽优惠券限制
        if (Boolean.TRUE.equals(fallbackQuery.getCouponOnly())) {
            fallbackQuery.setCouponOnly(false);
            relaxedConditions.add("优惠券限制");
            List<ShopRecommendationDTO> results = recommendationFunction.apply(fallbackQuery);
            if (!results.isEmpty()) {
                return new FallbackResult(
                        results,
                        buildFallbackMessage(relaxedConditions, "放宽了优惠券限制，以下是附近的推荐："),
                        relaxedConditions,
                        true
                );
            }
        }

        // 策略 2: 放宽预算限制（增加 30%）
        if (fallbackQuery.getMaxBudget() != null && fallbackQuery.getMaxBudget() > 0) {
            Long originalBudget = fallbackQuery.getMaxBudget();
            fallbackQuery.setMaxBudget((long) (originalBudget * 1.3));
            relaxedConditions.add("预算限制");
            List<ShopRecommendationDTO> results = recommendationFunction.apply(fallbackQuery);
            if (!results.isEmpty()) {
                return new FallbackResult(
                        results,
                        buildFallbackMessage(relaxedConditions, 
                            String.format("在你的预算 %d 元附近没找到合适的，稍微放宽到 %d 元，以下是推荐：", 
                                originalBudget, fallbackQuery.getMaxBudget())),
                        relaxedConditions,
                        true
                );
            }
        }

        // 策略 3: 放宽距离限制（扩大到 10 公里）
        if (fallbackQuery.getMaxDistanceMeters() != null && fallbackQuery.getMaxDistanceMeters() < 10000D) {
            fallbackQuery.setMaxDistanceMeters(10000D);
            relaxedConditions.add("距离限制");
            List<ShopRecommendationDTO> results = recommendationFunction.apply(fallbackQuery);
            if (!results.isEmpty()) {
                return new FallbackResult(
                        results,
                        buildFallbackMessage(relaxedConditions, "附近没找到合适的，扩大了搜索范围，以下是推荐："),
                        relaxedConditions,
                        true
                );
            }
        }

        // 策略 4: 移除细分类限制
        if (StrUtil.isNotBlank(fallbackQuery.getSubcategory())) {
            fallbackQuery.setSubcategory(null);
            relaxedConditions.add("细分类限制");
            List<ShopRecommendationDTO> results = recommendationFunction.apply(fallbackQuery);
            if (!results.isEmpty()) {
                return new FallbackResult(
                        results,
                        buildFallbackMessage(relaxedConditions, "放宽了品类限制，以下是相关推荐："),
                        relaxedConditions,
                        true
                );
            }
        }

        // 策略 5: 移除排除品类限制
        if (fallbackQuery.getExcludedCategories() != null && !fallbackQuery.getExcludedCategories().isEmpty()) {
            List<String> excludedCategories = new ArrayList<>(fallbackQuery.getExcludedCategories());
            fallbackQuery.setExcludedCategories(null);
            relaxedConditions.add("排除品类限制");
            List<ShopRecommendationDTO> results = recommendationFunction.apply(fallbackQuery);
            if (!results.isEmpty()) {
                return new FallbackResult(
                        results,
                        buildFallbackMessage(relaxedConditions, 
                            String.format("移除了「不要%s」的限制，以下是推荐：", String.join("、", excludedCategories))),
                        relaxedConditions,
                        true
                );
            }
        }

        // 策略 6: 只保留城市和类型，移除所有其他限制
        if (fallbackQuery.getKeyword() != null || fallbackQuery.getMaxBudget() != null 
            || fallbackQuery.getLocationHint() != null) {
            ShopRecommendationQuery minimalQuery = new ShopRecommendationQuery();
            minimalQuery.setTypeId(fallbackQuery.getTypeId());
            minimalQuery.setCity(fallbackQuery.getCity());
            minimalQuery.setX(fallbackQuery.getX());
            minimalQuery.setY(fallbackQuery.getY());
            minimalQuery.setLimit(fallbackQuery.getLimit());
            relaxedConditions.add("所有限制条件");
            List<ShopRecommendationDTO> results = recommendationFunction.apply(minimalQuery);
            if (!results.isEmpty()) {
                return new FallbackResult(
                        results,
                        buildFallbackMessage(relaxedConditions, "放宽了所有限制条件，以下是通用推荐："),
                        relaxedConditions,
                        true
                );
            }
        }

        // 所有降级策略都失败，返回友好的空结果提示
        return new FallbackResult(
                new ArrayList<>(),
                buildEmptyResultMessage(originalQuery, plan),
                relaxedConditions,
                true
        );
    }

    private ShopRecommendationQuery cloneQuery(ShopRecommendationQuery original) {
        ShopRecommendationQuery clone = new ShopRecommendationQuery();
        clone.setKeyword(original.getKeyword());
        clone.setTypeId(original.getTypeId());
        clone.setMaxBudget(original.getMaxBudget());
        clone.setCity(original.getCity());
        clone.setLocationHint(original.getLocationHint());
        clone.setX(original.getX());
        clone.setY(original.getY());
        clone.setMaxDistanceMeters(original.getMaxDistanceMeters());
        clone.setCouponOnly(original.getCouponOnly());
        clone.setLimit(original.getLimit());
        clone.setSubcategory(original.getSubcategory());
        clone.setQualityPreference(original.getQualityPreference());
        clone.setPartySize(original.getPartySize());
        if (original.getExcludedCategories() != null) {
            clone.setExcludedCategories(new ArrayList<>(original.getExcludedCategories()));
        }
        if (original.getNegativePreferences() != null) {
            clone.setNegativePreferences(new ArrayList<>(original.getNegativePreferences()));
        }
        return clone;
    }

    private String buildFallbackMessage(List<String> relaxedConditions, String prefix) {
        if (relaxedConditions.isEmpty()) {
            return prefix;
        }
        return prefix;
    }

    private String buildEmptyResultMessage(ShopRecommendationQuery query, AgentExecutionPlan plan) {
        StringBuilder message = new StringBuilder("抱歉，");

        // 构建查询条件描述
        List<String> conditions = new ArrayList<>();
        if (StrUtil.isNotBlank(query.getCity())) {
            conditions.add(query.getCity());
        }
        if (StrUtil.isNotBlank(query.getLocationHint())) {
            conditions.add(query.getLocationHint() + "附近");
        }
        if (StrUtil.isNotBlank(query.getKeyword())) {
            conditions.add(query.getKeyword());
        }
        if (query.getMaxBudget() != null) {
            conditions.add("人均" + query.getMaxBudget() + "元以内");
        }
        if (Boolean.TRUE.equals(query.getCouponOnly())) {
            conditions.add("有优惠券");
        }

        if (!conditions.isEmpty()) {
            message.append("暂时没有找到符合「").append(String.join("、", conditions)).append("」条件的店铺。");
        } else {
            message.append("暂时没有找到合适的店铺。");
        }

        // 提供调整建议
        message.append("\n\n你可以尝试：");
        List<String> suggestions = new ArrayList<>();
        if (query.getMaxBudget() != null) {
            suggestions.add("放宽预算限制");
        }
        if (Boolean.TRUE.equals(query.getCouponOnly())) {
            suggestions.add("去掉优惠券限制");
        }
        if (StrUtil.isNotBlank(query.getSubcategory())) {
            suggestions.add("换一个品类");
        }
        if (query.getExcludedCategories() != null && !query.getExcludedCategories().isEmpty()) {
            suggestions.add("减少排除条件");
        }
        if (query.getMaxDistanceMeters() != null && query.getMaxDistanceMeters() < 5000D) {
            suggestions.add("扩大搜索范围");
        }

        if (!suggestions.isEmpty()) {
            for (int i = 0; i < suggestions.size(); i++) {
                message.append("\n").append(i + 1).append(". ").append(suggestions.get(i));
            }
        } else {
            message.append("\n1. 换一个地点或品类\n2. 放宽筛选条件");
        }

        return message.toString();
    }
}
