package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 店铺细分类字典。
 * 例如“美食”下面继续拆成火锅、海鲜、咖啡等，Agent 解析用户意图时优先读这张表。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_shop_sub_category")
public class ShopSubCategory implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键。
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 所属一级分类 id，对应 tb_shop_type.id。
     */
    private Long parentTypeId;

    /**
     * 稳定编码，便于配置、导入和排查问题。
     */
    private String code;

    /**
     * 展示名称，例如“海鲜”“量贩KTV”。
     */
    private String name;

    /**
     * 逗号分隔的同义词和常见叫法，用于轻量意图识别。
     */
    private String aliases;

    /**
     * 同一大类下的展示排序。
     */
    private Integer sort;

    /**
     * 状态：1 启用，0 停用。
     */
    private Integer status;

    /**
     * 创建时间。
     */
    private LocalDateTime createTime;

    /**
     * 更新时间。
     */
    private LocalDateTime updateTime;
}
