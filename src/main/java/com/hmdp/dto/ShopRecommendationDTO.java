package com.hmdp.dto;

import lombok.Data;

import java.util.List;

@Data
public class ShopRecommendationDTO {
    private Long shopId;
    private String name;
    private String area;
    private String address;
    private Long avgPrice;
    private Double score;
    private Double distanceMeters;
    private Integer couponCount;
    private String couponSummary;
    private String blogSummary;
    private List<String> reasonTags;
    private Double recommendationScore;
}
