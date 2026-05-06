package com.hmdp.ai;

import cn.hutool.core.util.StrUtil;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 否定偏好处理器
 * 负责识别和匹配用户的否定偏好，如"不要太辣"、"别太吵"、"不要太贵"等
 */
@Component
public class NegativePreferenceHandler {

    /**
     * 否定偏好规则定义
     */
    private static final Map<String, NegativePreferenceRule> PREFERENCE_RULES = new LinkedHashMap<>();

    static {
        // 口味相关
        PREFERENCE_RULES.put("太辣", new NegativePreferenceRule(
                Arrays.asList("太辣", "不要辣", "别太辣", "不想吃辣", "不吃辣"),
                Arrays.asList("辣", "麻辣", "香辣", "爆辣", "川菜", "湘菜", "火锅"),
                Arrays.asList("清淡", "不辣", "微辣")
        ));

        PREFERENCE_RULES.put("太油腻", new NegativePreferenceRule(
                Arrays.asList("太油腻", "不要油腻", "别太油", "不想吃油的"),
                Arrays.asList("油腻", "重油", "油炸", "烧烤"),
                Arrays.asList("清淡", "少油", "健康")
        ));

        PREFERENCE_RULES.put("太甜", new NegativePreferenceRule(
                Arrays.asList("太甜", "不要太甜", "别太甜", "不喜欢甜"),
                Arrays.asList("甜", "糖", "甜品", "蛋糕"),
                Arrays.asList("不甜", "微甜", "咸口")
        ));

        // 环境相关
        PREFERENCE_RULES.put("太吵", new NegativePreferenceRule(
                Arrays.asList("太吵", "不要太吵", "别太吵", "太闹", "吵闹"),
                Arrays.asList("吵", "闹", "嘈杂", "热闹", "大排档", "夜市"),
                Arrays.asList("安静", "清静", "幽静")
        ));

        PREFERENCE_RULES.put("太挤", new NegativePreferenceRule(
                Arrays.asList("太挤", "不要太挤", "人太多", "拥挤"),
                Arrays.asList("拥挤", "排队", "人多"),
                Arrays.asList("人少", "宽敞", "不拥挤")
        ));

        // 价格相关
        PREFERENCE_RULES.put("太贵", new NegativePreferenceRule(
                Arrays.asList("太贵", "不要太贵", "别太贵", "贵了", "价格高"),
                Arrays.asList("高端", "奢华", "豪华", "精致"),
                Arrays.asList("便宜", "实惠", "平价", "性价比")
        ));

        // 距离相关
        PREFERENCE_RULES.put("太远", new NegativePreferenceRule(
                Arrays.asList("太远", "不要太远", "别太远", "远了"),
                Arrays.asList(),  // 距离通过数值判断，不需要关键词
                Arrays.asList("近", "附近", "周边")
        ));

        // 服务相关
        PREFERENCE_RULES.put("太慢", new NegativePreferenceRule(
                Arrays.asList("太慢", "不要太慢", "上菜慢", "等太久"),
                Arrays.asList("慢", "等待时间长"),
                Arrays.asList("快", "出餐快", "效率高")
        ));
    }

    /**
     * 从用户消息中提取否定偏好
     */
    public List<String> extractNegativePreferences(String message) {
        if (StrUtil.isBlank(message)) {
            return Collections.emptyList();
        }

        Set<String> preferences = new LinkedHashSet<>();
        String normalized = message.toLowerCase(Locale.ROOT);

        for (Map.Entry<String, NegativePreferenceRule> entry : PREFERENCE_RULES.entrySet()) {
            String preferenceKey = entry.getKey();
            NegativePreferenceRule rule = entry.getValue();

            for (String trigger : rule.triggerPhrases) {
                if (normalized.contains(trigger)) {
                    preferences.add(preferenceKey);
                    break;
                }
            }
        }

        return new ArrayList<>(preferences);
    }

    /**
     * 检查店铺是否匹配否定偏好
     * @return true 表示店铺匹配了否定偏好，应该被过滤掉
     */
    public boolean matchesNegativePreference(String shopName, String shopArea, 
                                            String shopAddress, String negativePreference) {
        if (StrUtil.isBlank(negativePreference)) {
            return false;
        }

        NegativePreferenceRule rule = PREFERENCE_RULES.get(negativePreference);
        if (rule == null) {
            return false;
        }

        String text = normalizeText(shopName, shopArea, shopAddress);
        
        // 检查是否包含否定关键词
        for (String keyword : rule.negativeKeywords) {
            if (text.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }

        return false;
    }

    /**
     * 检查店铺是否匹配距离相关的否定偏好
     */
    public boolean matchesDistancePreference(Double distanceMeters, String negativePreference) {
        if (distanceMeters == null || !"太远".equals(negativePreference)) {
            return false;
        }

        // 超过 5 公里认为太远
        return distanceMeters > 5000D;
    }

    /**
     * 检查店铺是否匹配价格相关的否定偏好
     */
    public boolean matchesPricePreference(Long avgPrice, String negativePreference, Long userBudget) {
        if (avgPrice == null || !"太贵".equals(negativePreference)) {
            return false;
        }

        // 如果用户指定了预算，超过预算认为太贵
        if (userBudget != null && avgPrice > userBudget) {
            return true;
        }

        // 否则人均超过 150 认为太贵
        return avgPrice > 150L;
    }

    /**
     * 获取否定偏好的正向建议
     */
    public List<String> getPositiveSuggestions(String negativePreference) {
        NegativePreferenceRule rule = PREFERENCE_RULES.get(negativePreference);
        if (rule == null) {
            return Collections.emptyList();
        }
        return new ArrayList<>(rule.positiveSuggestions);
    }

    /**
     * 获取所有支持的否定偏好类型
     */
    public Set<String> getSupportedPreferences() {
        return new LinkedHashSet<>(PREFERENCE_RULES.keySet());
    }

    private String normalizeText(String... texts) {
        StringBuilder builder = new StringBuilder();
        for (String text : texts) {
            if (StrUtil.isNotBlank(text)) {
                builder.append(text).append(" ");
            }
        }
        return builder.toString().toLowerCase(Locale.ROOT).trim();
    }

    /**
     * 否定偏好规则
     */
    private static class NegativePreferenceRule {
        final List<String> triggerPhrases;      // 触发短语，如"太辣"、"不要辣"
        final List<String> negativeKeywords;    // 否定关键词，如"辣"、"麻辣"
        final List<String> positiveSuggestions; // 正向建议，如"清淡"、"不辣"

        NegativePreferenceRule(List<String> triggerPhrases, 
                              List<String> negativeKeywords,
                              List<String> positiveSuggestions) {
            this.triggerPhrases = triggerPhrases;
            this.negativeKeywords = negativeKeywords;
            this.positiveSuggestions = positiveSuggestions;
        }
    }
}
