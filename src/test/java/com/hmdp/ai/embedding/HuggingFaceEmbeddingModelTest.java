package com.hmdp.ai.embedding;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class HuggingFaceEmbeddingModelTest {

    @Test
    void shouldParseFlatVector() {
        HuggingFaceEmbeddingModel model = new HuggingFaceEmbeddingModel(
                "https://example.com",
                "token",
                "model",
                new ObjectMapper()
        );

        float[] vector = model.parseVector("[0.1,0.2,0.3]");

        assertEquals(3, vector.length);
        assertArrayEquals(new float[]{0.1f, 0.2f, 0.3f}, vector);
    }

    @Test
    void shouldParseNestedSentenceVector() {
        HuggingFaceEmbeddingModel model = new HuggingFaceEmbeddingModel(
                "https://example.com",
                "token",
                "model",
                new ObjectMapper()
        );

        float[] vector = model.parseVector("[[0.1,0.2,0.3]]");

        assertEquals(3, vector.length);
        assertArrayEquals(new float[]{0.1f, 0.2f, 0.3f}, vector);
    }
}
