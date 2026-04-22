package com.hmdp.service;

import com.hmdp.dto.ShopRecommendationDTO;

import java.util.List;

public interface IShopRecommendationService {
    List<ShopRecommendationDTO> recommendShops(String keyword, Integer typeId, Long maxBudget,
                                               Double x, Double y, Boolean couponOnly, Integer limit);
}
