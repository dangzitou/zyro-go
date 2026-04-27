package com.hmdp.ai.rag;

import java.util.Map;
import java.util.List;

/**
 * RAG 原始知识单元。
 * 一个知识单元通常对应客服 FAQ、功能说明、运营规则或 SOP 的一个主题。
 */
public record RagKnowledgeDocument(
        long sourceId,
        String sourceType,
        String title,
        String text,
        List<String> tags,
        Map<String, Object> metadata
) {
}
