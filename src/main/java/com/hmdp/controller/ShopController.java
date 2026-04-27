package com.hmdp.controller;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import com.hmdp.utils.SystemConstants;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/shop")
public class ShopController {

    @Resource
    public IShopService shopService;

    /**
     * 根据店铺 id 查询店铺详情。
     */
    @GetMapping("/{id}")
    public Result queryShopById(@PathVariable("id") Long id) {
        return shopService.queryShopById(id);
    }

    /**
     * 新增店铺。
     */
    @PostMapping
    public Result saveShop(@RequestBody Shop shop) {
        shopService.save(shop);
        return Result.ok(shop.getId());
    }

    /**
     * 更新店铺信息。
     */
    @PutMapping
    public Result updateShop(@RequestBody Shop shop) {
        return shopService.update(shop);
    }

    /**
     * 按店铺类型分页查询店铺。
     * 传入经纬度时，会优先按附近距离排序。
     */
    @GetMapping("/of/type")
    public Result queryShopByType(@RequestParam("typeId") Integer typeId,
                                  @RequestParam(value = "current", defaultValue = "1") Integer current,
                                  @RequestParam(value = "x", required = false) Double x,
                                  @RequestParam(value = "y", required = false) Double y) {
        return shopService.queryShopByType(typeId, current, x, y);
    }

    /**
     * 按店铺名称模糊分页查询店铺。
     */
    @GetMapping("/of/name")
    public Result queryShopByName(@RequestParam(value = "name", required = false) String name,
                                  @RequestParam(value = "current", defaultValue = "1") Integer current) {
        Page<Shop> page = shopService.query()
                .like(StrUtil.isNotBlank(name), "name", name)
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        return Result.ok(page.getRecords());
    }
}
