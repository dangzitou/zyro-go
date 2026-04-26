package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.dto.ShopRecommendationDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Shop;
import com.hmdp.entity.Voucher;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IShopRecommendationService;
import com.hmdp.service.IShopService;
import com.hmdp.service.IVoucherService;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

@Service
public class ShopRecommendationServiceImpl implements IShopRecommendationService {

    private static final int MAX_CANDIDATES = 30;
    private static final int MAX_CANDIDATES_WITH_LOCATION = 200;
    private static final double NEARBY_DISTANCE_LIMIT_METERS = 50000D;

    @Resource
    private IShopService shopService;

    @Resource
    private IVoucherService voucherService;

    @Resource
    private IBlogService blogService;

    @Override
    public List<ShopRecommendationDTO> recommendShops(String keyword, Integer typeId, Long maxBudget,
                                                      Double x, Double y, Boolean couponOnly, Integer limit) {
        int resultLimit = limit == null ? 5 : Math.max(1, Math.min(limit, 10));
        boolean useLocation = x != null && y != null;
        List<Shop> candidates = shopService.query()
                .like(StrUtil.isNotBlank(keyword), "name", keyword)
                .eq(typeId != null, "type_id", typeId)
                .le(maxBudget != null, "avg_price", maxBudget)
                .last("LIMIT " + (useLocation ? MAX_CANDIDATES_WITH_LOCATION : MAX_CANDIDATES))
                .list();
        if (candidates == null || candidates.isEmpty()) {
            return Collections.emptyList();
        }

        List<ShopRecommendationDTO> recommendations = new ArrayList<ShopRecommendationDTO>();
        for (Shop shop : candidates) {
            List<Voucher> vouchers = loadVouchers(shop.getId());
            if (Boolean.TRUE.equals(couponOnly) && vouchers.isEmpty()) {
                continue;
            }
            List<Blog> blogs = loadShopBlogs(shop.getId());
            ShopRecommendationDTO dto = buildRecommendation(shop, vouchers, blogs, x, y);
            if (useLocation && dto.getDistanceMeters() != null && dto.getDistanceMeters() > NEARBY_DISTANCE_LIMIT_METERS) {
                continue;
            }
            recommendations.add(dto);
        }

        recommendations.sort(Comparator.comparing(ShopRecommendationDTO::getRecommendationScore).reversed());
        if (recommendations.size() > resultLimit) {
            return new ArrayList<ShopRecommendationDTO>(recommendations.subList(0, resultLimit));
        }
        return recommendations;
    }

    private List<Voucher> loadVouchers(Long shopId) {
        Result result = voucherService.queryVoucherOfShop(shopId);
        Object data = result.getData();
        if (!(data instanceof List)) {
            return Collections.emptyList();
        }
        List<?> rawList = (List<?>) data;
        List<Voucher> vouchers = new ArrayList<Voucher>(rawList.size());
        for (Object item : rawList) {
            vouchers.add(JSONUtil.toBean(JSONUtil.parseObj(item), Voucher.class));
        }
        return vouchers;
    }

    private List<Blog> loadShopBlogs(Long shopId) {
        return blogService.query()
                .eq("shop_id", shopId)
                .orderByDesc("liked")
                .orderByDesc("create_time")
                .last("LIMIT 3")
                .list();
    }

    private ShopRecommendationDTO buildRecommendation(Shop shop, List<Voucher> vouchers, List<Blog> blogs,
                                                      Double x, Double y) {
        ShopRecommendationDTO dto = new ShopRecommendationDTO();
        dto.setShopId(shop.getId());
        dto.setName(shop.getName());
        dto.setArea(shop.getArea());
        dto.setAddress(shop.getAddress());
        dto.setAvgPrice(shop.getAvgPrice());
        dto.setScore(shop.getScore() == null ? null : shop.getScore() / 10.0);
        dto.setCouponCount(vouchers.size());
        dto.setCouponSummary(buildCouponSummary(vouchers));
        dto.setBlogSummary(buildBlogSummary(blogs));
        if (x != null && y != null && shop.getX() != null && shop.getY() != null) {
            dto.setDistanceMeters(distanceMeters(x, y, shop.getX(), shop.getY()));
        }
        dto.setReasonTags(buildReasonTags(dto, vouchers, blogs));
        dto.setRecommendationScore(calculateScore(shop, dto, blogs));
        return dto;
    }

    private String buildCouponSummary(List<Voucher> vouchers) {
        if (vouchers.isEmpty()) {
            return "No active coupons";
        }
        Voucher best = vouchers.stream()
                .min(Comparator.comparing(Voucher::getPayValue))
                .orElse(vouchers.get(0));
        return "Coupons=" + vouchers.size() + ", bestPrice=" + best.getPayValue();
    }

    private String buildBlogSummary(List<Blog> blogs) {
        if (blogs == null || blogs.isEmpty()) {
            return "No recent hot content";
        }
        Blog top = blogs.get(0);
        String title = StrUtil.blankToDefault(top.getTitle(), "shop review");
        return "Hot post " + title + ", likes=" + top.getLiked();
    }

    private List<String> buildReasonTags(ShopRecommendationDTO dto, List<Voucher> vouchers, List<Blog> blogs) {
        List<String> tags = new ArrayList<String>();
        if (dto.getScore() != null && dto.getScore() >= 4.5D) {
            tags.add("high_rating");
        }
        if (dto.getDistanceMeters() != null && dto.getDistanceMeters() <= 1000D) {
            tags.add("nearby");
        }
        if (!vouchers.isEmpty()) {
            tags.add("has_coupon");
        }
        if (blogs != null && !blogs.isEmpty()) {
            tags.add("recently_hot");
        }
        if (dto.getAvgPrice() != null && dto.getAvgPrice() <= 80L) {
            tags.add("good_value");
        }
        return tags;
    }

    private Double calculateScore(Shop shop, ShopRecommendationDTO dto, List<Blog> blogs) {
        double ratingScore = shop.getScore() == null ? 0D : shop.getScore() / 10.0D;
        double commentScore = shop.getComments() == null ? 0D : Math.min(shop.getComments() / 200.0D, 1.5D);
        double couponScore = dto.getCouponCount() == null ? 0D : Math.min(dto.getCouponCount() * 0.2D, 1D);
        double distanceScore = 0D;
        if (dto.getDistanceMeters() != null) {
            distanceScore = Math.max(0D, 1.5D - dto.getDistanceMeters() / 2000.0D);
        }
        double freshnessScore = 0D;
        if (blogs != null && !blogs.isEmpty()) {
            Blog latest = blogs.stream()
                    .filter(blog -> blog.getCreateTime() != null)
                    .max(Comparator.comparing(Blog::getCreateTime))
                    .orElse(null);
            if (latest != null) {
                long days = ChronoUnit.DAYS.between(latest.getCreateTime(), LocalDateTime.now());
                freshnessScore = Math.max(0.2D, Math.exp(-0.03D * Math.max(days, 0)));
            }
            int likedSum = blogs.stream().map(Blog::getLiked).filter(v -> v != null).mapToInt(Integer::intValue).sum();
            freshnessScore += Math.min(likedSum / 200.0D, 1D);
        }
        return ratingScore + commentScore + couponScore + distanceScore + freshnessScore;
    }

    private Double distanceMeters(Double userX, Double userY, Double shopX, Double shopY) {
        double earthRadius = 6371000D;
        double lat1 = Math.toRadians(userY);
        double lat2 = Math.toRadians(shopY);
        double deltaLat = Math.toRadians(shopY - userY);
        double deltaLng = Math.toRadians(shopX - userX);

        double a = Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(deltaLng / 2) * Math.sin(deltaLng / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return Math.round(earthRadius * c * 10D) / 10D;
    }
}
