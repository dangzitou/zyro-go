package com.hmdp.ai;

import com.hmdp.dto.AgentExecutionPlan;
import com.hmdp.dto.ShopRecommendationDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Shop;
import com.hmdp.entity.Voucher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RecommendationReasonBuilderTest {

    private RecommendationReasonBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new RecommendationReasonBuilder();
    }

    @Test
    void testBuildReasons_HighRating() {
        Shop shop = new Shop();
        shop.setId(1L);
        shop.setName("测试餐厅");
        shop.setScore(48); // 4.8 分

        ShopRecommendationDTO dto = new ShopRecommendationDTO();
        dto.setScore(4.8);

        List<String> reasons = builder.buildReasons(shop, dto, new ArrayList<>(), new ArrayList<>(), null, null);

        assertTrue(reasons.contains("high_rating"));
        assertTrue(reasons.contains("rating_above_4.5"));
    }

    @Test
    void testBuildReasons_Nearby() {
        Shop shop = new Shop();
        shop.setId(1L);
        shop.setName("测试餐厅");

        ShopRecommendationDTO dto = new ShopRecommendationDTO();
        dto.setDistanceMeters(800.0);

        List<String> reasons = builder.buildReasons(shop, dto, new ArrayList<>(), new ArrayList<>(), null, null);

        assertTrue(reasons.contains("nearby"));
        assertTrue(reasons.contains("within_1km"));
    }

    @Test
    void testBuildReasons_HasCoupon() {
        Shop shop = new Shop();
        shop.setId(1L);
        shop.setName("测试餐厅");

        ShopRecommendationDTO dto = new ShopRecommendationDTO();
        dto.setCouponCount(2);

        Voucher voucher1 = new Voucher();
        voucher1.setId(1L);
        Voucher voucher2 = new Voucher();
        voucher2.setId(2L);
        List<Voucher> vouchers = List.of(voucher1, voucher2);

        List<String> reasons = builder.buildReasons(shop, dto, vouchers, new ArrayList<>(), null, null);

        assertTrue(reasons.contains("has_coupon"));
    }

    @Test
    void testBuildReasons_GoodValue() {
        Shop shop = new Shop();
        shop.setId(1L);
        shop.setName("测试餐厅");
        shop.setAvgPrice(50L);

        ShopRecommendationDTO dto = new ShopRecommendationDTO();
        dto.setAvgPrice(50L);

        List<String> reasons = builder.buildReasons(shop, dto, new ArrayList<>(), new ArrayList<>(), null, null);

        assertTrue(reasons.contains("good_value"));
        assertTrue(reasons.contains("cheap"));
    }

    @Test
    void testBuildReasons_WithinBudget() {
        Shop shop = new Shop();
        shop.setId(1L);
        shop.setName("测试餐厅");
        shop.setAvgPrice(80L);

        ShopRecommendationDTO dto = new ShopRecommendationDTO();
        dto.setAvgPrice(80L);

        AgentExecutionPlan plan = new AgentExecutionPlan();
        plan.setBudgetMax(100L);

        List<String> reasons = builder.buildReasons(shop, dto, new ArrayList<>(), new ArrayList<>(), plan, null);

        assertTrue(reasons.contains("within_budget"));
    }

    @Test
    void testBuildReasons_RecentlyHot() {
        Shop shop = new Shop();
        shop.setId(1L);
        shop.setName("测试餐厅");

        ShopRecommendationDTO dto = new ShopRecommendationDTO();

        Blog blog = new Blog();
        blog.setId(1L);
        blog.setLiked(100);
        List<Blog> blogs = List.of(blog);

        List<String> reasons = builder.buildReasons(shop, dto, new ArrayList<>(), blogs, null, null);

        assertTrue(reasons.contains("recently_hot"));
        assertTrue(reasons.contains("popular_blog"));
    }

    @Test
    void testBuildReasons_PreferenceMatch() {
        Shop shop = new Shop();
        shop.setId(1L);
        shop.setName("清淡素食餐厅");

        ShopRecommendationDTO dto = new ShopRecommendationDTO();

        AgentExecutionPlan plan = new AgentExecutionPlan();
        plan.setQualityPreference("清淡");

        List<String> reasons = builder.buildReasons(shop, dto, new ArrayList<>(), new ArrayList<>(), plan, null);

        assertTrue(reasons.contains("light_taste"));
        assertTrue(reasons.contains("preference_match"));
    }

    @Test
    void testBuildChineseReasons() {
        List<String> reasons = List.of("high_rating", "nearby", "has_coupon", "good_value");
        String chineseReasons = builder.buildChineseReasons(reasons);

        assertTrue(chineseReasons.contains("推荐理由："));
        assertTrue(chineseReasons.contains("评分很高"));
        assertTrue(chineseReasons.contains("就在附近"));
        assertTrue(chineseReasons.contains("有优惠券"));
        assertTrue(chineseReasons.contains("性价比高"));
    }

    @Test
    void testBuildChineseReasons_Empty() {
        List<String> reasons = new ArrayList<>();
        String chineseReasons = builder.buildChineseReasons(reasons);

        assertEquals("", chineseReasons);
    }
}
