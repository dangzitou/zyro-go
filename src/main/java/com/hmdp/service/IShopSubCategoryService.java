package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopSubCategory;

import java.util.List;

/**
 * 店铺细分类服务。
 * 统一负责“用户自然语言里的品类词”和“门店细分类”之间的匹配。
 */
public interface IShopSubCategoryService extends IService<ShopSubCategory> {

    /**
     * 根据用户输入匹配可能的细分类。
     *
     * @param keyword 用户输入或归一化后的关键词
     * @param parentTypeId 一级分类 id，可为空
     * @return 命中的细分类列表
     */
    List<ShopSubCategory> matchCategories(String keyword, Integer parentTypeId);

    /**
     * 根据用户输入找到已归类的候选门店 id。
     *
     * @param keyword 用户输入或归一化后的关键词
     * @param parentTypeId 一级分类 id，可为空
     * @param limit 最大返回数量
     * @return 候选门店 id 列表
     */
    List<Long> findMatchedShopIds(String keyword, Integer parentTypeId, int limit);

    /**
     * 对单个门店计算品类匹配得分。
     * 召回阶段可能还没有查关联表，这里会结合关联表和名称别名做轻量兜底。
     */
    double categoryMatchScore(Shop shop, String keyword);

    /**
     * 从用户输入中抽出已知品类词，用于关键词拆分。
     */
    List<String> extractMatchedTerms(String keyword);
}
