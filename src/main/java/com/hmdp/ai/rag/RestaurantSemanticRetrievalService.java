package com.hmdp.ai.rag;

import cn.hutool.core.util.StrUtil;
import com.hmdp.config.AiProperties;
import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
public class RestaurantSemanticRetrievalService {

    private final AiProperties aiProperties;
    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;
    private final IShopService shopService;
    private final DashScopeRerankService rerankService;
    private final BaiduMapGeoService baiduMapGeoService;

    private volatile SimpleVectorStore vectorStore;
    private volatile boolean ready;

    public RestaurantSemanticRetrievalService(AiProperties aiProperties,
                                              ObjectProvider<EmbeddingModel> embeddingModelProvider,
                                              IShopService shopService,
                                              DashScopeRerankService rerankService,
                                              BaiduMapGeoService baiduMapGeoService) {
        this.aiProperties = aiProperties;
        this.embeddingModelProvider = embeddingModelProvider;
        this.shopService = shopService;
        this.rerankService = rerankService;
        this.baiduMapGeoService = baiduMapGeoService;
    }

    @PostConstruct
    public void initialize() {
        if (!aiProperties.isEnabled() || !Boolean.TRUE.equals(aiProperties.getRestaurantRetrieval().getEnabled())) {
            return;
        }
        EmbeddingModel embeddingModel = embeddingModelProvider.getIfAvailable();
        if (embeddingModel == null) {
            log.warn("EmbeddingModel bean not found, restaurant semantic retrieval disabled.");
            return;
        }
        SimpleVectorStore store = SimpleVectorStore.builder(embeddingModel).build();
        File storeFile = resolveStoreFile();
        try {
            if (storeFile.exists() && !Boolean.TRUE.equals(aiProperties.getRestaurantRetrieval().getRebuildOnStartup())) {
                store.load(storeFile);
                this.vectorStore = store;
                this.ready = true;
                log.info("Loaded restaurant vector store from {}", storeFile.getAbsolutePath());
                return;
            }
            rebuildIndex(store, storeFile);
        } catch (Exception e) {
            log.warn("Failed to initialize restaurant semantic retrieval.", e);
        }
    }

    public synchronized void rebuildIndex() {
        EmbeddingModel embeddingModel = embeddingModelProvider.getIfAvailable();
        if (embeddingModel == null) {
            return;
        }
        rebuildIndex(SimpleVectorStore.builder(embeddingModel).build(), resolveStoreFile());
    }

    public boolean isReady() {
        return ready && vectorStore != null;
    }

    public List<SemanticRestaurantHit> search(String query, Integer typeId, String city, String locationHint, Double userX, Double userY, int limit) {
        if (!isReady() || StrUtil.isBlank(query)) {
            return Collections.emptyList();
        }
        Double effectiveX = userX;
        Double effectiveY = userY;
        boolean explicitGeoResolved = false;
        if (StrUtil.isNotBlank(locationHint)) {
            BaiduMapGeoService.GeoPoint geoPoint = baiduMapGeoService.geocode(city, locationHint);
            if (geoPoint != null) {
                effectiveX = geoPoint.lng();
                effectiveY = geoPoint.lat();
                explicitGeoResolved = true;
            }
        }
        final Double finalX = effectiveX;
        final Double finalY = effectiveY;
        final String finalCity = explicitGeoResolved && finalX != null && finalY != null ? null : city;
        final String finalLocationHint = explicitGeoResolved && finalX != null && finalY != null ? null : locationHint;
        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(Math.max(limit, aiProperties.getRestaurantRetrieval().getCandidateLimit()))
                .similarityThreshold(aiProperties.getRestaurantRetrieval().getSimilarityThreshold())
                .build();
        List<Document> recalled = vectorStore.similaritySearch(request);
        if (recalled == null || recalled.isEmpty()) {
            return Collections.emptyList();
        }

        List<Document> filtered = recalled.stream()
                .filter(doc -> matchType(doc, typeId))
                .filter(doc -> matchExplicitLocation(doc, finalCity, finalLocationHint))
                .filter(doc -> matchNearby(doc, finalX, finalY))
                .limit(aiProperties.getRestaurantRetrieval().getCandidateLimit())
                .toList();
        if (filtered.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> docs = filtered.stream().map(Document::getText).toList();
        Map<Integer, Double> rerankScores = rerankService.rerank(query, docs, Math.max(limit, 1)).stream()
                .collect(Collectors.toMap(DashScopeRerankService.RerankResult::index, DashScopeRerankService.RerankResult::score));

        List<SemanticRestaurantHit> hits = new ArrayList<SemanticRestaurantHit>();
        for (int i = 0; i < filtered.size(); i++) {
            Document doc = filtered.get(i);
            Map<String, Object> metadata = doc.getMetadata();
            hits.add(new SemanticRestaurantHit(
                    ((Number) metadata.get("shopId")).longValue(),
                    doc.getScore() == null ? 0D : doc.getScore(),
                    rerankScores.getOrDefault(i, 0D),
                    doc.getText()
            ));
        }
        hits.sort((left, right) -> Double.compare(right.rerankScore(), left.rerankScore()));
        if (hits.size() > limit) {
            return new ArrayList<SemanticRestaurantHit>(hits.subList(0, limit));
        }
        return hits;
    }

    private void rebuildIndex(SimpleVectorStore store, File storeFile) {
        List<Shop> shops = shopService.query().eq("type_id", 1).list();
        List<Document> docs = new ArrayList<Document>();
        for (Shop shop : shops) {
            docs.add(Document.builder()
                    .id("shop:" + shop.getId())
                    .text(buildRestaurantDocument(shop))
                    .metadata(buildMetadata(shop))
                    .build());
        }
        store.add(docs);
        ensureParentDirectory(storeFile.toPath());
        store.save(storeFile);
        this.vectorStore = store;
        this.ready = true;
        log.info("Rebuilt restaurant vector store with {} restaurant documents at {}", docs.size(), storeFile.getAbsolutePath());
    }

    private boolean matchType(Document document, Integer typeId) {
        if (typeId == null) {
            return true;
        }
        Object value = document.getMetadata().get("typeId");
        return value instanceof Number number && number.intValue() == typeId;
    }

    private boolean matchNearby(Document document, Double userX, Double userY) {
        if (userX == null || userY == null) {
            return true;
        }
        Object x = document.getMetadata().get("x");
        Object y = document.getMetadata().get("y");
        if (!(x instanceof Number xNumber) || !(y instanceof Number yNumber)) {
            return true;
        }
        double distance = distanceMeters(userX, userY, xNumber.doubleValue(), yNumber.doubleValue());
        document.getMetadata().put("distanceMeters", distance);
        return distance <= aiProperties.getRestaurantRetrieval().getNearbyDistanceLimitMeters();
    }

    private boolean matchExplicitLocation(Document document, String city, String locationHint) {
        if (StrUtil.isBlank(city) && StrUtil.isBlank(locationHint)) {
            return true;
        }
        String area = String.valueOf(document.getMetadata().getOrDefault("area", ""));
        String address = String.valueOf(document.getMetadata().getOrDefault("address", ""));
        String source = (area + " " + address).toLowerCase();
        boolean cityMatch = StrUtil.isBlank(city) || source.contains(city.toLowerCase());
        boolean locationMatch = StrUtil.isBlank(locationHint) || source.contains(locationHint.toLowerCase());
        return cityMatch && locationMatch;
    }

    private String buildRestaurantDocument(Shop shop) {
        return shop.getName()
                + "，" + StrUtil.blankToDefault(shop.getArea(), "未知区域")
                + "，" + StrUtil.blankToDefault(shop.getAddress(), "未知地址")
                + "，人均" + (shop.getAvgPrice() == null ? 0L : shop.getAvgPrice()) + "元"
                + "，评分" + (shop.getScore() == null ? "0" : (shop.getScore() / 10.0))
                + "，评论数" + (shop.getComments() == null ? 0 : shop.getComments())
                + "，营业时间" + StrUtil.blankToDefault(shop.getOpenHours(), "未知");
    }

    private Map<String, Object> buildMetadata(Shop shop) {
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put("shopId", shop.getId());
        metadata.put("typeId", shop.getTypeId());
        metadata.put("x", shop.getX());
        metadata.put("y", shop.getY());
        metadata.put("area", shop.getArea());
        metadata.put("address", shop.getAddress());
        metadata.put("avgPrice", shop.getAvgPrice());
        metadata.put("score", shop.getScore());
        return metadata;
    }

    private File resolveStoreFile() {
        Path path = Paths.get(aiProperties.getRestaurantRetrieval().getStoreFile()).toAbsolutePath().normalize();
        return path.toFile();
    }

    private void ensureParentDirectory(Path filePath) {
        try {
            Path parent = filePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create restaurant vector store directory for " + filePath, e);
        }
    }

    private double distanceMeters(Double userX, Double userY, Double shopX, Double shopY) {
        double earthRadius = 6371000D;
        double lat1 = Math.toRadians(userY);
        double lat2 = Math.toRadians(shopY);
        double deltaLat = Math.toRadians(shopY - userY);
        double deltaLng = Math.toRadians(shopX - userX);
        double a = Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(deltaLng / 2) * Math.sin(deltaLng / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return earthRadius * c;
    }

    public record SemanticRestaurantHit(Long shopId, double vectorScore, double rerankScore, String document) {
    }
}
