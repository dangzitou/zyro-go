package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.dto.ShopRecommendationDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Shop;
import com.hmdp.entity.Voucher;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IShopService;
import com.hmdp.service.IVoucherService;
import org.junit.jupiter.api.Test;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.GeoOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ShopRecommendationServiceImplTest {

    @Test
    void shouldFilterByCouponAndKeepBestCandidateFirst() {
        IShopService shopService = mock(IShopService.class);
        IVoucherService voucherService = mock(IVoucherService.class);
        IBlogService blogService = mock(IBlogService.class);
        QueryChainWrapper<Shop> shopQuery = mock(QueryChainWrapper.class);
        QueryChainWrapper<Blog> blogQuery1 = mock(QueryChainWrapper.class);
        QueryChainWrapper<Blog> blogQuery2 = mock(QueryChainWrapper.class);

        Shop couponShop = new Shop();
        couponShop.setId(1L);
        couponShop.setName("辣府火锅");
        couponShop.setArea("静安寺");
        couponShop.setAddress("南京西路");
        couponShop.setAvgPrice(88L);
        couponShop.setScore(49);
        couponShop.setComments(520);
        couponShop.setX(121.45);
        couponShop.setY(31.22);

        Shop noCouponShop = new Shop();
        noCouponShop.setId(2L);
        noCouponShop.setName("深夜烧烤");
        noCouponShop.setArea("徐家汇");
        noCouponShop.setAddress("虹桥路");
        noCouponShop.setAvgPrice(76L);
        noCouponShop.setScore(46);
        noCouponShop.setComments(120);
        noCouponShop.setX(121.44);
        noCouponShop.setY(31.21);
        noCouponShop.setTypeId(1L);

        Voucher voucher = new Voucher();
        voucher.setId(100L);
        voucher.setTitle("火锅双人套餐");
        voucher.setPayValue(128L);
        voucher.setActualValue(188L);

        Blog hotBlog = new Blog();
        hotBlog.setId(200L);
        hotBlog.setTitle("这家火锅值得冲");
        hotBlog.setContent("食材稳定，券后价格友好。");
        hotBlog.setLiked(320);
        hotBlog.setCreateTime(LocalDateTime.now().minusDays(1));
        hotBlog.setShopId(1L);

        when(shopService.query()).thenReturn(shopQuery);
        when(shopQuery.like(anyBoolean(), any(), any())).thenReturn(shopQuery);
        when(shopQuery.eq(anyBoolean(), any(), any())).thenReturn(shopQuery);
        when(shopQuery.le(anyBoolean(), any(), any())).thenReturn(shopQuery);
        when(shopQuery.last(anyString())).thenReturn(shopQuery);
        when(shopQuery.list()).thenReturn(List.of(couponShop, noCouponShop));

        when(voucherService.queryVoucherOfShop(1L)).thenReturn(Result.ok(List.of(voucher)));
        when(voucherService.queryVoucherOfShop(2L)).thenReturn(Result.ok(List.of()));
        when(blogService.query()).thenReturn(blogQuery1, blogQuery2);
        when(blogQuery1.eq("shop_id", 1L)).thenReturn(blogQuery1);
        when(blogQuery1.orderByDesc("liked")).thenReturn(blogQuery1);
        when(blogQuery1.orderByDesc("create_time")).thenReturn(blogQuery1);
        when(blogQuery1.last(anyString())).thenReturn(blogQuery1);
        when(blogQuery1.list()).thenReturn(List.of(hotBlog));

        when(blogQuery2.eq("shop_id", 2L)).thenReturn(blogQuery2);
        when(blogQuery2.orderByDesc("liked")).thenReturn(blogQuery2);
        when(blogQuery2.orderByDesc("create_time")).thenReturn(blogQuery2);
        when(blogQuery2.last(anyString())).thenReturn(blogQuery2);
        when(blogQuery2.list()).thenReturn(List.of());

        ShopRecommendationServiceImpl service = new ShopRecommendationServiceImpl();
        ReflectionTestUtils.setField(service, "shopService", shopService);
        ReflectionTestUtils.setField(service, "voucherService", voucherService);
        ReflectionTestUtils.setField(service, "blogService", blogService);

        List<ShopRecommendationDTO> recommendations = service.recommendShops("火锅", 1, 120L, 121.46, 31.23, true, 5);

        assertFalse(recommendations.isEmpty());
        assertEquals(1L, recommendations.get(0).getShopId());
        assertEquals(1, recommendations.size());
    }

    @Test
    void shouldPreferGeoCandidatesAndMatchKeywordAgainstAddress() {
        IShopService shopService = mock(IShopService.class);
        IVoucherService voucherService = mock(IVoucherService.class);
        IBlogService blogService = mock(IBlogService.class);
        StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        GeoOperations<String, String> geoOperations = mock(GeoOperations.class);
        QueryChainWrapper<Shop> shopQuery = mock(QueryChainWrapper.class);
        QueryChainWrapper<Blog> blogQuery = mock(QueryChainWrapper.class);

        Shop nearbyShop = new Shop();
        nearbyShop.setId(10L);
        nearbyShop.setName("天河精品咖啡");
        nearbyShop.setArea("天河区");
        nearbyShop.setAddress("广州市天河区枫叶路88号");
        nearbyShop.setAvgPrice(42L);
        nearbyShop.setScore(48);
        nearbyShop.setComments(260);
        nearbyShop.setTypeId(1L);

        GeoResults<RedisGeoCommands.GeoLocation<String>> geoResults = new GeoResults<>(List.of(
                new GeoResult<>(new RedisGeoCommands.GeoLocation<>("10", new Point(113.33D, 23.13D)), new Distance(320D))
        ));

        when(stringRedisTemplate.opsForGeo()).thenReturn(geoOperations);
        when(geoOperations.search(anyString(), any(), any(Distance.class), any(RedisGeoCommands.GeoSearchCommandArgs.class)))
                .thenReturn(geoResults);
        when(shopService.query()).thenReturn(shopQuery);
        when(shopQuery.in(anyString(), any(List.class))).thenReturn(shopQuery);
        when(shopQuery.like(anyBoolean(), any(), any())).thenReturn(shopQuery);
        when(shopQuery.eq(anyBoolean(), any(), any())).thenReturn(shopQuery);
        when(shopQuery.le(anyBoolean(), any(), any())).thenReturn(shopQuery);
        when(shopQuery.last(anyString())).thenReturn(shopQuery);
        when(shopQuery.list()).thenReturn(List.of(nearbyShop));

        when(voucherService.queryVoucherOfShop(10L)).thenReturn(Result.ok(List.of()));
        when(blogService.query()).thenReturn(blogQuery);
        when(blogQuery.eq("shop_id", 10L)).thenReturn(blogQuery);
        when(blogQuery.orderByDesc("liked")).thenReturn(blogQuery);
        when(blogQuery.orderByDesc("create_time")).thenReturn(blogQuery);
        when(blogQuery.last(anyString())).thenReturn(blogQuery);
        when(blogQuery.list()).thenReturn(List.of());

        ShopRecommendationServiceImpl service = new ShopRecommendationServiceImpl();
        ReflectionTestUtils.setField(service, "shopService", shopService);
        ReflectionTestUtils.setField(service, "voucherService", voucherService);
        ReflectionTestUtils.setField(service, "blogService", blogService);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", stringRedisTemplate);

        List<ShopRecommendationDTO> recommendations = service.recommendShops("天河区", 1, 80L, 113.33, 23.13, false, 5);

        assertEquals(1, recommendations.size());
        assertEquals(10L, recommendations.get(0).getShopId());
        assertEquals(320D, recommendations.get(0).getDistanceMeters());
        assertTrue(recommendations.get(0).getReasonTags().contains("keyword_match"));
    }
}
