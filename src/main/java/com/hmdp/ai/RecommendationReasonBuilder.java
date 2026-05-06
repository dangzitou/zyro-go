package com.hmdp.ai;

import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.AgentExecutionPlan;
import com.hmdp.dto.ShopRecommendationDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Shop;
import com.hmdp.entity.Voucher;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 推荐理由构建器
 * 负责为推荐结果生成丰富的、个性化的推荐理由
 */
@Component
public class RecommendationReasonBuilder {

    /**
     * 构建推荐理由
     */
    public List<String> buildReasons(Shop shop,
                                     ShopRecommendationDTO dto,
                                     List<Voucher> vouchers,
                                     List<Blog> blogs,
                                     AgentExecutionPlan plan,
                                     String keyword) {
        List<String> reasons = new ArrayList<>();

        // 1. 基础质量理由
        addQualityReasons(reasons, shop, dto);

        // 2. 距离和位置理由
        addLocationReasons(reasons, dto, plan);

        // 3. 价格和优惠理由
        addPriceReasons(reasons, shop, dto, vouchers, plan);

        // 4. 个性化偏好理由
        addPreferenceReasons(reasons, shop, dto, plan);

        // 5. 社交证明理由
        addSocialProofReasons(reasons, blogs);

        // 6. 场景匹配理由
        addSceneMatchReasons(reasons, shop, dto, plan);

        // 7. 关键词匹配理由
        addKeywordMatchReasons(reasons, shop, keyword);

        return reasons;
    }

    /**
     * 构建中文推荐理由描述
     */
    public String buildChineseReasons(List<String> reasons) {
        if (reasons == null || reasons.isEmpty()) {
            return "";
        }

        Map<String, String> reasonTranslations = new LinkedHashMap<>();
        reasonTranslations.put("high_rating", "评分很高");
        reasonTranslations.put("rating_above_4.5", "评分 4.5 分以上");
        reasonTranslations.put("rating_above_4.0", "评分 4.0 分以上");
        reasonTranslations.put("many_reviews", "评价很多");
        reasonTranslations.put("nearby", "就在附近");
        reasonTranslations.put("very_close", "非常近");
        reasonTranslations.put("within_1km", "1 公里内");
        reasonTranslations.put("within_2km", "2 公里内");
        reasonTranslations.put("has_coupon", "有优惠券");
        reasonTranslations.put("multiple_coupons", "多张优惠券");
        reasonTranslations.put("good_value", "性价比高");
        reasonTranslations.put("within_budget", "符合预算");
        reasonTranslations.put("cheap", "价格实惠");
        reasonTranslations.put("recently_hot", "最近很火");
        reasonTranslations.put("popular_blog", "有热门探店笔记");
        reasonTranslations.put("keyword_match", "符合搜索");
        reasonTranslations.put("exact_match", "完全匹配");
        reasonTranslations.put("suitable_for_date", "适合约会");
        reasonTranslations.put("quiet_environment", "环境安静");
        reasonTranslations.put("suitable_for_party", "适合聚餐");
        reasonTranslations.put("fast_service", "出餐快");
        reasonTranslations.put("light_taste", "口味清淡");
        reasonTranslations.put("preference_match", "符合你的偏好");

        List<String> translatedReasons = new ArrayList<>();
        for (String reason : reasons) {
            String translation = reasonTranslations.get(reason);
            if (translation != null) {
                translatedReasons.add(translation);
            }
        }

        if (translatedReasons.isEmpty()) {
            return "";
        }

        return "推荐理由：" + String.join("、", translatedReasons);
    }

    private void addQualityReasons(List<String> reasons, Shop shop, ShopRecommendationDTO dto) {
        if (dto.getScore() != null) {
            if (dto.getScore() >= 4.5D) {
                reasons.add("high_rating");
                reasons.add("rating_above_4.5");
            } else if (dto.getScore() >= 4.0D) {
                reasons.add("rating_above_4.0");
            }
        }

        if (shop.getComments() != null && shop.getComments() > 100) {
            reasons.add("many_reviews");
        }
    }

    private void addLocationReasons(List<String> reasons, ShopRecommendationDTO dto, AgentExecutionPlan plan) {
        if (dto.getDistanceMeters() == null) {
            return;
        }

        if (dto.getDistanceMeters() <= 500D) {
            reasons.add("very_close");
        } else if (dto.getDistanceMeters() <= 1000D) {
            reasons.add("nearby");
            reasons.add("within_1km");
        } else if (dto.getDistanceMeters() <= 2000D) {
            reasons.add("within_2km");
        }

        if (Boolean.TRUE.equals(plan != null ? plan.getNearby() : null) 
            && dto.getDistanceMeters() <= 1000D) {
            reasons.add("nearby");
        }
    }

    private void addPriceReasons(List<String> reasons, Shop shop, ShopRecommendationDTO dto, 
                                List<Voucher> vouchers, AgentExecutionPlan plan) {
        // 优惠券理由
        if (dto.getCouponCount() != null && dto.getCouponCount() > 0) {
            reasons.add("has_coupon");
            if (dto.getCouponCount() >= 3) {
                reasons.add("multiple_coupons");
            }
        }

        // 价格理由
        if (dto.getAvgPrice() != null) {
            if (dto.getAvgPrice() <= 50L) {
                reasons.add("cheap");
                reasons.add("good_value");
            } else if (dto.getAvgPrice() <= 80L) {
                reasons.add("good_value");
            }

            // 预算匹配
            if (plan != null && plan.getBudgetMax() != null) {
                if (dto.getAvgPrice() <= plan.getBudgetMax()) {
                    reasons.add("within_budget");
                }
                // 如果价格明显低于预算，强调性价比
                if (dto.getAvgPrice() <= plan.getBudgetMax() * 0.7) {
                    reasons.add("good_value");
                }
            }

            // 价格偏好匹配
            if (plan != null && "cheap".equals(plan.getPricePreference())) {
                if (dto.getAvgPrice() <= 80L) {
                    reasons.add("preference_match");
                }
            }
        }
    }

    private void addPreferenceReasons(List<String> reasons, Shop shop, ShopRecommendationDTO dto, 
                                     AgentExecutionPlan plan) {
        if (plan == null || StrUtil.isBlank(plan.getQualityPreference())) {
            return;
        }

        String preference = plan.getQualityPreference();
        String shopText = normalizeText(shop.getName(), shop.getArea(), shop.getAddress());

        // 清淡偏好
        if (preference.contains("清淡")) {
            if (shopText.contains("素") || shopText.contains("清") || shopText.contains("淡")) {
                reasons.add("light_taste");
                reasons.add("preference_match");
            }
        }

        // 出餐快偏好
        if (preference.contains("出餐快")) {
            if (shopText.contains("快餐") || shopText.contains("简餐") || shopText.contains("轻食")) {
                reasons.add("fast_service");
                reasons.add("preference_match");
            }
        }

        // 安静偏好
        if (preference.contains("安静")) {
            if (shopText.contains("安静") || shopText.contains("清静") || shopText.contains("幽静")) {
                reasons.add("quiet_environment");
                reasons.add("preference_match");
            }
        }
    }

    private void addSocialProofReasons(List<String> reasons, List<Blog> blogs) {
        if (blogs == null || blogs.isEmpty()) {
            return;
        }

        reasons.add("recently_hot");

        // 检查是否有高赞博客
        boolean hasPopularBlog = blogs.stream()
                .anyMatch(blog -> blog.getLiked() != null && blog.getLiked() > 50);
        if (hasPopularBlog) {
            reasons.add("popular_blog");
        }
    }

    private void addSceneMatchReasons(List<String> reasons, Shop shop, ShopRecommendationDTO dto, 
                                     AgentExecutionPlan plan) {
        if (plan == null || StrUtil.isBlank(plan.getQualityPreference())) {
            return;
        }

        String preference = plan.getQualityPreference();

        // 约会场景
        if (preference.contains("约会")) {
            if (dto.getScore() != null && dto.getScore() >= 4.3D) {
                reasons.add("suitable_for_date");
            }
        }

        // 聚餐场景
        if (plan.getPartySize() != null && plan.getPartySize() >= 4) {
            String shopText = normalizeText(shop.getName());
            if (shopText.contains("聚") || shopText.contains("宴") || shopText.contains("包厢")) {
                reasons.add("suitable_for_party");
            }
        }
    }

    private void addKeywordMatchReasons(List<String> reasons, Shop shop, String keyword) {
        if (StrUtil.isBlank(keyword)) {
            return;
        }

        String normalizedKeyword = keyword.toLowerCase(Locale.ROOT).trim();
        String shopName = shop.getName() == null ? "" : shop.getName().toLowerCase(Locale.ROOT);

        if (shopName.contains(normalizedKeyword)) {
            reasons.add("keyword_match");
            if (shopName.equals(normalizedKeyword)) {
                reasons.add("exact_match");
            }
        }
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
}
