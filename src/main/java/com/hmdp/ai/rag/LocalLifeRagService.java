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
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class LocalLifeRagService {

    private final AiProperties aiProperties;
    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;

    private volatile SimpleVectorStore vectorStore;
    private volatile boolean ready;

    public LocalLifeRagService(AiProperties aiProperties,
                               ObjectProvider<EmbeddingModel> embeddingModelProvider) {
        this.aiProperties = aiProperties;
        this.embeddingModelProvider = embeddingModelProvider;
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
        List<Document> documents = buildStaticKnowledgeDocuments();
        if (documents.isEmpty()) {
            log.warn("No knowledge documents found, vector RAG remains disabled.");
            return;
        }
        store.add(documents);
        ensureParentDirectory(storeFile.toPath());
        store.save(storeFile);
        this.vectorStore = store;
        this.ready = true;
        log.info("Rebuilt vector store with {} guide documents at {}", documents.size(), storeFile.getAbsolutePath());
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

    private List<Document> buildStaticKnowledgeDocuments() {
        return List.of(
                Document.builder()
                        .id("guide:agent")
                        .text("本系统是本地生活 Agent。遇到推荐类问题时，应优先结合推荐工具、店铺工具和优惠券工具获取事实依据，而不是只依赖语言模型记忆直接回答。")
                        .metadata(Map.of("sourceType", "guide", "sourceId", 1L, "title", "Agent 使用约束"))
                        .build(),
                Document.builder()
                        .id("guide:dynamic-facts")
                        .text("涉及价格、优惠券、评分、营业时间、店铺详情等动态业务事实时，必须优先依赖工具调用和数据库查询结果，不能直接编造，也不能把旧向量片段当成当前真实数据。")
                        .metadata(Map.of("sourceType", "guide", "sourceId", 2L, "title", "动态事实约束"))
                        .build(),
                Document.builder()
                        .id("guide:retrieval-scope")
                        .text("RAG 只负责静态规则、解释性知识和系统约束增强，不再承载探店博客、店铺内容或任何动态业务信息。")
                        .metadata(Map.of("sourceType", "guide", "sourceId", 3L, "title", "RAG 范围约束"))
                        .build()
        );
    }
}
