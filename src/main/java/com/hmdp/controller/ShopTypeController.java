package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.service.IShopTypeService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/shop-type")
public class ShopTypeController {

    @Resource
    private IShopTypeService typeService;

    /**
     * 查询店铺类型列表。
     * 一般用于首页分类和筛选项渲染。
     */
    @GetMapping("list")
    public Result queryTypeList() {
        return typeService.queryTypeList();
    }
}
