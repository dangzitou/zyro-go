package com.hmdp.ai.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.AbstractEmbeddingModel;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Alibaba DashScope embedding model using the official compatible embeddings endpoint.
 */
public class DashScopeEmbeddingModel extends AbstractEmbeddingModel {

    private static final String DEFAULT_ENDPOINT = "https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String endpoint;
    private final String model;
    private final Integer dimensions;

    public DashScopeEmbeddingModel(String endpoint,
                                   String apiKey,
                                   String model,
                                   Integer dimensions,
                                   ObjectMapper objectMapper) {
        this.endpoint = (endpoint == null || endpoint.isBlank()) ? DEFAULT_ENDPOINT : endpoint.trim();
        this.model = model;
        this.dimensions = dimensions;
        this.objectMapper = objectMapper;
        this.restClient = RestClient.builder()
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public float[] embed(Document document) {
        return embedText(getEmbeddingContent(document));
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<String> instructions = request.getInstructions();
        List<Embedding> results = new ArrayList<>(instructions.size());
        for (int i = 0; i < instructions.size(); i++) {
            results.add(new Embedding(embedText(instructions.get(i)), i));
        }
        return new EmbeddingResponse(results);
    }

    @Override
    public int dimensions() {
        if (dimensions != null && dimensions > 0) {
            return dimensions;
        }
        return embedText("embedding health check").length;
    }

    public float[] embedText(String text) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("model", model);
        payload.put("input", text == null ? "" : text);
        payload.put("encoding_format", "float");
        if (dimensions != null && dimensions > 0) {
            payload.put("dimensions", dimensions);
        }

        String responseBody = restClient.post()
                .uri(endpoint)
                .body(payload)
                .retrieve()
                .body(String.class);

        return parseVector(responseBody);
    }

    float[] parseVector(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode vectorNode = root.path("data").get(0).path("embedding");
            float[] vector = new float[vectorNode.size()];
            for (int i = 0; i < vectorNode.size(); i++) {
                vector[i] = (float) vectorNode.get(i).asDouble();
            }
            return vector;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse DashScope embedding response.", e);
        }
    }
}
