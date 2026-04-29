package com.hmdp.ai;

import com.hmdp.config.AiProperties;
import com.hmdp.dto.AgentExecutionPlan;
import org.junit.jupiter.api.Test;

import java.util.List;

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

        assertTrue(facts.stream().anyMatch(fact -> "profile.city".equals(fact.getType()) && fact.getContent().contains("广州市")));
        assertTrue(facts.stream().anyMatch(fact -> "preference.price_band".equals(fact.getType())));
        assertTrue(facts.stream().anyMatch(fact -> "avoidance.category".equals(fact.getType()) && fact.getContent().contains("火锅")));
        assertTrue(facts.stream().anyMatch(fact -> "preference.scene".equals(fact.getType()) && fact.getContent().contains("约会")));
    }
}
