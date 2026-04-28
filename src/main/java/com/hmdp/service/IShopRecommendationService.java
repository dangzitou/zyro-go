package com.hmdp.service;

import com.hmdp.dto.ShopRecommendationQuery;
import com.hmdp.dto.ShopRecommendationDTO;

import java.util.List;

public interface IShopRecommendationService {
    default List<ShopRecommendationDTO> recommendShops(ShopRecommendationQuery query) {
        return recommendShops(
                query.getKeyword(),
                query.getTypeId(),
                query.getMaxBudget(),
                query.getCity(),
                query.getLocationHint(),
                query.getX(),
                query.getY(),
                query.getCouponOnly(),
                query.getLimit()
        );
    }

    default List<ShopRecommendationDTO> recommendShops(String keyword, Integer typeId, Long maxBudget,
                                                       Double x, Double y, Boolean couponOnly, Integer limit) {
        return recommendShops(keyword, typeId, maxBudget, null, null, x, y, couponOnly, limit);
    }

    List<ShopRecommendationDTO> recommendShops(String keyword, Integer typeId, Long maxBudget,
                                               String city, String locationHint,
                                               Double x, Double y, Boolean couponOnly, Integer limit);
}
