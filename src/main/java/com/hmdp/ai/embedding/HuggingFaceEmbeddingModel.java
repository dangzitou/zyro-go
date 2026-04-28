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
 * Hugging Face Inference feature-extraction embedding model.
 * It targets OpenAI-incompatible providers that still expose stable HTTP inference endpoints.
 */
public class HuggingFaceEmbeddingModel extends AbstractEmbeddingModel {

    private static final String DEFAULT_BASE_URL = "https://router.huggingface.co/hf-inference/models";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String endpoint;

    public HuggingFaceEmbeddingModel(String baseUrl, String apiKey, String model, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.endpoint = resolveEndpoint(baseUrl, model);
        this.restClient = RestClient.builder()
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.USER_AGENT, "Mozilla/5.0")
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
        return embedText("embedding health check").length;
    }

    public float[] embedText(String text) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("inputs", text == null ? "" : text);
        payload.put("normalize", true);
        payload.put("truncate", true);

        String responseBody = restClient.post()
                .uri(endpoint)
                .body(payload)
                .retrieve()
                .body(String.class);

        return parseVector(responseBody);
    }

    /**
     * HF feature-extraction may return:
     * 1. [0.1, 0.2, ...]
     * 2. [[0.1, 0.2, ...]]
     * 3. [[[token vectors...]]]
     * We always flatten to one sentence-level vector.
     */
    float[] parseVector(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode vectorNode = unwrapVectorNode(root);
            if (vectorNode == null || !vectorNode.isArray() || vectorNode.isEmpty()) {
                throw new IllegalStateException("Hugging Face embedding response is empty.");
            }
            float[] vector = new float[vectorNode.size()];
            for (int i = 0; i < vectorNode.size(); i++) {
                vector[i] = (float) vectorNode.get(i).asDouble();
            }
            return vector;
        } catch (Exception e) {
            String snippet = responseBody == null ? "null" : responseBody.substring(0, Math.min(responseBody.length(), 200));
            throw new IllegalStateException("Failed to parse Hugging Face embedding response. body=" + snippet, e);
        }
    }

    private JsonNode unwrapVectorNode(JsonNode node) {
        JsonNode current = node;
        while (current != null && current.isArray() && !current.isEmpty() && current.get(0).isArray()) {
            current = current.get(0);
        }
        return current;
    }

    private static String resolveBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return DEFAULT_BASE_URL;
        }
        return baseUrl.trim().replaceAll("/+$", "");
    }

    private static String resolveEndpoint(String baseUrl, String model) {
        String normalizedBaseUrl = resolveBaseUrl(baseUrl);
        String normalizedModel = model == null ? "" : model.trim().replaceAll("^/+", "");
        return normalizedBaseUrl + "/" + normalizedModel;
    }
}
