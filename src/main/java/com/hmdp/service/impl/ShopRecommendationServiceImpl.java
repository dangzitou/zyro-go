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
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;

import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;

@Service
public class ShopRecommendationServiceImpl implements IShopRecommendationService {

    private static final int MAX_CANDIDATES = 30;
    private static final int MAX_CANDIDATES_WITH_LOCATION = 200;
    private static final int MAX_GEO_CANDIDATES = 120;
    private static final int MIN_LOCATION_CANDIDATES = 12;
    private static final int ENRICHMENT_MULTIPLIER = 4;
    private static final int MIN_ENRICHMENT_CANDIDATES = 12;
    private static final int MAX_ENRICHMENT_CANDIDATES = 24;
    private static final double GEO_SEARCH_RADIUS_METERS = 8000D;
    private static final double NEARBY_DISTANCE_LIMIT_METERS = 50000D;
    private static final String[] KEYWORD_HINTS = {"火锅", "咖啡", "烧烤", "奶茶", "酒吧", "KTV", "spa", "美发", "足疗"};

    @Resource
    private IShopService shopService;

    @Resource
    private IVoucherService voucherService;

    @Resource
    private IBlogService blogService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 推荐链路分成三步：
     * 1. 先做候选召回，尽量把“附近 + 类目 + 关键词”相关的店捞出来。
     * 2. 再做轻量粗排，避免对过多候选触发券/博客查询造成 N+1 压力。
     * 3. 最后只富化前排候选，产出可直接给 Agent 组织答案的推荐卡片。
     */
    @Override
    public List<ShopRecommendationDTO> recommendShops(String keyword, Integer typeId, Long maxBudget,
                                                      Double x, Double y, Boolean couponOnly, Integer limit) {
        int resultLimit = limit == null ? 5 : Math.max(1, Math.min(limit, 10));
        boolean useLocation = x != null && y != null;
        String normalizedKeyword = normalizeKeyword(keyword);
        List<Shop> candidates = loadCandidates(normalizedKeyword, typeId, maxBudget, x, y, useLocation);
        if (candidates == null || candidates.isEmpty()) {
            return Collections.emptyList();
        }

        // 这里只做轻量过滤和粗排，避免所有候选都去查优惠券和博客。
        List<Shop> shortlisted = candidates.stream()
                .filter(shop -> matchesBudget(shop, maxBudget) && matchesKeyword(shop, normalizedKeyword))
                .sorted(Comparator.comparingDouble((Shop shop) -> baseCandidateScore(shop, normalizedKeyword, x, y)).reversed())
                .limit(resolveEnrichmentLimit(resultLimit, couponOnly))
                .toList();

        List<ShopRecommendationDTO> recommendations = new ArrayList<ShopRecommendationDTO>();
        for (Shop shop : shortlisted) {
            List<Voucher> vouchers = loadVouchers(shop.getId());
            if (Boolean.TRUE.equals(couponOnly) && vouchers.isEmpty()) {
                continue;
            }
            List<Blog> blogs = loadShopBlogs(shop.getId());
            ShopRecommendationDTO dto = buildRecommendation(shop, vouchers, blogs, x, y, normalizedKeyword);
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

    /**
     * 富化候选数量不跟召回量完全一致，而是按最终返回条数放大几倍。
     * 这样既能保留排序余量，也能控制数据库查询成本。
     */
    private long resolveEnrichmentLimit(int resultLimit, Boolean couponOnly) {
        long limit = Math.max((long) resultLimit * ENRICHMENT_MULTIPLIER, MIN_ENRICHMENT_CANDIDATES);
        if (Boolean.TRUE.equals(couponOnly)) {
            limit = Math.max(limit, 18L);
        }
        return Math.min(limit, MAX_ENRICHMENT_CANDIDATES);
    }

    /**
     * 候选召回优先级：
     * 1. 有定位时先走 Redis GEO，优先捞“真的在附近”的门店。
     * 2. GEO 不够时，再补数据库模糊查询结果。
     * 3. 关键词太窄时，最后再补一层类目级候选，避免完全无召回。
     */
    private List<Shop> loadCandidates(String keyword, Integer typeId, Long maxBudget, Double x, Double y, boolean useLocation) {
        Map<Long, Shop> merged = new LinkedHashMap<Long, Shop>();
        if (useLocation && typeId != null) {
            mergeCandidates(merged, loadNearbyCandidates(typeId, x, y));
        }
        int targetSize = useLocation ? MAX_CANDIDATES_WITH_LOCATION : MAX_CANDIDATES;
        if (merged.size() < (useLocation ? MIN_LOCATION_CANDIDATES : targetSize)) {
            mergeCandidates(merged, loadPrimaryDbCandidates(keyword, typeId, maxBudget, targetSize));
        }
        if (merged.size() < targetSize && StrUtil.isNotBlank(keyword)) {
            mergeCandidates(merged, loadSupplementDbCandidates(typeId, maxBudget, targetSize));
        }
        return new ArrayList<Shop>(merged.values());
    }

    /**
     * 用 LinkedHashMap 去重，既保留先召回结果的优先级，也避免同一门店被多次富化。
     */
    private void mergeCandidates(Map<Long, Shop> merged, List<Shop> candidates) {
        for (Shop shop : candidates) {
            if (shop != null && shop.getId() != null) {
                merged.putIfAbsent(shop.getId(), shop);
            }
        }
    }

    /**
     * Redis GEO 用来承接“附近”语义。
     * 返回后会把距离回填到 Shop 上，后面排序和回答都能直接复用。
     */
    private List<Shop> loadNearbyCandidates(Integer typeId, Double x, Double y) {
        if (typeId == null || x == null || y == null || stringRedisTemplate == null) {
            return Collections.emptyList();
        }
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo().search(
                SHOP_GEO_KEY + typeId,
                GeoReference.fromCoordinate(x, y),
                new Distance(GEO_SEARCH_RADIUS_METERS),
                RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(MAX_GEO_CANDIDATES)
        );
        if (results == null || results.getContent().isEmpty()) {
            return Collections.emptyList();
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> geoHits = results.getContent();
        List<Long> ids = new ArrayList<Long>(geoHits.size());
        Map<Long, Double> distanceById = new LinkedHashMap<Long, Double>();
        for (GeoResult<RedisGeoCommands.GeoLocation<String>> hit : geoHits) {
            Long shopId = Long.parseLong(hit.getContent().getName());
            ids.add(shopId);
            distanceById.put(shopId, hit.getDistance() == null ? null : hit.getDistance().getValue());
        }
        if (ids.isEmpty()) {
            return Collections.emptyList();
        }
        String orderedIds = StrUtil.join(",", ids);
        List<Shop> shops = shopService.query()
                .in("id", ids)
                .last("ORDER BY FIELD(id," + orderedIds + ")")
                .list();
        for (Shop shop : shops) {
            shop.setDistance(distanceById.get(shop.getId()));
        }
        return shops;
    }

    /**
     * 关键词主召回负责“精准一点”的命中，适合火锅、咖啡、烧烤这类明确意图。
     */
    private List<Shop> loadPrimaryDbCandidates(String keyword, Integer typeId, Long maxBudget, int limit) {
        return shopService.query()
                .like(StrUtil.isNotBlank(keyword), "name", keyword)
                .eq(typeId != null, "type_id", typeId)
                .le(maxBudget != null, "avg_price", maxBudget)
                .last("LIMIT " + limit)
                .list();
    }

    /**
     * 补召回不带关键词过滤，目的是在数据稀疏时兜底，不让“附近推荐”直接空掉。
     */
    private List<Shop> loadSupplementDbCandidates(Integer typeId, Long maxBudget, int limit) {
        return shopService.query()
                .eq(typeId != null, "type_id", typeId)
                .le(maxBudget != null, "avg_price", maxBudget)
                .last("LIMIT " + limit)
                .list();
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

    /**
     * DTO 是给 Agent 用的“可解释推荐卡片”，不仅有基础字段，还带推荐理由和排序分。
     */
    private ShopRecommendationDTO buildRecommendation(Shop shop, List<Voucher> vouchers, List<Blog> blogs,
                                                      Double x, Double y, String keyword) {
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
        if (shop.getDistance() != null) {
            dto.setDistanceMeters(Math.round(shop.getDistance() * 10D) / 10D);
        } else if (x != null && y != null && shop.getX() != null && shop.getY() != null) {
            dto.setDistanceMeters(distanceMeters(x, y, shop.getX(), shop.getY()));
        }
        dto.setReasonTags(buildReasonTags(shop, dto, vouchers, blogs, keyword));
        dto.setRecommendationScore(calculateScore(shop, dto, blogs, keyword));
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

    private List<String> buildReasonTags(Shop shop, ShopRecommendationDTO dto, List<Voucher> vouchers, List<Blog> blogs, String keyword) {
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
        if (textualMatchScore(shop, keyword) > 0D) {
            tags.add("keyword_match");
        }
        return tags;
    }

    /**
     * 最终排序是多信号叠加：
     * 评分、评论量、优惠、距离、内容新鲜度、关键词匹配都会参与打分。
     */
    private Double calculateScore(Shop shop, ShopRecommendationDTO dto, List<Blog> blogs, String keyword) {
        double ratingScore = shop.getScore() == null ? 0D : shop.getScore() / 10.0D;
        double commentScore = shop.getComments() == null ? 0D : Math.min(shop.getComments() / 200.0D, 1.5D);
        double couponScore = dto.getCouponCount() == null ? 0D : Math.min(dto.getCouponCount() * 0.2D, 1D);
        double distanceScore = 0D;
        if (dto.getDistanceMeters() != null) {
            distanceScore = Math.max(0D, 1.5D - dto.getDistanceMeters() / 2000.0D);
        }
        double keywordScore = textualMatchScore(shop, keyword);
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
        return ratingScore + commentScore + couponScore + distanceScore + freshnessScore + keywordScore;
    }

    private boolean matchesBudget(Shop shop, Long maxBudget) {
        return maxBudget == null || shop.getAvgPrice() == null || shop.getAvgPrice() <= maxBudget;
    }

    /**
     * 粗排分数只依赖轻量字段，保证召回阶段足够快。
     */
    private double baseCandidateScore(Shop shop, String keyword, Double x, Double y) {
        double score = textualMatchScore(shop, keyword);
        score += categoryMatchScore(shop, keyword);
        score += shop.getScore() == null ? 0D : shop.getScore() / 10.0D;
        score += shop.getComments() == null ? 0D : Math.min(shop.getComments() / 300.0D, 1.2D);
        Double distance = shop.getDistance();
        if (distance == null && x != null && y != null && shop.getX() != null && shop.getY() != null) {
            distance = distanceMeters(x, y, shop.getX(), shop.getY());
        }
        if (distance != null) {
            score += Math.max(0D, 1.6D - distance / 1800.0D);
        }
        return score;
    }

    private boolean matchesKeyword(Shop shop, String keyword) {
        return StrUtil.isBlank(keyword)
                || textualMatchScore(shop, keyword) > 0D
                || categoryMatchScore(shop, keyword) > 0D;
    }

    /**
     * 关键词命中不只看店名，也看商圈和地址。
     * 这样“天河区咖啡”“枫叶路火锅”这类自然表达也能命中。
     */
    private double textualMatchScore(Shop shop, String keyword) {
        if (shop == null || StrUtil.isBlank(keyword)) {
            return 0D;
        }
        List<String> variants = buildKeywordVariants(keyword);
        double score = 0D;
        if (containsAnyVariant(shop.getName(), variants)) {
            score += 1.8D;
        }
        if (containsAnyVariant(shop.getArea(), variants)) {
            score += 0.8D;
        }
        if (containsAnyVariant(shop.getAddress(), variants)) {
            score += 0.6D;
        }
        String exactKeyword = keyword.toLowerCase(Locale.ROOT).trim();
        if (containsText(shop.getName(), exactKeyword)) {
            score += 0.8D;
        }
        return score;
    }

    /**
     * 把用户自然语言里的“推荐、店铺、门店”之类噪音词剥掉，再拆出品类词和地址词。
     */
    private List<String> buildKeywordVariants(String keyword) {
        Set<String> variants = new LinkedHashSet<String>();
        String normalized = keyword.toLowerCase(Locale.ROOT).trim();
        if (StrUtil.isBlank(normalized)) {
            return Collections.emptyList();
        }
        variants.add(normalized);
        String relaxed = normalized.replace("店铺", "")
                .replace("商家", "")
                .replace("门店", "")
                .replace("推荐", "")
                .replace("店", "")
                .trim();
        if (StrUtil.isNotBlank(relaxed)) {
            variants.add(relaxed);
        }
        String addressPart = relaxed;
        for (String hint : KEYWORD_HINTS) {
            String lowerHint = hint.toLowerCase(Locale.ROOT);
            if (relaxed.contains(lowerHint)) {
                variants.add(lowerHint);
                addressPart = addressPart.replace(lowerHint, " ");
            }
        }
        addressPart = addressPart.trim();
        if (addressPart.length() >= 2) {
            variants.add(addressPart);
        }
        return new ArrayList<String>(variants);
    }

    private boolean containsAnyVariant(String source, List<String> variants) {
        if (StrUtil.isBlank(source) || variants == null || variants.isEmpty()) {
            return false;
        }
        for (String variant : variants) {
            if (containsText(source, variant)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsText(String source, String keyword) {
        return StrUtil.isNotBlank(source) && StrUtil.isNotBlank(keyword)
                && source.toLowerCase(Locale.ROOT).contains(keyword);
    }

    /**
     * 当用户给的是“海鲜餐厅、约会餐厅”这类长句时，
     * 这里补一层轻量品类语义匹配，避免必须要求整句命中门店名称。
     */
    private double categoryMatchScore(Shop shop, String keyword) {
        if (shop == null || StrUtil.isBlank(keyword)) {
            return 0D;
        }
        String normalized = keyword.toLowerCase(Locale.ROOT);
        double score = 0D;
        if (normalized.contains("海鲜")) {
            score += containsAnyText(shop, "海鲜", "渔", "酒家", "鲜") ? 1.6D : 0D;
        }
        if (normalized.contains("火锅")) {
            score += containsAnyText(shop, "火锅", "锅", "涮") ? 1.4D : 0D;
        }
        if (normalized.contains("咖啡")) {
            score += containsAnyText(shop, "咖啡", "coffee", "cafe") ? 1.4D : 0D;
        }
        if (normalized.contains("烧烤")) {
            score += containsAnyText(shop, "烧烤", "烤肉", "烤") ? 1.4D : 0D;
        }
        return score;
    }

    private boolean containsAnyText(Shop shop, String... values) {
        for (String value : values) {
            if (containsText(shop.getName(), value.toLowerCase(Locale.ROOT))
                    || containsText(shop.getArea(), value.toLowerCase(Locale.ROOT))
                    || containsText(shop.getAddress(), value.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String normalizeKeyword(String keyword) {
        if (StrUtil.isBlank(keyword)) {
            return "";
        }
        return keyword.replace("附近", "")
                .replace("周边", "")
                .replace("推荐", "")
                .replace("店铺", "")
                .replace("商家", "")
                .trim();
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
