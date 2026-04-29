package com.hmdp.ai;

import cn.hutool.core.util.StrUtil;
import com.hmdp.config.AiProperties;
import com.hmdp.dto.AgentExecutionPlan;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class MemoryExtractionService {

    private final AiProperties aiProperties;

    public MemoryExtractionService(AiProperties aiProperties) {
        this.aiProperties = aiProperties;
    }

    public List<LongTermMemoryFact> extractFacts(Long userId,
                                                 String conversationId,
                                                 String message,
                                                 AgentExecutionPlan plan) {
        if (userId == null || plan == null || !Boolean.TRUE.equals(aiProperties.getContextCompression().getLongTermMemoryEnabled())) {
            return new ArrayList<LongTermMemoryFact>();
        }
        List<LongTermMemoryFact> facts = new ArrayList<LongTermMemoryFact>();
        Instant now = Instant.now();

        addSingletonFact(facts, userId, "profile.city", plan.getCity(), 0.95D, conversationId, now);
        addFactIfPresent(facts, userId, "profile.frequent_areas", plan.getLocationHint(), 0.82D, conversationId, now);
        addFactIfPresent(facts, userId, "preference.food", firstNonBlank(plan.getSubcategory(), plan.getCategory()), 0.78D, conversationId, now);
        addFactIfPresent(facts, userId, "preference.price_band", resolvePriceBand(plan), 0.84D, conversationId, now);
        if (plan.getPartySize() != null) {
            addSingletonFact(facts, userId, "preference.party_size", String.valueOf(plan.getPartySize()), 0.88D, conversationId, now);
        }
        addFactIfPresent(facts, userId, "preference.response_style", plan.getResponseStyle(), 0.75D, conversationId, now);

        for (String tag : splitTags(plan.getQualityPreference())) {
            if (containsAny(tag, "约会", "安静", "一个人", "出餐快", "清淡", "性价比")) {
                addFactIfPresent(facts, userId, "preference.scene", tag, 0.77D, conversationId, now);
            }
        }
        for (String category : safeList(plan.getExcludedCategories())) {
            addFactIfPresent(facts, userId, "avoidance.category", category, 0.93D, conversationId, now);
        }
        for (String preference : safeList(plan.getNegativePreferences())) {
            addFactIfPresent(facts, userId, "avoidance.flavor", preference, 0.88D, conversationId, now);
        }

        // Lift obvious low-cost preference cues from the raw message when the plan is conservative.
        String normalized = StrUtil.blankToDefault(message, "").toLowerCase(Locale.ROOT);
        if (containsAny(normalized, "学生党", "便宜", "不贵", "平价")) {
            addFactIfPresent(facts, userId, "preference.price_band", "cheap", 0.76D, conversationId, now);
        }
        if (containsAny(normalized, "约会", "安静")) {
            addFactIfPresent(facts, userId, "preference.scene", containsAny(normalized, "约会") ? "适合约会" : "安静", 0.74D, conversationId, now);
        }
        return deduplicateFacts(facts);
    }

    public List<LongTermMemoryFact> mergeFacts(List<LongTermMemoryFact> existingFacts, List<LongTermMemoryFact> newFacts) {
        Map<String, LongTermMemoryFact> merged = new LinkedHashMap<String, LongTermMemoryFact>();
        for (LongTermMemoryFact fact : safeFactList(existingFacts)) {
            merged.put(memoryKey(fact), fact);
        }
        for (LongTermMemoryFact fact : safeFactList(newFacts)) {
            if (fact.getConfidence() == null
                    || fact.getConfidence() < aiProperties.getContextCompression().getFactMinConfidence()) {
                continue;
            }
            String key = memoryKey(fact);
            LongTermMemoryFact existing = merged.get(key);
            if (existing == null || shouldReplace(existing, fact)) {
                if (isSingletonType(fact.getType())) {
                    removeSingletonType(merged, fact.getType());
                }
                merged.put(key, fact);
            }
        }
        return new ArrayList<LongTermMemoryFact>(merged.values());
    }

    private void addSingletonFact(List<LongTermMemoryFact> facts, Long userId, String type, String content,
                                  double confidence, String conversationId, Instant now) {
        if (StrUtil.isBlank(content)) {
            return;
        }
        facts.removeIf(existing -> type.equals(existing.getType()));
        facts.add(new LongTermMemoryFact(userId, type, content.trim(), confidence, conversationId, now, null));
    }

    private void addFactIfPresent(List<LongTermMemoryFact> facts, Long userId, String type, String content,
                                  double confidence, String conversationId, Instant now) {
        if (StrUtil.isBlank(content)) {
            return;
        }
        facts.add(new LongTermMemoryFact(userId, type, content.trim(), confidence, conversationId, now, null));
    }

    private boolean shouldReplace(LongTermMemoryFact existing, LongTermMemoryFact candidate) {
        if (isSingletonType(candidate.getType())) {
            return candidate.getLastUpdatedAt() != null;
        }
        return candidate.getConfidence() != null
                && (existing.getConfidence() == null || candidate.getConfidence() >= existing.getConfidence() - 0.05D);
    }

    private void removeSingletonType(Map<String, LongTermMemoryFact> merged, String type) {
        merged.entrySet().removeIf(entry -> type.equals(entry.getValue().getType()));
    }

    private boolean isSingletonType(String type) {
        return containsAny(type,
                "profile.city",
                "preference.price_band",
                "preference.party_size",
                "preference.response_style");
    }

    private String memoryKey(LongTermMemoryFact fact) {
        if (isSingletonType(fact.getType())) {
            return fact.getType();
        }
        return fact.getType() + "::" + normalize(fact.getContent());
    }

    private String resolvePriceBand(AgentExecutionPlan plan) {
        if (plan.getBudgetMax() != null) {
            if (plan.getBudgetMax() <= 80) {
                return "cheap";
            }
            if (plan.getBudgetMax() >= 220) {
                return "premium";
            }
            return "moderate";
        }
        return plan.getPricePreference();
    }

    private List<String> splitTags(String value) {
        if (StrUtil.isBlank(value)) {
            return new ArrayList<String>();
        }
        Set<String> tags = new LinkedHashSet<String>();
        for (String token : value.split("[,，]")) {
            if (StrUtil.isNotBlank(token)) {
                tags.add(token.trim());
            }
        }
        return new ArrayList<String>(tags);
    }

    private String firstNonBlank(String first, String second) {
        return StrUtil.isNotBlank(first) ? first : second;
    }

    private boolean containsAny(String source, String... values) {
        if (source == null) {
            return false;
        }
        for (String value : values) {
            if (source.contains(value)) {
                return true;
            }
        }
        return false;
    }

    private List<String> safeList(List<String> values) {
        return values == null ? new ArrayList<String>() : values;
    }

    private List<LongTermMemoryFact> safeFactList(List<LongTermMemoryFact> values) {
        return values == null ? new ArrayList<LongTermMemoryFact>() : values;
    }

    private List<LongTermMemoryFact> deduplicateFacts(List<LongTermMemoryFact> facts) {
        Map<String, LongTermMemoryFact> map = new LinkedHashMap<String, LongTermMemoryFact>();
        for (LongTermMemoryFact fact : facts) {
            map.put(memoryKey(fact), fact);
        }
        return new ArrayList<LongTermMemoryFact>(map.values());
    }

    private String normalize(String value) {
        return StrUtil.blankToDefault(value, "").trim().toLowerCase(Locale.ROOT);
    }
}
