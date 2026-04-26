package com.hmdp.ai.embedding;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.AbstractEmbeddingModel;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A deterministic local fallback embedding model.
 * It is not semantically comparable to hosted embedding APIs,
 * but it provides stable vectors so the RAG pipeline remains usable
 * when a third-party gateway does not expose /v1/embeddings.
 */
public class LocalHashEmbeddingModel extends AbstractEmbeddingModel {

    private final int dimensions;

    public LocalHashEmbeddingModel(int dimensions) {
        this.dimensions = Math.max(64, dimensions);
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
        return dimensions;
    }

    public float[] embedText(String text) {
        float[] vector = new float[dimensions];
        String normalized = normalize(text);
        if (normalized.isEmpty()) {
            return vector;
        }

        char[] chars = normalized.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            char current = chars[i];
            mix(vector, String.valueOf(current), 1.0f);
            if (i + 1 < chars.length) {
                mix(vector, "" + current + chars[i + 1], 0.75f);
            }
            if (i + 2 < chars.length) {
                mix(vector, "" + current + chars[i + 1] + chars[i + 2], 0.45f);
            }
        }

        String[] tokens = normalized.split("\\s+");
        for (String token : tokens) {
            if (token.isBlank()) {
                continue;
            }
            mix(vector, token, 1.2f);
        }

        normalizeL2(vector);
        return vector;
    }

    private void mix(float[] vector, String token, float weight) {
        int hash = murmurLikeHash(token);
        int index = Math.floorMod(hash, dimensions);
        float sign = ((hash >>> 1) & 1) == 0 ? 1.0f : -1.0f;
        vector[index] += sign * weight;

        int secondIndex = Math.floorMod(hash * 31, dimensions);
        vector[secondIndex] += sign * weight * 0.35f;
    }

    private String normalize(String text) {
        if (text == null) {
            return "";
        }
        return Normalizer.normalize(text, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{Punct}]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private int murmurLikeHash(String token) {
        int hash = 0x9747b28c;
        for (int i = 0; i < token.length(); i++) {
            hash ^= token.charAt(i);
            hash *= 0x5bd1e995;
            hash ^= hash >>> 15;
        }
        return hash;
    }

    private void normalizeL2(float[] vector) {
        double sum = 0D;
        for (float value : vector) {
            sum += value * value;
        }
        if (sum == 0D) {
            return;
        }
        float norm = (float) Math.sqrt(sum);
        for (int i = 0; i < vector.length; i++) {
            vector[i] = vector[i] / norm;
        }
    }
}
