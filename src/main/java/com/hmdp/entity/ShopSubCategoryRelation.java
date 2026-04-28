package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 店铺与细分类的关联。
 * 一家店可以命中多个细分类，例如“烤肉火锅”可以同时归到烧烤和火锅。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_shop_sub_category_relation")
public class ShopSubCategoryRelation implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键。
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 店铺 id，对应 tb_shop.id。
     */
    private Long shopId;

    /**
     * 细分类 id，对应 tb_shop_sub_category.id。
     */
    private Long subCategoryId;

    /**
     * 是否主细分类：1 是，0 否。
     */
    private Integer isPrimary;

    /**
     * 关联来源：manual/import/rule/ai。
     */
    private String source;

    /**
     * 规则或模型判断的置信度。
     */
    private BigDecimal confidence;

    /**
     * 创建时间。
     */
    private LocalDateTime createTime;

    /**
     * 更新时间。
     */
    private LocalDateTime updateTime;
}
