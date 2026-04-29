package com.hmdp.ai;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.rag.DashScopeRerankService;
import com.hmdp.config.AiContextCompressionProperties;
import com.hmdp.config.AiProperties;
import com.hmdp.dto.AgentExecutionPlan;
import com.hmdp.dto.AiRetrievalHit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class ContextCompressionService {

    private static final Pattern BUDGET_PATTERN =
            Pattern.compile("(?:人均|预算|不超过|控制在|价格)?\\s*(\\d{2,4})\\s*(?:元|块)?\\s*(?:以内|以下|之内)?");

    private static final String SUMMARY_PROMPT = """
            You compress conversation history for Zyro's local-life agent.
            Output JSON only.

            Required JSON fields:
            - userGoal
            - persistentPreferences
            - hardConstraints
            - negativePreferences
            - resolvedFacts
            - openThreads
            - toolOutcomes
            - ragTakeaways

            Rules:
            1. Keep stable user preferences and hard business constraints.
            2. Preserve location, budget, party size, wanted category and forbidden category when present.
            3. Summarize tool outcomes briefly. Do not keep long candidate lists.
            4. Keep unresolved questions in openThreads.
            5. Use concise Chinese strings.
            6. Output valid JSON only.
            """;

    private final AiProperties aiProperties;
    private final ChatMemory chatMemory;
    private final ChatContextRepository chatContextRepository;
    private final TokenBudgetEstimator tokenBudgetEstimator;
    private final MemoryExtractionService memoryExtractionService;
    private final ObjectProvider<ChatClient.Builder> chatClientBuilderProvider;
    private final ObjectProvider<DashScopeRerankService> rerankServiceProvider;
    private final ObjectMapper objectMapper;

    public ContextCompressionService(AiProperties aiProperties,
                                     ChatMemory chatMemory,
                                     ChatContextRepository chatContextRepository,
                                     TokenBudgetEstimator tokenBudgetEstimator,
                                     MemoryExtractionService memoryExtractionService,
                                     ObjectProvider<ChatClient.Builder> chatClientBuilderProvider,
                                     ObjectProvider<DashScopeRerankService> rerankServiceProvider,
                                     ObjectMapper objectMapper) {
        this.aiProperties = aiProperties;
        this.chatMemory = chatMemory;
        this.chatContextRepository = chatContextRepository;
        this.tokenBudgetEstimator = tokenBudgetEstimator;
        this.memoryExtractionService = memoryExtractionService;
        this.chatClientBuilderProvider = chatClientBuilderProvider;
        this.rerankServiceProvider = rerankServiceProvider;
        this.objectMapper = objectMapper;
    }

    public AssembledChatContext assemble(String conversationId,
                                         Long userId,
                                         String currentMessage,
                                         AgentExecutionPlan executionPlan,
                                         String systemPrompt,
                                         String prefetchedContext,
                                         List<AiRetrievalHit> retrievalHits) {
        AiContextCompressionProperties properties = aiProperties.getContextCompression();
        if (!Boolean.TRUE.equals(properties.getEnabled())) {
            return AssembledChatContext.empty();
        }

        List<Message> recentMessages = new ArrayList<Message>(chatMemory.get(conversationId));
        ConversationSummary summary = chatContextRepository.loadConversationSummary(conversationId);
        List<LongTermMemoryFact> memories = upsertLongTermMemories(userId, conversationId, currentMessage, executionPlan);

        int estimatedBefore = estimateTotalTokens(systemPrompt, currentMessage, prefetchedContext, retrievalHits, recentMessages, summary, memories);
        boolean summaryUpdated = false;

        if (shouldCompact(recentMessages, currentMessage, estimatedBefore, executionPlan, properties)) {
            CompactResult compactResult = compactConversation(conversationId, recentMessages, summary, executionPlan, properties);
            recentMessages = compactResult.recentMessages();
            summary = compactResult.summary();
            summaryUpdated = compactResult.summaryUpdated();
        }

        List<String> summaryDocs = selectSummaryDocs(summary, currentMessage, executionPlan);
        List<LongTermMemoryFact> memoryHits = selectRelevantMemories(memories, currentMessage, executionPlan);
        List<Message> recentToInject = tailMessages(recentMessages, properties.getRecentTurnPairs() * 2);

        String promptContext = renderContext(summaryDocs, memoryHits, recentToInject);
        int estimatedAfter = estimateTotalTokens(systemPrompt, currentMessage, prefetchedContext, retrievalHits,
                recentToInject, materializeSummary(summaryDocs), memoryHits);

        while (estimatedAfter > properties.getHardTokenBudget()
                && (!memoryHits.isEmpty() || !summaryDocs.isEmpty() || recentToInject.size() > 2)) {
            if (!memoryHits.isEmpty()) {
                memoryHits = new ArrayList<LongTermMemoryFact>(memoryHits.subList(0, memoryHits.size() - 1));
            } else if (!summaryDocs.isEmpty()) {
                summaryDocs = new ArrayList<String>(summaryDocs.subList(0, summaryDocs.size() - 1));
            } else {
                recentToInject = tailMessages(recentToInject, Math.max(2, recentToInject.size() - 2));
            }
            promptContext = renderContext(summaryDocs, memoryHits, recentToInject);
            estimatedAfter = estimateTotalTokens(systemPrompt, currentMessage, prefetchedContext, retrievalHits,
                    recentToInject, materializeSummary(summaryDocs), memoryHits);
        }

        return new AssembledChatContext(
                promptContext,
                recentToInject.size(),
                summaryDocs.size(),
                memoryHits.size(),
                estimatedBefore,
                estimatedAfter,
                summaryUpdated
        );
    }

    public void recordTurn(String conversationId,
                           Long userId,
                           String message,
                           String answer,
                           AgentExecutionPlan executionPlan) {
        AiContextCompressionProperties properties = aiProperties.getContextCompression();
        List<Message> current = new ArrayList<Message>(chatMemory.get(conversationId));
        current.add(new UserMessage(StrUtil.blankToDefault(message, "")));
        current.add(new AssistantMessage(StrUtil.blankToDefault(answer, "")));
        chatMemory.clear(conversationId);
        chatMemory.add(conversationId, current);

        if (!Boolean.TRUE.equals(properties.getEnabled())) {
            return;
        }
        List<LongTermMemoryFact> memories = upsertLongTermMemories(userId, conversationId, message, executionPlan);
        if (current.size() >= properties.getSummaryTriggerMessageCount()) {
            compactConversation(conversationId, current, chatContextRepository.loadConversationSummary(conversationId), executionPlan, properties);
        } else if (memories != null && !memories.isEmpty()) {
            chatContextRepository.saveLongTermMemories(userId, memories);
        }
    }

    public void clearConversation(String conversationId) {
        chatMemory.clear(conversationId);
        chatContextRepository.clearConversationSummary(conversationId);
    }

    private boolean shouldCompact(List<Message> recentMessages,
                                  String currentMessage,
                                  int estimatedBefore,
                                  AgentExecutionPlan executionPlan,
                                  AiContextCompressionProperties properties) {
        if (recentMessages.size() >= properties.getSummaryTriggerMessageCount()) {
            return true;
        }
        if (estimatedBefore > properties.getSoftTokenBudget()) {
            return true;
        }
        return hasHighValueSignal(currentMessage, executionPlan) && recentMessages.size() > properties.getRecentTurnPairs() * 2;
    }

    private boolean hasHighValueSignal(String currentMessage, AgentExecutionPlan executionPlan) {
        if (executionPlan != null) {
            if (executionPlan.getBudgetMax() != null
                    || executionPlan.getPartySize() != null
                    || StrUtil.isNotBlank(executionPlan.getCity())
                    || StrUtil.isNotBlank(executionPlan.getLocationHint())
                    || (executionPlan.getExcludedCategories() != null && !executionPlan.getExcludedCategories().isEmpty())
                    || (executionPlan.getNegativePreferences() != null && !executionPlan.getNegativePreferences().isEmpty())) {
                return true;
            }
        }
        String normalized = StrUtil.blankToDefault(currentMessage, "").toLowerCase(Locale.ROOT);
        return normalized.contains("预算") || normalized.contains("不要") || normalized.contains("两个人")
                || normalized.contains("约会") || normalized.contains("附近");
    }

    private CompactResult compactConversation(String conversationId,
                                              List<Message> currentMessages,
                                              ConversationSummary currentSummary,
                                              AgentExecutionPlan executionPlan,
                                              AiContextCompressionProperties properties) {
        int keepCount = Math.max(2, properties.getRecentTurnPairs() * 2);
        List<Message> recent = tailMessages(currentMessages, keepCount);
        List<Message> older = currentMessages.size() > recent.size()
                ? new ArrayList<Message>(currentMessages.subList(0, currentMessages.size() - recent.size()))
                : new ArrayList<Message>();

        ConversationSummary mergedSummary = currentSummary == null ? new ConversationSummary() : currentSummary;
        if (!older.isEmpty()) {
            ConversationSummary deltaSummary = summarizeMessages(older, executionPlan, mergedSummary);
            mergedSummary = mergeSummaries(mergedSummary, deltaSummary);
            mergedSummary.setUpdatedAt(Instant.now());
            chatContextRepository.saveConversationSummary(conversationId, mergedSummary);
            chatMemory.clear(conversationId);
            if (!recent.isEmpty()) {
                chatMemory.add(conversationId, recent);
            }
            return new CompactResult(recent, mergedSummary, true);
        }
        return new CompactResult(recent, mergedSummary, false);
    }

    private ConversationSummary summarizeMessages(List<Message> messages,
                                                  AgentExecutionPlan executionPlan,
                                                  ConversationSummary currentSummary) {
        ChatClient.Builder builder = chatClientBuilderProvider.getIfAvailable();
        if (builder != null) {
            try {
                String content = builder.clone()
                        .build()
                        .prompt()
                        .system(SUMMARY_PROMPT)
                        .user(buildSummaryUserPrompt(messages, executionPlan, currentSummary))
                        .stream()
                        .content()
                        .reduce(new StringBuilder(), StringBuilder::append)
                        .map(StringBuilder::toString)
                        .block();
                ConversationSummary parsed = parseSummary(content);
                if (parsed != null && parsed.hasContent()) {
                    return parsed;
                }
            } catch (Exception e) {
                log.debug("Structured summary generation failed, fallback to heuristic summary: {}", e.getMessage());
            }
        }
        return heuristicSummary(messages, executionPlan);
    }

    private ConversationSummary parseSummary(String content) {
        if (StrUtil.isBlank(content)) {
            return null;
        }
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            return objectMapper.readValue(content.substring(start, end + 1), ConversationSummary.class);
        } catch (Exception e) {
            return null;
        }
    }

    private String buildSummaryUserPrompt(List<Message> messages,
                                          AgentExecutionPlan executionPlan,
                                          ConversationSummary currentSummary) {
        StringBuilder builder = new StringBuilder();
        builder.append("Current summary JSON:\n");
        try {
            builder.append(objectMapper.writeValueAsString(currentSummary == null ? new ConversationSummary() : currentSummary));
        } catch (Exception e) {
            builder.append("{}");
        }
        builder.append("\n\nOlder conversation transcript:\n");
        for (Message message : messages) {
            builder.append(label(message)).append(": ").append(StrUtil.blankToDefault(message.getText(), "")).append("\n");
        }
        if (executionPlan != null) {
            builder.append("\nCurrent plan hint:\n");
            builder.append("intent=").append(StrUtil.blankToDefault(executionPlan.getIntent(), "general"));
            builder.append(", city=").append(StrUtil.blankToDefault(executionPlan.getCity(), "-"));
            builder.append(", locationHint=").append(StrUtil.blankToDefault(executionPlan.getLocationHint(), "-"));
            builder.append(", category=").append(StrUtil.blankToDefault(executionPlan.getSubcategory(), executionPlan.getCategory()));
            builder.append(", budget=").append(executionPlan.getBudgetMax());
        }
        return builder.toString();
    }

    private ConversationSummary heuristicSummary(List<Message> messages, AgentExecutionPlan executionPlan) {
        ConversationSummary summary = new ConversationSummary();
        String allText = joinMessages(messages);
        summary.setUserGoal(lastUserMessage(messages));
        summary.setPersistentPreferences(new ArrayList<String>());
        summary.setHardConstraints(new ArrayList<String>());
        summary.setNegativePreferences(new ArrayList<String>());
        summary.setResolvedFacts(new ArrayList<String>());
        summary.setOpenThreads(new ArrayList<String>());
        summary.setToolOutcomes(new ArrayList<String>());
        summary.setRagTakeaways(new ArrayList<String>());

        if (executionPlan != null) {
            if (StrUtil.isNotBlank(executionPlan.getCity())) {
                summary.getResolvedFacts().add("当前关注城市: " + executionPlan.getCity());
            }
            if (StrUtil.isNotBlank(executionPlan.getLocationHint())) {
                summary.getResolvedFacts().add("位置偏好: " + executionPlan.getLocationHint());
            }
            if (executionPlan.getBudgetMax() != null) {
                summary.getHardConstraints().add("预算不高于 " + executionPlan.getBudgetMax() + " 元");
            } else {
                Long budget = extractBudget(allText);
                if (budget != null) {
                    summary.getHardConstraints().add("预算不高于 " + budget + " 元");
                }
            }
            if (executionPlan.getPartySize() != null) {
                summary.getHardConstraints().add("人数约 " + executionPlan.getPartySize() + " 人");
            }
            if (StrUtil.isNotBlank(executionPlan.getQualityPreference())) {
                summary.getPersistentPreferences().addAll(splitTags(executionPlan.getQualityPreference()));
            }
            summary.getNegativePreferences().addAll(safeList(executionPlan.getNegativePreferences()));
            for (String category : safeList(executionPlan.getExcludedCategories())) {
                summary.getHardConstraints().add("排除类目: " + category);
            }
        }

        for (Message message : messages) {
            String text = StrUtil.blankToDefault(message.getText(), "");
            if (message instanceof AssistantMessage) {
                if (text.contains("推荐") || text.contains("地址") || text.contains("人均")) {
                    summary.getToolOutcomes().add(trimForSummary(text));
                }
                if (text.contains("RAG") || text.contains("知识")) {
                    summary.getRagTakeaways().add(trimForSummary(text));
                }
            }
        }
        summary.setPersistentPreferences(unique(summary.getPersistentPreferences()));
        summary.setHardConstraints(unique(summary.getHardConstraints()));
        summary.setNegativePreferences(unique(summary.getNegativePreferences()));
        summary.setResolvedFacts(unique(summary.getResolvedFacts()));
        summary.setToolOutcomes(limit(unique(summary.getToolOutcomes()), 4));
        summary.setRagTakeaways(limit(unique(summary.getRagTakeaways()), 3));
        summary.setUpdatedAt(Instant.now());
        return summary;
    }

    private ConversationSummary mergeSummaries(ConversationSummary current, ConversationSummary delta) {
        ConversationSummary merged = current == null ? new ConversationSummary() : current;
        if (delta == null) {
            return merged;
        }
        if (StrUtil.isNotBlank(delta.getUserGoal())) {
            merged.setUserGoal(delta.getUserGoal());
        }
        merged.setPersistentPreferences(mergeStringLists(merged.getPersistentPreferences(), delta.getPersistentPreferences()));
        merged.setHardConstraints(mergeStringLists(merged.getHardConstraints(), delta.getHardConstraints()));
        merged.setNegativePreferences(mergeStringLists(merged.getNegativePreferences(), delta.getNegativePreferences()));
        merged.setResolvedFacts(mergeStringLists(merged.getResolvedFacts(), delta.getResolvedFacts()));
        merged.setOpenThreads(mergeStringLists(merged.getOpenThreads(), delta.getOpenThreads()));
        merged.setToolOutcomes(limit(mergeStringLists(merged.getToolOutcomes(), delta.getToolOutcomes()), 6));
        merged.setRagTakeaways(limit(mergeStringLists(merged.getRagTakeaways(), delta.getRagTakeaways()), 4));
        merged.setUpdatedAt(Instant.now());
        return merged;
    }

    private List<String> selectSummaryDocs(ConversationSummary summary,
                                           String currentMessage,
                                           AgentExecutionPlan executionPlan) {
        if (summary == null || !summary.hasContent()) {
            return new ArrayList<String>();
        }
        List<String> docs = new ArrayList<String>();
        if (StrUtil.isNotBlank(summary.getUserGoal())) {
            docs.add("user_goal: " + summary.getUserGoal());
        }
        addDocs(docs, "persistent_preference", summary.getPersistentPreferences());
        addDocs(docs, "hard_constraint", summary.getHardConstraints());
        addDocs(docs, "negative_preference", summary.getNegativePreferences());
        addDocs(docs, "resolved_fact", summary.getResolvedFacts());
        addDocs(docs, "open_thread", summary.getOpenThreads());
        addDocs(docs, "tool_outcome", summary.getToolOutcomes());
        addDocs(docs, "rag_takeaway", summary.getRagTakeaways());
        return rankDocuments(resolveRankingQuery(currentMessage, executionPlan), docs, 5);
    }

    private List<LongTermMemoryFact> selectRelevantMemories(List<LongTermMemoryFact> memories,
                                                            String currentMessage,
                                                            AgentExecutionPlan executionPlan) {
        if (memories == null || memories.isEmpty()) {
            return new ArrayList<LongTermMemoryFact>();
        }
        List<String> docs = new ArrayList<String>();
        Map<String, LongTermMemoryFact> index = new LinkedHashMap<String, LongTermMemoryFact>();
        for (LongTermMemoryFact fact : memories) {
            String text = fact.toRetrievableText();
            docs.add(text);
            index.put(text, fact);
        }
        List<String> ranked = rankDocuments(resolveRankingQuery(currentMessage, executionPlan), docs,
                Math.max(1, aiProperties.getContextCompression().getMemoryTopK()));
        List<LongTermMemoryFact> hits = new ArrayList<LongTermMemoryFact>();
        for (String doc : ranked) {
            LongTermMemoryFact fact = index.get(doc);
            if (fact != null) {
                hits.add(fact);
            }
        }
        return hits;
    }

    private List<String> rankDocuments(String query, List<String> documents, int limit) {
        if (documents == null || documents.isEmpty()) {
            return new ArrayList<String>();
        }
        DashScopeRerankService rerankService = rerankServiceProvider.getIfAvailable();
        if (rerankService != null) {
            try {
                List<DashScopeRerankService.RerankResult> results = rerankService.rerank(query, documents, limit);
                if (!results.isEmpty()) {
                    List<String> ranked = new ArrayList<String>();
                    for (DashScopeRerankService.RerankResult result : results) {
                        if (result.index() >= 0 && result.index() < documents.size()) {
                            ranked.add(documents.get(result.index()));
                        }
                    }
                    if (!ranked.isEmpty()) {
                        return ranked;
                    }
                }
            } catch (Exception e) {
                log.debug("Rerank documents failed, fallback to lexical ranking: {}", e.getMessage());
            }
        }
        List<String> sorted = new ArrayList<String>(documents);
        sorted.sort(Comparator.comparingDouble((String doc) -> lexicalScore(query, doc)).reversed());
        return limit(sorted, limit);
    }

    private double lexicalScore(String query, String doc) {
        Set<String> terms = extractTerms(query);
        if (terms.isEmpty()) {
            return 0D;
        }
        String normalizedDoc = normalize(doc);
        double score = 0D;
        for (String term : terms) {
            if (normalizedDoc.contains(term)) {
                score += Math.max(1D, term.length() / 2.0D);
            }
        }
        if (normalizedDoc.contains("hard_constraint") || normalizedDoc.contains("avoidance")) {
            score += 0.6D;
        }
        return score;
    }

    private Set<String> extractTerms(String query) {
        String normalized = normalize(query);
        Set<String> terms = new LinkedHashSet<String>();
        for (String token : normalized.split("[\\s,，。！？?!.:/\\\\|]+")) {
            if (token.length() >= 2) {
                terms.add(token);
            }
        }
        if (terms.isEmpty() && normalized.length() >= 2) {
            terms.add(normalized);
        }
        return terms;
    }

    private String resolveRankingQuery(String currentMessage, AgentExecutionPlan executionPlan) {
        List<String> parts = new ArrayList<String>();
        if (executionPlan != null) {
            if (StrUtil.isNotBlank(executionPlan.getRetrievalQuery())) {
                parts.add(executionPlan.getRetrievalQuery());
            }
            if (StrUtil.isNotBlank(executionPlan.getLocationHint())) {
                parts.add(executionPlan.getLocationHint());
            }
            if (StrUtil.isNotBlank(executionPlan.getSubcategory())) {
                parts.add(executionPlan.getSubcategory());
            }
            if (executionPlan.getBudgetMax() != null) {
                parts.add(String.valueOf(executionPlan.getBudgetMax()));
            }
        }
        parts.add(currentMessage);
        return String.join(" ", parts);
    }

    private String renderContext(List<String> summaryDocs,
                                 List<LongTermMemoryFact> memoryHits,
                                 List<Message> recentMessages) {
        StringBuilder builder = new StringBuilder();
        if (!summaryDocs.isEmpty()) {
            builder.append("\nConversation summary context:\n");
            for (String doc : summaryDocs) {
                builder.append("- ").append(doc).append("\n");
            }
        }
        if (!memoryHits.isEmpty()) {
            builder.append("\nRelevant long-term memory:\n");
            for (LongTermMemoryFact fact : memoryHits) {
                builder.append("- ").append(fact.toRetrievableText()).append("\n");
            }
        }
        if (!recentMessages.isEmpty()) {
            builder.append("\nRecent conversation transcript:\n");
            for (Message message : recentMessages) {
                builder.append(label(message)).append(": ")
                        .append(StrUtil.blankToDefault(message.getText(), ""))
                        .append("\n");
            }
        }
        return builder.toString().trim();
    }

    private ConversationSummary materializeSummary(List<String> docs) {
        ConversationSummary summary = new ConversationSummary();
        if (docs == null) {
            return summary;
        }
        summary.setResolvedFacts(new ArrayList<String>(docs));
        return summary;
    }

    private List<LongTermMemoryFact> upsertLongTermMemories(Long userId,
                                                            String conversationId,
                                                            String currentMessage,
                                                            AgentExecutionPlan executionPlan) {
        if (userId == null || !Boolean.TRUE.equals(aiProperties.getContextCompression().getLongTermMemoryEnabled())) {
            return new ArrayList<LongTermMemoryFact>();
        }
        List<LongTermMemoryFact> existing = chatContextRepository.loadLongTermMemories(userId);
        List<LongTermMemoryFact> extracted = memoryExtractionService.extractFacts(userId, conversationId, currentMessage, executionPlan);
        List<LongTermMemoryFact> merged = memoryExtractionService.mergeFacts(existing, extracted);
        chatContextRepository.saveLongTermMemories(userId, merged);
        return merged;
    }

    private int estimateTotalTokens(String systemPrompt,
                                    String currentMessage,
                                    String prefetchedContext,
                                    List<AiRetrievalHit> retrievalHits,
                                    List<Message> recentMessages,
                                    ConversationSummary summary,
                                    List<LongTermMemoryFact> memories) {
        int total = tokenBudgetEstimator.estimateTextTokens(systemPrompt);
        total += tokenBudgetEstimator.estimateTextTokens(currentMessage);
        total += tokenBudgetEstimator.estimateTextTokens(prefetchedContext);
        total += tokenBudgetEstimator.estimateMessagesTokens(recentMessages);
        total += tokenBudgetEstimator.estimateSummaryTokens(summary);
        total += tokenBudgetEstimator.estimateMemoryTokens(memories);
        total += estimateRetrievalHits(retrievalHits);
        return total;
    }

    private int estimateRetrievalHits(List<AiRetrievalHit> retrievalHits) {
        if (retrievalHits == null || retrievalHits.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (AiRetrievalHit hit : retrievalHits) {
            total += tokenBudgetEstimator.estimateTextTokens(hit.getTitle());
            total += tokenBudgetEstimator.estimateTextTokens(hit.getSnippet());
            total += 5;
        }
        return total;
    }

    private List<Message> tailMessages(List<Message> messages, int keepCount) {
        if (messages == null || messages.isEmpty()) {
            return new ArrayList<Message>();
        }
        int fromIndex = Math.max(0, messages.size() - Math.max(1, keepCount));
        return new ArrayList<Message>(messages.subList(fromIndex, messages.size()));
    }

    private List<String> mergeStringLists(List<String> base, List<String> append) {
        Set<String> merged = new LinkedHashSet<String>();
        if (base != null) {
            merged.addAll(base);
        }
        if (append != null) {
            merged.addAll(append);
        }
        return new ArrayList<String>(merged);
    }

    private List<String> splitTags(String value) {
        if (StrUtil.isBlank(value)) {
            return Collections.emptyList();
        }
        Set<String> tags = new LinkedHashSet<String>();
        for (String token : value.split("[,，]")) {
            if (StrUtil.isNotBlank(token)) {
                tags.add(token.trim());
            }
        }
        return new ArrayList<String>(tags);
    }

    private List<String> safeList(List<String> values) {
        return values == null ? new ArrayList<String>() : values;
    }

    private void addDocs(List<String> docs, String prefix, List<String> values) {
        for (String value : safeList(values)) {
            docs.add(prefix + ": " + value);
        }
    }

    private String label(Message message) {
        return message instanceof AssistantMessage ? "Assistant" : "User";
    }

    private String joinMessages(List<Message> messages) {
        StringBuilder builder = new StringBuilder();
        for (Message message : messages) {
            builder.append(label(message)).append(": ")
                    .append(StrUtil.blankToDefault(message.getText(), ""))
                    .append('\n');
        }
        return builder.toString();
    }

    private String lastUserMessage(List<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message message = messages.get(i);
            if (message instanceof UserMessage) {
                return trimForSummary(message.getText());
            }
        }
        return null;
    }

    private String trimForSummary(String text) {
        String normalized = StrUtil.blankToDefault(text, "").trim();
        if (normalized.length() <= 100) {
            return normalized;
        }
        return normalized.substring(0, 100) + "...";
    }

    private Long extractBudget(String source) {
        Matcher matcher = BUDGET_PATTERN.matcher(StrUtil.blankToDefault(source, ""));
        return matcher.find() ? Long.parseLong(matcher.group(1)) : null;
    }

    private String normalize(String value) {
        return StrUtil.blankToDefault(value, "").trim().toLowerCase(Locale.ROOT);
    }

    private <T> List<T> limit(List<T> values, int limit) {
        if (values == null || values.size() <= limit) {
            return values == null ? new ArrayList<T>() : values;
        }
        return new ArrayList<T>(values.subList(0, limit));
    }

    private List<String> unique(List<String> values) {
        if (values == null || values.isEmpty()) {
            return new ArrayList<String>();
        }
        return new ArrayList<String>(new LinkedHashSet<String>(values));
    }

    private record CompactResult(List<Message> recentMessages,
                                 ConversationSummary summary,
                                 boolean summaryUpdated) {
    }
}
