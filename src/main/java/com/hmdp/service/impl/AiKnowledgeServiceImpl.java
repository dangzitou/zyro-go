package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import com.hmdp.ai.rag.LocalLifeRagService;
import com.hmdp.dto.AiRetrievalHit;
import com.hmdp.service.IAiKnowledgeService;
import jakarta.annotation.Resource;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Service
public class AiKnowledgeServiceImpl implements IAiKnowledgeService {

    private static final int DEFAULT_TOP_K = 4;

    @Resource
    private LocalLifeRagService localLifeRagService;

    @Override
    public List<AiRetrievalHit> retrieve(String query, Integer topK) {
        if (StrUtil.isBlank(query)) {
            return Collections.emptyList();
        }
        int limit = topK == null ? DEFAULT_TOP_K : Math.max(1, Math.min(topK, 8));
        return retrieveVectorHits(query, limit);
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
                    title == null ? "Untitled" : String.valueOf(title),
                    abbreviate(document.getText(), 140),
                    document.getScore() == null ? 1.5D : document.getScore() + 1.5D
            ));
        }
        return hits;
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...";
    }
}
