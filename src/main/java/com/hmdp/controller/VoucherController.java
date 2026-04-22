package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.entity.Voucher;
import com.hmdp.service.IVoucherService;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;

/**
 * <p>
 *  鍓嶇鎺у埗鍣?
 * </p>
 *
 * @author 铏庡摜
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/voucher")
public class VoucherController {

    @Resource
    private IVoucherService voucherService;

    /**
     * 鏂板鏅€氬埜
     * @param voucher 浼樻儬鍒镐俊鎭?
     * @return 浼樻儬鍒竔d
     */
    @PostMapping
    public Result addVoucher(@RequestBody Voucher voucher) {
        voucherService.save(voucher);
        return Result.ok(voucher.getId());
    }

    /**
     * 鏂板绉掓潃鍒?
     * @param voucher 浼樻儬鍒镐俊鎭紝鍖呭惈绉掓潃淇℃伅
     * @return 浼樻儬鍒竔d
     */
    @PostMapping("seckill")
    public Result addSeckillVoucher(@RequestBody Voucher voucher) {
        voucherService.addSeckillVoucher(voucher);
        return Result.ok(voucher.getId());
    }

    /**
     * 鏌ヨ搴楅摵鐨勪紭鎯犲埜鍒楄〃
     * @param shopId 搴楅摵id
     * @return 浼樻儬鍒稿垪琛?
     */
    @GetMapping("/list/{shopId}")
    public Result queryVoucherOfShop(@PathVariable("shopId") Long shopId) {
       return voucherService.queryVoucherOfShop(shopId);
    }
}

