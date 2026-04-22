package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  鏈嶅姟瀹炵幇绫?
 * </p>
 *
 * @author 铏庡摜
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;


    /**
     * 鏍规嵁id鏌ヨ鍟嗛摵淇℃伅
     * @param id 鍟嗛摵id
     * @return 鍟嗛摵璇︽儏鏁版嵁
     */
    @Override
    public Result queryShopById(Long id) {
        //瑙ｅ喅缂撳瓨绌块€?
        //Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //浜掓枼閿佽В鍐崇紦瀛樺嚮绌?
        Shop shop = cacheClient.queryWithMutex(CACHE_SHOP_KEY, LOCK_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //閫昏緫杩囨湡鏃堕棿瑙ｅ喅缂撳瓨鍑荤┛
        //Shop shop = cacheClient.queryWithLogicalExpireTime(CACHE_SHOP_KEY, LOCK_SHOP_KEY, id, Shop.class, this::getById, 20L, TimeUnit.SECONDS);
        if(shop == null){
            return Result.fail("商铺不存在");
        }
        return Result.ok(shop);
    }

    /**
     * 鏇存柊鍟嗛摵淇℃伅
     * @param shop 鍟嗛摵鏁版嵁
     * @return 鏃?
     */
    @Override
    @Transactional
    public Result update(Shop shop) {
        //鏍￠獙鍟嗛摵id
        Long id = shop.getId();
        if(id == null){
            return Result.fail("鍟嗛摵id涓嶈兘涓虹┖");
        }
        //1.鏇存柊鏁版嵁搴?
        updateById(shop);
        //2.鍒犻櫎缂撳瓨
        String key = CACHE_SHOP_KEY + shop.getId();
        stringRedisTemplate.delete(key);
        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        //鍒ゆ柇鏄惁闇€瑕佹牴鎹潗鏍囨煡璇?
        if(x == null || y == null){
            //涓嶉渶瑕佸潗鏍囨煡璇紝鎸夌収鏁版嵁搴撴煡璇?
            Page<Shop> page = query().eq("type_id", typeId).page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            return Result.ok(page);
        }
        //璁＄畻鍒嗛〉鍙傛暟
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;
        //鏌ヨredis锛屾寜鐓ц窛绂绘帓搴忥紝鍒嗛〉銆傜粨鏋滐細shopId銆乨istance
        String key = SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo().search(
                key,
                GeoReference.fromCoordinate(x, y),
                new Distance(5000),
                RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
        );
        //鍒ゆ柇鏄惁鏈夌粨鏋?
        if(results == null){
            return Result.ok(Collections.emptyList());
        }
        //鍒ゆ柇鍒嗛〉鏄惁鏈夋暟鎹?
        if(results.getContent().size() <= from){
            return Result.ok(Collections.emptyList());
        }
        //瑙ｆ瀽鍑篿d
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent().stream().skip(from).collect(Collectors.toList());
        List<Long> ids = list.stream().map(
                result -> Long.parseLong(result.getContent().getName())).collect(Collectors.toList()
        );
        //鏍规嵁id鏌ヨshop
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (int i = 0; i < shops.size(); i++) {
            //鍙栧嚭璺濈
            GeoResult<RedisGeoCommands.GeoLocation<String>> result = list.get(i);
            Distance distance = result.getDistance();
            shops.get(i).setDistance(distance.getValue());
        }
        return Result.ok(shops);
    }
}

