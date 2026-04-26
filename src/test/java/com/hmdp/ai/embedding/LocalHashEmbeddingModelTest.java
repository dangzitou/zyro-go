package com.hmdp.ai.embedding;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class LocalHashEmbeddingModelTest {

    @Test
    void shouldGenerateDeterministicVectors() {
        LocalHashEmbeddingModel model = new LocalHashEmbeddingModel(128);

        float[] first = model.embed("厦门火锅推荐");
        float[] second = model.embed("厦门火锅推荐");

        assertEquals(128, first.length);
        assertArrayEquals(first, second);
    }

    @Test
    void shouldDifferentiateDifferentTexts() {
        LocalHashEmbeddingModel model = new LocalHashEmbeddingModel(128);

        float[] hotpot = model.embed("厦门火锅推荐");
        float[] coffee = model.embed("北京咖啡馆推荐");

        assertNotEquals(signature(hotpot), signature(coffee));
    }

    private String signature(float[] vector) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < Math.min(vector.length, 12); i++) {
            builder.append(Math.round(vector[i] * 1000)).append('#');
        }
        return builder.toString();
    }
}
