package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import com.hmdp.ai.rag.LocalLifeRagService;
import com.hmdp.dto.AiRetrievalHit;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Shop;
import com.hmdp.entity.Voucher;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IAiKnowledgeService;
import com.hmdp.service.IShopService;
import com.hmdp.service.IVoucherService;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class AiKnowledgeServiceImpl implements IAiKnowledgeService {

    private static final int DEFAULT_TOP_K = 4;
    private static final int MAX_CANDIDATES = 30;

    @Resource
    private IShopService shopService;

    @Resource
    private IVoucherService voucherService;

    @Resource
    private IBlogService blogService;

    @Resource
    private LocalLifeRagService localLifeRagService;

    @Override
    public List<AiRetrievalHit> retrieve(String query, Integer topK) {
        if (StrUtil.isBlank(query)) {
            return Collections.emptyList();
        }
        int limit = topK == null ? DEFAULT_TOP_K : Math.max(1, Math.min(topK, 8));
        List<String> tokens = tokenize(query);
        Map<String, AiRetrievalHit> dedupedHits = new LinkedHashMap<>();

        for (AiRetrievalHit hit : retrieveVectorHits(query, limit)) {
            dedupedHits.put(hit.getSourceType() + ":" + hit.getSourceId(), hit);
        }
        for (AiRetrievalHit hit : retrieveShops(tokens)) {
            dedupedHits.putIfAbsent(hit.getSourceType() + ":" + hit.getSourceId(), hit);
        }
        for (AiRetrievalHit hit : retrieveVouchers(tokens)) {
            dedupedHits.putIfAbsent(hit.getSourceType() + ":" + hit.getSourceId(), hit);
        }
        for (AiRetrievalHit hit : retrieveBlogs(tokens)) {
            dedupedHits.putIfAbsent(hit.getSourceType() + ":" + hit.getSourceId(), hit);
        }

        List<AiRetrievalHit> hits = new ArrayList<>(dedupedHits.values());
        hits.sort(Comparator.comparing(AiRetrievalHit::getScore).reversed());
        return hits.size() > limit ? new ArrayList<>(hits.subList(0, limit)) : hits;
    }

    private List<AiRetrievalHit> retrieveVectorHits(String query, int limit) {
        List<Document> documents = localLifeRagService.search(query, limit);
        if (documents == null || documents.isEmpty()) {
            return Collections.emptyList();
        }
        List<AiRetrievalHit> hits = new ArrayList<>(documents.size());
        for (Document document : documents) {
            Map<String, Object> metadata = document.getMetadata();
            Object sourceId = metadata.get("sourceId");
            Object sourceType = metadata.get("sourceType");
            Object title = metadata.get("title");
            Long resolvedSourceId = sourceId instanceof Number number ? number.longValue() : null;
            if (resolvedSourceId == null || sourceType == null) {
                continue;
            }
            hits.add(new AiRetrievalHit(
                    String.valueOf(sourceType),
                    resolvedSourceId,
                    title == null ? "未命名" : String.valueOf(title),
                    abbreviate(document.getText(), 140),
                    document.getScore() == null ? 1.5D : document.getScore() + 1.5D
            ));
        }
        return hits;
    }

    private List<AiRetrievalHit> retrieveShops(List<String> tokens) {
        List<Shop> shops = shopService.query().last("LIMIT " + MAX_CANDIDATES).list();
        List<AiRetrievalHit> hits = new ArrayList<AiRetrievalHit>();
        if (shops == null) {
            return hits;
        }
        for (Shop shop : shops) {
            String text = join(shop.getName(), shop.getArea(), shop.getAddress(), shop.getOpenHours());
            double score = score(tokens, text);
            if (score <= 0D) {
                continue;
            }
            String snippet = "店铺=" + safe(shop.getName())
                    + "，商圈=" + safe(shop.getArea())
                    + "，地址=" + safe(shop.getAddress())
                    + "，均价=" + safe(shop.getAvgPrice())
                    + "，评分=" + (shop.getScore() == null ? "未知" : shop.getScore() / 10.0)
                    + "，营业时间=" + safe(shop.getOpenHours());
            hits.add(new AiRetrievalHit("shop", shop.getId(), shop.getName(), snippet, score + 1.2D));
        }
        return hits;
    }

    private List<AiRetrievalHit> retrieveVouchers(List<String> tokens) {
        List<Voucher> vouchers = voucherService.query().last("LIMIT " + MAX_CANDIDATES).list();
        List<AiRetrievalHit> hits = new ArrayList<AiRetrievalHit>();
        if (vouchers == null) {
            return hits;
        }
        for (Voucher voucher : vouchers) {
            String text = join(voucher.getTitle(), voucher.getSubTitle(), voucher.getRules());
            double score = score(tokens, text);
            if (score <= 0D) {
                continue;
            }
            String snippet = "券=" + safe(voucher.getTitle())
                    + "，副标题=" + safe(voucher.getSubTitle())
                    + "，支付=" + safe(voucher.getPayValue())
                    + "，抵扣=" + safe(voucher.getActualValue())
                    + "，规则=" + safe(voucher.getRules());
            hits.add(new AiRetrievalHit("voucher", voucher.getId(), voucher.getTitle(), snippet, score + 0.8D));
        }
        return hits;
    }

    private List<AiRetrievalHit> retrieveBlogs(List<String> tokens) {
        List<Blog> blogs = blogService.query().orderByDesc("liked").last("LIMIT " + MAX_CANDIDATES).list();
        List<AiRetrievalHit> hits = new ArrayList<AiRetrievalHit>();
        if (blogs == null) {
            return hits;
        }
        for (Blog blog : blogs) {
            String text = join(blog.getTitle(), blog.getContent(), blog.getName());
            double score = score(tokens, text);
            if (score <= 0D) {
                continue;
            }
            String snippet = "标题=" + safe(blog.getTitle())
                    + "，内容=" + abbreviate(safe(blog.getContent()), 120)
                    + "，点赞=" + safe(blog.getLiked())
                    + "，店铺ID=" + safe(blog.getShopId());
            hits.add(new AiRetrievalHit("blog", blog.getId(), blog.getTitle(), snippet, score + 0.5D));
        }
        return hits;
    }

    private List<String> tokenize(String query) {
        List<String> tokens = new ArrayList<String>();
        String normalized = query.toLowerCase(Locale.ROOT)
                .replace('，', ' ')
                .replace('。', ' ')
                .replace(',', ' ')
                .replace('.', ' ')
                .replace('？', ' ')
                .replace('！', ' ')
                .replace('!', ' ');
        for (String part : normalized.split("\\s+")) {
            if (StrUtil.isBlank(part)) {
                continue;
            }
            tokens.add(part.trim());
        }
        if (tokens.isEmpty()) {
            tokens.add(normalized.trim());
        }
        return tokens;
    }

    private double score(List<String> tokens, String text) {
        if (StrUtil.isBlank(text)) {
            return 0D;
        }
        String haystack = text.toLowerCase(Locale.ROOT);
        double score = 0D;
        for (String token : tokens) {
            if (StrUtil.isBlank(token)) {
                continue;
            }
            if (haystack.contains(token)) {
                score += token.length() >= 2 ? 1.0D : 0.3D;
            }
        }
        return score;
    }

    private String join(Object... values) {
        StringBuilder builder = new StringBuilder();
        for (Object value : values) {
            if (value == null) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(value);
        }
        return builder.toString();
    }

    private String safe(Object value) {
        return value == null ? "未知" : String.valueOf(value);
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...";
    }
}

