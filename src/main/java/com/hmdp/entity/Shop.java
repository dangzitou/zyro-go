package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * <p>
 * 
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_shop")
public class Shop implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 商铺名称
     */
    private String name;

    /**
     * 商铺类型的id
     */
    private Long typeId;

    /**
     * 主子分类 id。
     * 用于高频筛选、排序和统计，详细多标签关系仍然落在 tb_shop_sub_category_relation。
     */
    private Long primarySubCategoryId;

    /**
     * 商铺图片，多个图片以','隔开
     */
    private String images;

    /**
     * 商圈，例如陆家嘴
     */
    private String area;

    /**
     * 地址
     */
    private String address;

    /**
     * 经度
     */
    private Double x;

    /**
     * 维度
     */
    private Double y;

    /**
     * 均价，取整数
     */
    private Long avgPrice;

    /**
     * 销量
     */
    private Integer sold;

    /**
     * 评论数量
     */
    private Integer comments;

    /**
     * 评分，1~5分，乘10保存，避免小数
     */
    private Integer score;

    /**
     * 营业时间，例如 10:00-22:00
     */
    private String openHours;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;


    @TableField(exist = false)
    private Double distance;
}
