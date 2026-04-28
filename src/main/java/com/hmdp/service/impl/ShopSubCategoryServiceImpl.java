package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopSubCategory;
import com.hmdp.entity.ShopSubCategoryRelation;
import com.hmdp.mapper.ShopSubCategoryMapper;
import com.hmdp.service.IShopSubCategoryRelationService;
import com.hmdp.service.IShopSubCategoryService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 店铺细分类服务实现。
 * 当前使用 MySQL 字典 + 规则匹配，后续可以平滑替换成“字典召回 + LLM 纠错”的企业版方案。
 */
@Service
public class ShopSubCategoryServiceImpl extends ServiceImpl<ShopSubCategoryMapper, ShopSubCategory>
        implements IShopSubCategoryService {

    @Resource
    private IShopSubCategoryRelationService relationService;

    /**
     * 根据关键词命中字典中的细分类。
     * 这里不把“海鲜/火锅”等写死在 Java 里，而是统一读取 tb_shop_sub_category.aliases。
     */
    @Override
    public List<ShopSubCategory> matchCategories(String keyword, Integer parentTypeId) {
        if (StrUtil.isBlank(keyword)) {
            return Collections.emptyList();
        }
        String normalized = normalize(keyword);
        List<ShopSubCategory> categories = query()
                .eq("status", 1)
                .eq(parentTypeId != null, "parent_type_id", parentTypeId)
                .orderByAsc("parent_type_id", "sort")
                .list();
        List<ShopSubCategory> matched = new ArrayList<ShopSubCategory>();
        for (ShopSubCategory category : categories) {
            if (matchesCategory(normalized, category)) {
                matched.add(category);
            }
        }
        return matched;
    }

    /**
     * 先匹配用户意图中的细分类，再通过关联表召回门店。
     * 这一步让“厦大附近海鲜餐厅”能命中被归类为海鲜的门店，而不是要求整句出现在店名里。
     */
    @Override
    public List<Long> findMatchedShopIds(String keyword, Integer parentTypeId, int limit) {
        List<ShopSubCategory> categories = matchCategories(keyword, parentTypeId);
        if (categories.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> categoryIds = categories.stream().map(ShopSubCategory::getId).toList();
        List<ShopSubCategoryRelation> relations = relationService.query()
                .in("sub_category_id", categoryIds)
                .orderByDesc("is_primary")
                .orderByDesc("confidence")
                .last("LIMIT " + Math.max(limit, 1))
                .list();
        Set<Long> shopIds = new LinkedHashSet<Long>();
        for (ShopSubCategoryRelation relation : relations) {
            shopIds.add(relation.getShopId());
        }
        return new ArrayList<Long>(shopIds);
    }

    /**
     * 给粗排提供轻量分数。
     * 有关联表时按关联命中加分；没有关联数据时，用店名/地址和别名做兜底，避免冷启动完全不可用。
     */
    @Override
    public double categoryMatchScore(Shop shop, String keyword) {
        if (shop == null || shop.getId() == null || StrUtil.isBlank(keyword)) {
            return 0D;
        }
        List<ShopSubCategory> categories = matchCategories(keyword,
                shop.getTypeId() == null ? null : shop.getTypeId().intValue());
        if (categories.isEmpty()) {
            return 0D;
        }
        List<Long> categoryIds = categories.stream().map(ShopSubCategory::getId).toList();
        long relationCount = relationService.query()
                .eq("shop_id", shop.getId())
                .in("sub_category_id", categoryIds)
                .count();
        if (relationCount > 0) {
            return 1.6D;
        }
        String shopText = normalize(StrUtil.nullToEmpty(shop.getName()) + " "
                + StrUtil.nullToEmpty(shop.getArea()) + " "
                + StrUtil.nullToEmpty(shop.getAddress()));
        for (ShopSubCategory category : categories) {
            for (String term : categoryTerms(category)) {
                if (term.length() >= 2 && shopText.contains(term)) {
                    return 1.2D;
                }
            }
        }
        return 0D;
    }

    /**
     * 抽出命中的品类词，供推荐服务把长句拆成“地址词 + 品类词”。
     */
    @Override
    public List<String> extractMatchedTerms(String keyword) {
        List<ShopSubCategory> categories = matchCategories(keyword, null);
        Set<String> terms = new LinkedHashSet<String>();
        for (ShopSubCategory category : categories) {
            terms.addAll(categoryTerms(category));
        }
        return new ArrayList<String>(terms);
    }

    private boolean matchesCategory(String normalizedKeyword, ShopSubCategory category) {
        for (String term : categoryTerms(category)) {
            if (term.length() >= 2 && normalizedKeyword.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private List<String> categoryTerms(ShopSubCategory category) {
        Set<String> terms = new LinkedHashSet<String>();
        addTerm(terms, category.getName());
        if (StrUtil.isNotBlank(category.getAliases())) {
            for (String alias : category.getAliases().split(",")) {
                addTerm(terms, alias);
            }
        }
        return new ArrayList<String>(terms);
    }

    private void addTerm(Set<String> terms, String value) {
        String term = normalize(value);
        if (StrUtil.isNotBlank(term)) {
            terms.add(term);
        }
    }

    private String normalize(String value) {
        return StrUtil.nullToEmpty(value).toLowerCase(Locale.ROOT).trim();
    }
}
