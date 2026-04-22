package com.hmdp.ai.rag;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.config.AiProperties;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Shop;
import com.hmdp.entity.Voucher;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IShopService;
import com.hmdp.service.IVoucherService;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class LocalLifeRagService {

    private static final int MAX_DOCS_PER_DOMAIN = 200;

    private final AiProperties aiProperties;
    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;
    private final IShopService shopService;
    private final IVoucherService voucherService;
    private final IBlogService blogService;

    private volatile SimpleVectorStore vectorStore;
    private volatile boolean ready;

    public LocalLifeRagService(AiProperties aiProperties,
                               ObjectProvider<EmbeddingModel> embeddingModelProvider,
                               IShopService shopService,
                               IVoucherService voucherService,
                               IBlogService blogService) {
        this.aiProperties = aiProperties;
        this.embeddingModelProvider = embeddingModelProvider;
        this.shopService = shopService;
        this.voucherService = voucherService;
        this.blogService = blogService;
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
        File storeFile = FileUtil.file(aiProperties.getRagStoreFile());
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
        rebuildIndex(SimpleVectorStore.builder(embeddingModel).build(), FileUtil.file(aiProperties.getRagStoreFile()));
    }

    private void rebuildIndex(SimpleVectorStore store, File storeFile) {
        List<Document> documents = buildDocuments();
        if (documents.isEmpty()) {
            log.warn("No business documents found, vector RAG remains disabled.");
            return;
        }
        store.add(documents);
        FileUtil.mkParentDirs(storeFile);
        store.save(storeFile);
        this.vectorStore = store;
        this.ready = true;
        log.info("Rebuilt vector store with {} documents at {}", documents.size(), storeFile.getAbsolutePath());
    }

    private List<Document> buildDocuments() {
        List<Document> documents = new ArrayList<>();
        documents.addAll(buildShopDocuments());
        documents.addAll(buildVoucherDocuments());
        documents.addAll(buildBlogDocuments());
        documents.addAll(buildStaticKnowledgeDocuments());
        return documents;
    }

    private List<Document> buildShopDocuments() {
        List<Shop> shops = shopService.query().last("LIMIT " + MAX_DOCS_PER_DOMAIN).list();
        if (shops == null || shops.isEmpty()) {
            return Collections.emptyList();
        }
        List<Document> documents = new ArrayList<>(shops.size());
        for (Shop shop : shops) {
            String text = "店铺：" + safe(shop.getName())
                    + "；商圈：" + safe(shop.getArea())
                    + "；地址：" + safe(shop.getAddress())
                    + "；均价：" + safe(shop.getAvgPrice())
                    + "；评分：" + normalizeScore(shop.getScore())
                    + "；评价数：" + safe(shop.getComments())
                    + "；营业时间：" + safe(shop.getOpenHours());
            documents.add(Document.builder()
                    .id("shop:" + shop.getId())
                    .text(text)
                    .metadata(Map.of(
                            "sourceType", "shop",
                            "sourceId", shop.getId(),
                            "title", safe(shop.getName())
                    ))
                    .build());
        }
        return documents;
    }

    private List<Document> buildVoucherDocuments() {
        List<Voucher> vouchers = voucherService.query().last("LIMIT " + MAX_DOCS_PER_DOMAIN).list();
        if (vouchers == null || vouchers.isEmpty()) {
            return Collections.emptyList();
        }
        List<Document> documents = new ArrayList<>(vouchers.size());
        for (Voucher voucher : vouchers) {
            String text = "优惠券：" + safe(voucher.getTitle())
                    + "；副标题：" + safe(voucher.getSubTitle())
                    + "；支付金额：" + safe(voucher.getPayValue())
                    + "；抵扣金额：" + safe(voucher.getActualValue())
                    + "；规则：" + safe(voucher.getRules());
            documents.add(Document.builder()
                    .id("voucher:" + voucher.getId())
                    .text(text)
                    .metadata(Map.of(
                            "sourceType", "voucher",
                            "sourceId", voucher.getId(),
                            "title", safe(voucher.getTitle())
                    ))
                    .build());
        }
        return documents;
    }

    private List<Document> buildBlogDocuments() {
        List<Blog> blogs = blogService.query()
                .orderByDesc("liked")
                .last("LIMIT " + MAX_DOCS_PER_DOMAIN)
                .list();
        if (blogs == null || blogs.isEmpty()) {
            return Collections.emptyList();
        }
        List<Document> documents = new ArrayList<>(blogs.size());
        for (Blog blog : blogs) {
            String text = "探店笔记标题：" + safe(blog.getTitle())
                    + "；内容：" + abbreviate(safe(blog.getContent()), 180)
                    + "；点赞数：" + safe(blog.getLiked())
                    + "；关联店铺ID：" + safe(blog.getShopId());
            documents.add(Document.builder()
                    .id("blog:" + blog.getId())
                    .text(text)
                    .metadata(Map.of(
                            "sourceType", "blog",
                            "sourceId", blog.getId(),
                            "title", StrUtil.blankToDefault(blog.getTitle(), "探店笔记")
                    ))
                    .build());
        }
        return documents;
    }

    private List<Document> buildStaticKnowledgeDocuments() {
        return List.of(
                Document.builder()
                        .id("guide:agent")
                        .text("本系统是本地生活 Agent，推荐类问题应优先结合推荐工具和优惠券工具，不要只根据语言模型记忆回答。")
                        .metadata(Map.of("sourceType", "guide", "sourceId", 1L, "title", "Agent 使用约束"))
                        .build(),
                Document.builder()
                        .id("guide:coupon")
                        .text("涉及价格、优惠券、评分、营业时间等动态业务事实时，应优先依赖工具和召回结果，不可直接编造。")
                        .metadata(Map.of("sourceType", "guide", "sourceId", 2L, "title", "动态事实约束"))
                        .build()
        );
    }

    private String safe(Object value) {
        return value == null ? "未知" : String.valueOf(value);
    }

    private String normalizeScore(Integer score) {
        return score == null ? "未知" : String.valueOf(score / 10.0D);
    }

    private String abbreviate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }
}
