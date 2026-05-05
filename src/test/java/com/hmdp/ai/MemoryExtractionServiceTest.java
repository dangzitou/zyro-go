package com.hmdp.ai;

import com.hmdp.config.AiProperties;
import com.hmdp.dto.AgentExecutionPlan;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryExtractionServiceTest {

    @Test
    void shouldExtractStablePreferenceFactsFromPlan() {
        AiProperties properties = new AiProperties();
        MemoryExtractionService service = new MemoryExtractionService(properties);

        AgentExecutionPlan plan = new AgentExecutionPlan();
        plan.setCity("广州市");
        plan.setLocationHint("正佳广场");
        plan.setSubcategory("海鲜");
        plan.setBudgetMax(80L);
        plan.setPartySize(2);
        plan.setQualityPreference("适合约会,安静,性价比高");
        plan.setExcludedCategories(List.of("火锅"));
        plan.setNegativePreferences(List.of("太辣"));

        List<LongTermMemoryFact> facts = service.extractFacts(
                1L,
                "agent-session:1:default",
                "广州正佳附近预算80，两个人，安静一点，适合约会，不要火锅",
                plan
        );

        assertTrue(facts.stream().anyMatch(fact -> "profile.city".equals(fact.getType()) && fact.getContent().contains("广州")));
        assertTrue(facts.stream().anyMatch(fact -> "preference.price_band".equals(fact.getType())));
        assertTrue(facts.stream().anyMatch(fact -> "avoidance.category".equals(fact.getType()) && fact.getContent().contains("火锅")));
        assertTrue(facts.stream().anyMatch(fact -> "preference.scene".equals(fact.getType()) && fact.getContent().contains("约会")));
        assertTrue(facts.stream().allMatch(fact -> fact.getMemoryClass() != null && !fact.getMemoryClass().isBlank()));
        assertTrue(facts.stream().anyMatch(fact -> fact.getExpiresAt() != null));
    }

    @Test
    void shouldFilterExpiredAndConflictingFacts() {
        AiProperties properties = new AiProperties();
        MemoryExtractionService service = new MemoryExtractionService(properties);

        LongTermMemoryFact expired = new LongTermMemoryFact(
                1L, "stable_preference", "preference.food", "火锅", 0.9D,
                "agent-session:1:default", Instant.now().minusSeconds(3600), Instant.now().minusSeconds(60)
        );
        LongTermMemoryFact conflict = new LongTermMemoryFact(
                1L, "stable_preference", "preference.food", "火锅", 0.9D,
                "agent-session:1:default", Instant.now(), Instant.now().plusSeconds(3600)
        );
        LongTermMemoryFact valid = new LongTermMemoryFact(
                1L, "user_profile", "profile.city", "广州市", 0.95D,
                "agent-session:1:default", Instant.now(), null
        );

        AgentExecutionPlan plan = new AgentExecutionPlan();
        plan.setExcludedCategories(List.of("火锅"));

        List<LongTermMemoryFact> filtered = service.filterRelevantFacts(List.of(expired, conflict, valid), plan, Instant.now());

        assertTrue(expired.getStale());
        assertFalse(filtered.contains(expired));
        assertFalse(filtered.contains(conflict));
        assertTrue(filtered.contains(valid));
    }
}
