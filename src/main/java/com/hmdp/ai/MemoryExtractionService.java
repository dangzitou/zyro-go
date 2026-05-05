package com.hmdp.ai;

import cn.hutool.core.util.StrUtil;
import com.hmdp.config.AiProperties;
import com.hmdp.dto.AgentExecutionPlan;
import org.springframework.stereotype.Service;

import java.time.Duration;
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

        addSingletonFact(facts, userId, "user_profile", "profile.city", plan.getCity(), 0.95D, conversationId, now, null);
        addFactIfPresent(facts, userId, "user_profile", "profile.frequent_areas", plan.getLocationHint(), 0.82D, conversationId, now, expiryAt(now, 120));
        addFactIfPresent(facts, userId, "stable_preference", "preference.food", firstNonBlank(plan.getSubcategory(), plan.getCategory()), 0.78D, conversationId, now, expiryAt(now, 60));
        addFactIfPresent(facts, userId, "stable_preference", "preference.price_band", resolvePriceBand(plan), 0.84D, conversationId, now, expiryAt(now, 90));
        if (plan.getPartySize() != null) {
            addSingletonFact(facts, userId, "stable_preference", "preference.party_size",
                    String.valueOf(plan.getPartySize()), 0.88D, conversationId, now, expiryAt(now, 90));
        }
        addFactIfPresent(facts, userId, "user_profile", "preference.response_style",
                plan.getResponseStyle(), 0.75D, conversationId, now, expiryAt(now, 180));

        for (String tag : splitTags(plan.getQualityPreference())) {
            if (containsAny(tag, "约会", "安静", "一个人", "出餐快", "清淡", "性价比")) {
                addFactIfPresent(facts, userId, "stable_preference", "preference.scene", tag, 0.77D,
                        conversationId, now, expiryAt(now, 90));
            }
        }
        for (String category : safeList(plan.getExcludedCategories())) {
            addFactIfPresent(facts, userId, "hard_avoidance", "avoidance.category", category, 0.93D,
                    conversationId, now, expiryAt(now, 180));
        }
        for (String preference : safeList(plan.getNegativePreferences())) {
            addFactIfPresent(facts, userId, "hard_avoidance", "avoidance.flavor", preference, 0.88D,
                    conversationId, now, expiryAt(now, 120));
        }

        String normalized = StrUtil.blankToDefault(message, "").toLowerCase(Locale.ROOT);
        if (containsAny(normalized, "学生党", "便宜", "不贵", "平价")) {
            addFactIfPresent(facts, userId, "stable_preference", "preference.price_band", "cheap", 0.76D,
                    conversationId, now, expiryAt(now, 60));
        }
        if (containsAny(normalized, "约会", "安静")) {
            addFactIfPresent(facts, userId, "stable_preference", "preference.scene",
                    containsAny(normalized, "约会") ? "适合约会" : "安静", 0.74D,
                    conversationId, now, expiryAt(now, 60));
        }
        return deduplicateFacts(facts);
    }

    public List<LongTermMemoryFact> mergeFacts(List<LongTermMemoryFact> existingFacts, List<LongTermMemoryFact> newFacts) {
        Map<String, LongTermMemoryFact> merged = new LinkedHashMap<String, LongTermMemoryFact>();
        Instant now = Instant.now();
        for (LongTermMemoryFact fact : safeFactList(existingFacts)) {
            if (markStaleIfNeeded(fact, now)) {
                continue;
            }
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

    public List<LongTermMemoryFact> filterRelevantFacts(List<LongTermMemoryFact> facts,
                                                        AgentExecutionPlan currentPlan,
                                                        Instant now) {
        List<LongTermMemoryFact> filtered = new ArrayList<LongTermMemoryFact>();
        for (LongTermMemoryFact fact : safeFactList(facts)) {
            if (markStaleIfNeeded(fact, now)) {
                continue;
            }
            if (conflictsWithCurrentPlan(fact, currentPlan)) {
                continue;
            }
            filtered.add(fact);
        }
        return filtered;
    }

    private void addSingletonFact(List<LongTermMemoryFact> facts,
                                  Long userId,
                                  String memoryClass,
                                  String type,
                                  String content,
                                  double confidence,
                                  String conversationId,
                                  Instant now,
                                  Instant expiresAt) {
        if (StrUtil.isBlank(content)) {
            return;
        }
        facts.removeIf(existing -> type.equals(existing.getType()));
        facts.add(new LongTermMemoryFact(userId, memoryClass, type, content.trim(), confidence, conversationId, now, expiresAt));
    }

    private void addFactIfPresent(List<LongTermMemoryFact> facts,
                                  Long userId,
                                  String memoryClass,
                                  String type,
                                  String content,
                                  double confidence,
                                  String conversationId,
                                  Instant now,
                                  Instant expiresAt) {
        if (StrUtil.isBlank(content)) {
            return;
        }
        facts.add(new LongTermMemoryFact(userId, memoryClass, type, content.trim(), confidence, conversationId, now, expiresAt));
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

    private Instant expiryAt(Instant now, int fallbackDays) {
        Integer configured = aiProperties.getContextCompression().getMemoryExpiryDays();
        int days = configured == null ? fallbackDays : Math.max(7, configured);
        return now.plus(Duration.ofDays(Math.max(days, fallbackDays)));
    }

    private List<String> splitTags(String value) {
        if (StrUtil.isBlank(value)) {
            return new ArrayList<String>();
        }
        Set<String> tags = new LinkedHashSet<String>();
        for (String token : value.split("[,，、/]")) {
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

    private boolean markStaleIfNeeded(LongTermMemoryFact fact, Instant now) {
        if (fact == null) {
            return true;
        }
        boolean stale = fact.isExpired(now);
        fact.setStale(stale);
        return stale;
    }

    private boolean conflictsWithCurrentPlan(LongTermMemoryFact fact, AgentExecutionPlan plan) {
        if (fact == null || plan == null) {
            return false;
        }
        String content = normalize(fact.getContent());
        if ("profile.frequent_areas".equals(fact.getType()) && StrUtil.isNotBlank(plan.getLocationHint())) {
            String current = normalize(plan.getLocationHint());
            return !current.contains(content) && !content.contains(current);
        }
        if ("preference.food".equals(fact.getType())) {
            for (String excluded : safeList(plan.getExcludedCategories())) {
                if (content.contains(normalize(excluded))) {
                    return true;
                }
            }
            for (String negative : safeList(plan.getNegativePreferences())) {
                if (content.contains(normalize(negative))) {
                    return true;
                }
            }
        }
        if ("avoidance.category".equals(fact.getType()) && StrUtil.isNotBlank(plan.getCategory())) {
            return normalize(plan.getCategory()).contains(content);
        }
        return false;
    }

    private String normalize(String value) {
        return StrUtil.blankToDefault(value, "").trim().toLowerCase(Locale.ROOT);
    }
}
