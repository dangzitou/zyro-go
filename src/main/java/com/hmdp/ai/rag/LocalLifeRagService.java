package com.hmdp.ai.rag;

import cn.hutool.core.util.StrUtil;
import com.hmdp.config.AiProperties;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class LocalLifeRagService {

    private final AiProperties aiProperties;
    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;
    private final List<RagKnowledgeSource> knowledgeSources;

    private volatile SimpleVectorStore vectorStore;
    private volatile boolean ready;

    public LocalLifeRagService(AiProperties aiProperties,
                               ObjectProvider<EmbeddingModel> embeddingModelProvider,
                               List<RagKnowledgeSource> knowledgeSources) {
        this.aiProperties = aiProperties;
        this.embeddingModelProvider = embeddingModelProvider;
        this.knowledgeSources = knowledgeSources == null ? List.of() : knowledgeSources;
    }

    @PostConstruct
    public void initialize() {
        if (!aiProperties.isEnabled() || !Boolean.TRUE.equals(aiProperties.getRagEnabled())) {
            return;
        }
        EmbeddingModel embeddingModel = embeddingModelProvider.getIfAvailable();
        if (embeddingModel == null) {
            log.warn("EmbeddingModel bean not found, vector RAG is disabled.");
            return;
        }

        SimpleVectorStore store = SimpleVectorStore.builder(embeddingModel).build();
        File storeFile = resolveStoreFile();
        try {
            if (storeFile.exists() && !Boolean.TRUE.equals(aiProperties.getRagRebuildOnStartup())) {
                store.load(storeFile);
                this.vectorStore = store;
                this.ready = true;
                log.info("Loaded vector store from {}", storeFile.getAbsolutePath());
                return;
            }
            if (Boolean.TRUE.equals(aiProperties.getRagRebuildOnStartup())) {
                rebuildIndex(store, storeFile);
            } else {
                log.info("Vector store file does not exist, RAG index will remain inactive until rebuilt.");
            }
        } catch (Exception e) {
            log.warn("Failed to initialize vector RAG, fallback to keyword retrieval only.", e);
        }
    }

    public boolean isReady() {
        return ready && vectorStore != null;
    }

    public VectorStore getVectorStore() {
        return isReady() ? vectorStore : null;
    }

    public List<Document> search(String query, int topK) {
        if (!isReady() || StrUtil.isBlank(query)) {
            return Collections.emptyList();
        }
        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(Math.max(1, Math.min(topK, 8)))
                .similarityThreshold(aiProperties.getRagSimilarityThreshold())
                .build();
        return vectorStore.similaritySearch(request);
    }

    public QuestionAnswerAdvisor buildAdvisor(String query, int topK) {
        if (!isReady() || StrUtil.isBlank(query)) {
            return null;
        }
        return QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(SearchRequest.builder()
                        .query(query)
                        .topK(Math.max(1, Math.min(topK, 8)))
                        .similarityThreshold(aiProperties.getRagSimilarityThreshold())
                        .build())
                .build();
    }

    public synchronized void rebuildIndex() {
        EmbeddingModel embeddingModel = embeddingModelProvider.getIfAvailable();
        if (embeddingModel == null) {
            log.warn("EmbeddingModel bean not found, skip rebuilding vector index.");
            return;
        }
        rebuildIndex(SimpleVectorStore.builder(embeddingModel).build(), resolveStoreFile());
    }

    private void rebuildIndex(SimpleVectorStore store, File storeFile) {
        List<Document> documents = buildKnowledgeDocuments();
        if (documents.isEmpty()) {
            log.warn("No knowledge documents found, vector RAG remains disabled.");
            return;
        }
        store.add(documents);
        ensureParentDirectory(storeFile.toPath());
        store.save(storeFile);
        this.vectorStore = store;
        this.ready = true;
        log.info("Rebuilt vector store with {} knowledge chunks at {}", documents.size(), storeFile.getAbsolutePath());
    }

    private File resolveStoreFile() {
        Path path = Paths.get(aiProperties.getRagStoreFile()).toAbsolutePath().normalize();
        return path.toFile();
    }

    private void ensureParentDirectory(Path filePath) {
        try {
            Path parent = filePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create vector store directory for " + filePath, e);
        }
    }

    /**
     * 当前 RAG 以两层知识组成：
     * 1. 系统级约束，确保 Agent 知道哪些内容必须走工具。
     * 2. 可维护的业务知识包，承载客服 FAQ、产品使用说明和运营 SOP。
     */
    private List<Document> buildKnowledgeDocuments() {
        List<RagKnowledgeDocument> knowledgeDocuments = new ArrayList<>();
        knowledgeDocuments.add(new RagKnowledgeDocument(
                1L,
                "guide",
                "Agent 使用约束",
                "本系统是本地生活 Agent。遇到推荐类问题时，应优先结合推荐工具、店铺工具和优惠券工具获取事实依据，而不是只依赖语言模型记忆直接回答。",
                List.of("Agent", "工具调用", "约束"),
                Map.of("audience", "internal")
        ));
        knowledgeDocuments.add(new RagKnowledgeDocument(
                2L,
                "guide",
                "动态事实约束",
                "涉及价格、优惠券、评分、营业时间、店铺详情等动态业务事实时，必须优先依赖工具调用和数据库查询结果，不能直接编造，也不能把旧向量片段当成当前真实数据。",
                List.of("动态事实", "工具", "边界"),
                Map.of("audience", "internal")
        ));
        knowledgeDocuments.add(new RagKnowledgeDocument(
                3L,
                "guide",
                "RAG 范围约束",
                "RAG 只负责静态规则、解释性知识和系统约束增强，不承载店铺实时内容、优惠库存、订单状态或任何强实时业务事实。",
                List.of("RAG", "范围", "架构"),
                Map.of("audience", "internal")
        ));

        for (RagKnowledgeSource knowledgeSource : knowledgeSources) {
            knowledgeDocuments.addAll(knowledgeSource.loadDocuments());
        }
        return toVectorDocuments(knowledgeDocuments);
    }

    /**
     * 入库前按句号级切片，提升长知识文档的召回质量。
     */
    private List<Document> toVectorDocuments(List<RagKnowledgeDocument> knowledgeDocuments) {
        if (knowledgeDocuments == null || knowledgeDocuments.isEmpty()) {
            return Collections.emptyList();
        }
        List<Document> vectorDocuments = new ArrayList<>();
        for (RagKnowledgeDocument knowledgeDocument : knowledgeDocuments) {
            List<String> chunks = splitText(knowledgeDocument.text(), 220);
            if (chunks.isEmpty()) {
                continue;
            }
            for (int index = 0; index < chunks.size(); index++) {
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("sourceType", knowledgeDocument.sourceType());
                metadata.put("sourceId", knowledgeDocument.sourceId());
                metadata.put("title", knowledgeDocument.title());
                metadata.put("chunkIndex", index);
                metadata.put("tags", knowledgeDocument.tags() == null ? List.of() : knowledgeDocument.tags());
                if (knowledgeDocument.metadata() != null && !knowledgeDocument.metadata().isEmpty()) {
                    metadata.putAll(knowledgeDocument.metadata());
                }
                vectorDocuments.add(Document.builder()
                        .id(knowledgeDocument.sourceType() + ":" + knowledgeDocument.sourceId() + ":" + index)
                        .text(chunks.get(index))
                        .metadata(metadata)
                        .build());
            }
        }
        return vectorDocuments;
    }

    private List<String> splitText(String text, int maxLength) {
        if (StrUtil.isBlank(text)) {
            return Collections.emptyList();
        }
        String normalized = text.replace("\r", "").trim();
        if (normalized.length() <= maxLength) {
            return List.of(normalized);
        }
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String[] sentences = normalized.split("(?<=[。！？；])");
        for (String sentence : sentences) {
            String trimmed = sentence == null ? "" : sentence.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.length() > maxLength) {
                if (current.length() > 0) {
                    chunks.add(current.toString());
                    current.setLength(0);
                }
                appendLongSentenceChunks(chunks, trimmed, maxLength);
                continue;
            }
            if (current.length() > 0 && current.length() + trimmed.length() > maxLength) {
                chunks.add(current.toString());
                current.setLength(0);
            }
            current.append(trimmed);
        }
        if (current.length() > 0) {
            chunks.add(current.toString());
        }
        return chunks;
    }

    private void appendLongSentenceChunks(List<String> chunks, String sentence, int maxLength) {
        int start = 0;
        while (start < sentence.length()) {
            int end = Math.min(start + maxLength, sentence.length());
            chunks.add(sentence.substring(start, end));
            start = end;
        }
    }
}
