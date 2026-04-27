package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import com.hmdp.ai.rag.LocalLifeRagService;
import com.hmdp.config.AiProperties;
import com.hmdp.dto.AiRetrievalHit;
import com.hmdp.service.IAiKnowledgeService;
import jakarta.annotation.Resource;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Service
public class AiKnowledgeServiceImpl implements IAiKnowledgeService {

    private static final int DEFAULT_TOP_K = 4;

    @Resource
    private LocalLifeRagService localLifeRagService;

    @Resource
    private AiProperties aiProperties;

    /**
     * 从本地向量知识库中检索背景知识。
     * 当前只承载静态规则和说明性内容，不承载动态业务事实。
     */
    @Override
    public List<AiRetrievalHit> retrieve(String query, Integer topK) {
        if (StrUtil.isBlank(query)) {
            return Collections.emptyList();
        }
        int limit = topK == null ? DEFAULT_TOP_K : Math.max(1, Math.min(topK, 8));
        return retrieveVectorHits(query, limit);
    }

    /**
     * 把向量检索结果转换成前端和 Agent 都更容易消费的命中结构。
     */
    private List<AiRetrievalHit> retrieveVectorHits(String query, int limit) {
        List<Document> documents = localLifeRagService.search(query, limit);
        if (documents == null || documents.isEmpty()) {
            return Collections.emptyList();
        }
        List<Document> reranked = rerankDocuments(documents);
        List<AiRetrievalHit> hits = new ArrayList<>(documents.size());
        for (Document document : reranked) {
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
                    title == null ? "Untitled" : String.valueOf(title),
                    abbreviate(document.getText(), 140),
                    document.getScore() == null ? 1.5D : document.getScore() + 1.5D
            ));
        }
        return hits;
    }

    /**
     * 对博客类命中补一层本地重排：
     * 语义分仍然是主体，但会适度偏向更近、更热的体验内容。
     */
    private List<Document> rerankDocuments(List<Document> documents) {
        List<Document> working = new ArrayList<>(documents);
        int candidateLimit = aiProperties.getBlogRag().getRerankCandidateLimit();
        if (candidateLimit <= 0 || working.size() <= candidateLimit) {
            working.sort(Comparator.comparingDouble(this::computeRerankScore).reversed());
            return working;
        }
        List<Document> head = new ArrayList<>(working.subList(0, candidateLimit));
        List<Document> tail = new ArrayList<>(working.subList(candidateLimit, working.size()));
        head.sort(Comparator.comparingDouble(this::computeRerankScore).reversed());
        head.addAll(tail);
        return head;
    }

    private double computeRerankScore(Document document) {
        double baseScore = document.getScore() == null ? 0D : document.getScore();
        Map<String, Object> metadata = document.getMetadata();
        if (!"blog".equals(String.valueOf(metadata.get("sourceType")))) {
            return baseScore;
        }
        double freshnessScore = resolveFreshnessScore(metadata.get("createTime"));
        double heatScore = resolveHeatScore(metadata.get("liked"));
        return baseScore + freshnessScore * 0.25D + heatScore * 0.15D;
    }
    private double resolveFreshnessScore(Object createTime) {
        if (createTime == null) {
            return 0D;
        }
        try {
            LocalDateTime publishedAt = LocalDateTime.parse(String.valueOf(createTime));
            long days = ChronoUnit.DAYS.between(publishedAt, LocalDateTime.now());
            return Math.max(0D, Math.exp(-0.015D * Math.max(days, 0)));
        } catch (Exception ignore) {
            return 0D;
        }
    }

    private double resolveHeatScore(Object liked) {
        if (!(liked instanceof Number number)) {
            return 0D;
        }
        return Math.min(Math.log1p(Math.max(number.doubleValue(), 0D)) / 6D, 1D);
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...";
    }
}
