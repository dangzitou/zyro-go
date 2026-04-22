package com.hmdp.service.impl;

import com.hmdp.ai.rag.LocalLifeRagService;
import com.hmdp.dto.AiRetrievalHit;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Shop;
import com.hmdp.entity.Voucher;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IShopService;
import com.hmdp.service.IVoucherService;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiKnowledgeServiceImplTest {

    @Test
    void shouldRetrieveShopVoucherAndBlogHitsForKeyword() {
        IShopService shopService = mock(IShopService.class);
        IVoucherService voucherService = mock(IVoucherService.class);
        IBlogService blogService = mock(IBlogService.class);
        LocalLifeRagService ragService = mock(LocalLifeRagService.class);
        QueryChainWrapper<Shop> shopQuery = mock(QueryChainWrapper.class);
        QueryChainWrapper<Voucher> voucherQuery = mock(QueryChainWrapper.class);
        QueryChainWrapper<Blog> blogQuery = mock(QueryChainWrapper.class);

        Shop shop = new Shop();
        shop.setId(1L);
        shop.setName("海底捞火锅");
        shop.setArea("陆家嘴");
        shop.setAddress("世纪大道");
        shop.setAvgPrice(98L);
        shop.setScore(48);
        shop.setOpenHours("10:00-22:00");

        Voucher voucher = new Voucher();
        voucher.setId(11L);
        voucher.setTitle("火锅双人餐");
        voucher.setSubTitle("工作日可用");
        voucher.setRules("需提前预约");
        voucher.setPayValue(128L);
        voucher.setActualValue(188L);

        Blog blog = new Blog();
        blog.setId(21L);
        blog.setTitle("火锅探店");
        blog.setContent("这家火锅店环境和食材都很稳定。");
        blog.setLiked(256);
        blog.setShopId(1L);

        when(shopService.query()).thenReturn(shopQuery);
        when(shopQuery.last(anyString())).thenReturn(shopQuery);
        when(shopQuery.list()).thenReturn(List.of(shop));

        when(voucherService.query()).thenReturn(voucherQuery);
        when(voucherQuery.last(anyString())).thenReturn(voucherQuery);
        when(voucherQuery.list()).thenReturn(List.of(voucher));

        when(blogService.query()).thenReturn(blogQuery);
        when(blogQuery.orderByDesc("liked")).thenReturn(blogQuery);
        when(blogQuery.last(anyString())).thenReturn(blogQuery);
        when(blogQuery.list()).thenReturn(List.of(blog));
        when(ragService.search(anyString(), anyInt())).thenReturn(List.of());

        AiKnowledgeServiceImpl service = new AiKnowledgeServiceImpl();
        ReflectionTestUtils.setField(service, "shopService", shopService);
        ReflectionTestUtils.setField(service, "voucherService", voucherService);
        ReflectionTestUtils.setField(service, "blogService", blogService);
        ReflectionTestUtils.setField(service, "localLifeRagService", ragService);

        List<AiRetrievalHit> hits = service.retrieve("火锅", 5);

        assertFalse(hits.isEmpty());
        assertEquals("shop", hits.get(0).getSourceType());
        assertEquals(1L, hits.get(0).getSourceId());
    }
}
