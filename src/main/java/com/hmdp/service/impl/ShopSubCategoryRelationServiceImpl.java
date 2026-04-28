package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.entity.ShopSubCategoryRelation;
import com.hmdp.mapper.ShopSubCategoryRelationMapper;
import com.hmdp.service.IShopSubCategoryRelationService;
import org.springframework.stereotype.Service;

/**
 * 店铺细分类关联服务实现。
 */
@Service
public class ShopSubCategoryRelationServiceImpl
        extends ServiceImpl<ShopSubCategoryRelationMapper, ShopSubCategoryRelation>
        implements IShopSubCategoryRelationService {
}
