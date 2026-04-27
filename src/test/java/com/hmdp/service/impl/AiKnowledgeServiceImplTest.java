package com.hmdp.service.impl;

import com.hmdp.ai.rag.LocalLifeRagService;
import com.hmdp.config.AiProperties;
import com.hmdp.dto.AiRetrievalHit;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiKnowledgeServiceImplTest {

    @Test
    void shouldConvertVectorDocumentsToRetrievalHits() {
        LocalLifeRagService ragService = mock(LocalLifeRagService.class);
        when(ragService.search(anyString(), anyInt())).thenReturn(List.of(
                Document.builder()
                        .id("support:1001:0")
                        .text("当前采用手机号验证码登录，开发环境下验证码可能只写入 Redis 或日志。")
                        .metadata(Map.of(
                                "sourceType", "support",
                                "sourceId", 1001L,
                                "title", "用户登录与验证码说明"
                        ))
                        .score(0.93D)
                        .build()
        ));

        AiKnowledgeServiceImpl service = new AiKnowledgeServiceImpl();
        ReflectionTestUtils.setField(service, "localLifeRagService", ragService);
        ReflectionTestUtils.setField(service, "aiProperties", new AiProperties());

        List<AiRetrievalHit> hits = service.retrieve("为什么收不到验证码", 4);

        assertEquals(1, hits.size());
        assertEquals("support", hits.get(0).getSourceType());
        assertEquals(1001L, hits.get(0).getSourceId());
        assertEquals("用户登录与验证码说明", hits.get(0).getTitle());
        assertTrue(hits.get(0).getSnippet().contains("验证码"));
    }

    @Test
    void shouldPreferRecentAndHotBlogHitsDuringRerank() {
        LocalLifeRagService ragService = mock(LocalLifeRagService.class);
        when(ragService.search(anyString(), anyInt())).thenReturn(List.of(
                Document.builder()
                        .id("blog:11:0")
                        .text("老博客，但语义分更高。")
                        .metadata(Map.of(
                                "sourceType", "blog",
                                "sourceId", 11L,
                                "title", "老探店",
                                "liked", 20,
                                "createTime", "2025-01-01T10:00:00"
                        ))
                        .score(0.92D)
                        .build(),
                Document.builder()
                        .id("blog:12:0")
                        .text("较新的博客，点赞也更高。")
                        .metadata(Map.of(
                                "sourceType", "blog",
                                "sourceId", 12L,
                                "title", "新探店",
                                "liked", 220,
                                "createTime", "2026-04-20T10:00:00"
                        ))
                        .score(0.83D)
                        .build()
        ));

        AiKnowledgeServiceImpl service = new AiKnowledgeServiceImpl();
        ReflectionTestUtils.setField(service, "localLifeRagService", ragService);
        ReflectionTestUtils.setField(service, "aiProperties", new AiProperties());

        List<AiRetrievalHit> hits = service.retrieve("适合约会的环境好的店", 4);

        assertEquals(2, hits.size());
        assertEquals(12L, hits.get(0).getSourceId());
        assertEquals("新探店", hits.get(0).getTitle());
    }
}
