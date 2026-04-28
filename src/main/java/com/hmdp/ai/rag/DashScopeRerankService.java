package com.hmdp.ai.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.config.AiProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class DashScopeRerankService {

    private final AiProperties aiProperties;
    private final ObjectMapper objectMapper;

    public DashScopeRerankService(AiProperties aiProperties, ObjectMapper objectMapper) {
        this.aiProperties = aiProperties;
        this.objectMapper = objectMapper;
    }

    public List<RerankResult> rerank(String query, List<String> documents, Integer topN) {
        if (query == null || query.isBlank() || documents == null || documents.isEmpty()
                || !Boolean.TRUE.equals(aiProperties.getRerank().getEnabled())) {
            return List.of();
        }
        RestClient restClient = RestClient.builder()
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + resolveApiKey())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();

        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("model", aiProperties.getRerank().getModel());
        payload.put("query", query);
        payload.put("documents", documents);
        payload.put("top_n", topN == null ? aiProperties.getRerank().getTopN() : topN);
        payload.put("instruct", "Rank local-life restaurant candidates by dining intent, location fit, taste and review relevance.");

        String responseBody = restClient.post()
                .uri(aiProperties.getRerank().getBaseUrl())
                .body(payload)
                .retrieve()
                .body(String.class);

        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode resultsNode = root.path("results");
            List<RerankResult> results = new ArrayList<RerankResult>();
            for (JsonNode node : resultsNode) {
                results.add(new RerankResult(
                        node.path("index").asInt(),
                        node.path("relevance_score").asDouble()
                ));
            }
            results.sort(Comparator.comparingDouble(RerankResult::score).reversed());
            return results;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse DashScope rerank response.", e);
        }
    }

    private String resolveApiKey() {
        if (StringUtils.hasText(aiProperties.getRerank().getApiKey())) {
            return aiProperties.getRerank().getApiKey().trim();
        }
        if (StringUtils.hasText(aiProperties.getEmbedding().getApiKey())) {
            return aiProperties.getEmbedding().getApiKey().trim();
        }
        return "";
    }

    public record RerankResult(int index, double score) {
    }
}
