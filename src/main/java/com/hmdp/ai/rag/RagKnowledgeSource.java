package com.hmdp.ai.rag;

import java.util.List;

/**
 * RAG 知识源抽象。
 * 当前可以先接 classpath JSON，后续也可以扩展到数据库、CMS 或对象存储。
 */
public interface RagKnowledgeSource {

    /**
     * 返回当前知识源下的所有知识文档。
     */
    List<RagKnowledgeDocument> loadDocuments();
}
