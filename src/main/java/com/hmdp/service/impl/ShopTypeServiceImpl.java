package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;
import static com.hmdp.utils.RedisConstants.CACHE_TYPE_LIST;

/**
 * <p>
 *  鏈嶅姟瀹炵幇绫?
 * </p>
 *
 * @author 铏庡摜
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    /**
     * 鏌ヨ鍟嗛摵绫诲瀷鍒楄〃
     * @return 鍟嗛摵绫诲瀷鍒楄〃
     */
    /**
     * 查询店铺类型列表。
     * 优先从 Redis List 读取，缓存未命中时再查数据库并回填缓存。
     */
    @Override
    public Result queryTypeList() {
        String key = CACHE_TYPE_LIST;
        //1.浠巖edis涓煡璇㈠晢閾虹被鍨嬬紦瀛?
        List<String> typeList = stringRedisTemplate.opsForList().range(key, 0, -1);
        //2.濡傛灉瀛樺湪锛岀洿鎺ヨ繑鍥?
        if(typeList != null && !typeList.isEmpty()){
            //灏唗ypeList杞崲涓篖ist<ShopType>骞惰繑鍥?
            List<ShopType> shopTypes = typeList.stream()
                    .map(json -> JSONUtil.toBean(json, ShopType.class))
                    .collect(Collectors.toList());
            return Result.ok(shopTypes);
        }
        //3.涓嶅瓨鍦紝鏌ヨ鏁版嵁搴?
        List<ShopType> shopTypeList = query().orderByAsc("sort").list();
        //4.涓嶅瓨鍦紝杩斿洖鍟嗛摵绫诲瀷涓嶅瓨鍦?
        if(shopTypeList == null || shopTypeList.isEmpty()){
            return Result.fail("商铺类型不存在");
        }
        //5.瀛樺湪锛屽啓鍏edis缂撳瓨
        List<String> jsonList = shopTypeList.stream()
                .map(JSONUtil::toJsonStr)
                .collect(Collectors.toList());
        stringRedisTemplate.opsForList().rightPushAll(key, jsonList);
        stringRedisTemplate.expire(key, CACHE_SHOP_TTL, TimeUnit.HOURS);
        //6.杩斿洖
        return Result.ok(shopTypeList);
    }
}

