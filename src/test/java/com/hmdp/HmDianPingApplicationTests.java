package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@SpringBootTest
class HmDianPingApplicationTests {
    @Resource
    private ShopServiceImpl shopService;

    @Resource
    private CacheClient cacheClient;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    //创建线程池，500个线程
    private ExecutorService es = Executors.newFixedThreadPool(500);

    @Test
    void testIdWorker() throws InterruptedException {
        int countDown = 300;
        //使用CountDownLatch让线程同步等待
        CountDownLatch countDownLatch = new CountDownLatch(countDown);
        //设置每个线程内的任务：生成100个id并打印
        Runnable task = () -> {
            for (int i = 0; i < 100; i++) {
                long id = redisIdWorker.nextId("order");
                System.out.println("id = " + id);
            }
            countDownLatch.countDown();
        };
        long begin = System.currentTimeMillis();
        //创建300个线程，每个线程生成100个id，一共生成30000个id
        for (int i = 0; i < countDown; i++) {
            es.submit(task);
        }
        countDownLatch.await();
        long end = System.currentTimeMillis();
        System.out.println("time = " + (end - begin));
    }

    @Test
    void saveShopTest() {
        Shop shop = shopService.getById(1L);
        cacheClient.setWithLogicalExpireTime(RedisConstants.CACHE_SHOP_KEY + 1L, shop, 10L, TimeUnit.SECONDS);
    }

    @Test
    void loadShopLocationDara() {
        //查询店铺信息
        List<Shop> shopList = shopService.list();
        //把店铺按分类存进map
        Map<Long, List<Shop>> map = shopList.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        //把店铺信息存进redis
        map.forEach((typeId, shops) -> {
            String key = RedisConstants.SHOP_GEO_KEY + typeId;
            //把店铺id和经纬度存进redis
            List<RedisGeoCommands.GeoLocation<String>> locations = shops.stream()
                    .map(shop -> new RedisGeoCommands.GeoLocation<>(shop.getId().toString(), new Point(shop.getX(), shop.getY())))
                    .collect(Collectors.toList());
            stringRedisTemplate.opsForGeo().add(key, locations);
        });
    }
}
