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
            3. Summarize tool outcomes briefly and never keep candidate lists.
            4. Keep only useful unresolved questions in openThreads.
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
        ConversationSummary summary = normalizeSummary(chatContextRepository.loadConversationSummary(conversationId));
        List<LongTermMemoryFact> memories = upsertLongTermMemories(userId, conversationId, currentMessage, executionPlan);

        int estimatedBefore = estimateTotalTokens(systemPrompt, currentMessage, prefetchedContext, retrievalHits, recentMessages, summary, memories);
        List<String> microCompactedItems = new ArrayList<String>();
        boolean microCompactTriggered = false;
        if (Boolean.TRUE.equals(properties.getMicroCompactEnabled())) {
            MicroCompactResult microCompactResult = microCompact(recentMessages, properties);
            recentMessages = microCompactResult.messages();
            microCompactedItems = microCompactResult.compactedItems();
            microCompactTriggered = !microCompactedItems.isEmpty();
        }

        boolean summaryUpdated = false;
        if (shouldCompact(recentMessages, currentMessage, estimatedBefore, executionPlan, properties)) {
            CompactResult compactResult = compactConversation(conversationId, recentMessages, summary, executionPlan, properties);
            recentMessages = compactResult.recentMessages();
            summary = compactResult.summary();
            summaryUpdated = compactResult.summaryUpdated();
        }

        List<LongTermMemoryFact> filteredMemories = memoryExtractionService.filterRelevantFacts(memories, executionPlan, Instant.now());
        List<SummarySlice> summarySlices = selectSummaryDocs(summary, currentMessage, executionPlan);
        List<LongTermMemoryFact> memoryHits = selectRelevantMemories(filteredMemories, currentMessage, executionPlan);
        List<Message> recentToInject = tailMessages(recentMessages, properties.getRecentTurnPairs() * 2);
        List<String> droppedContextKinds = new ArrayList<String>();

        AssemblyState state = new AssemblyState(summarySlices, memoryHits, recentToInject, droppedContextKinds);
        String promptContext = renderContext(state.summarySlices, state.memoryHits, state.recentMessages);
        int estimatedAfter = estimateTotalTokens(systemPrompt, currentMessage, prefetchedContext, retrievalHits,
                state.recentMessages, materializeSummary(state.summarySlices), state.memoryHits);

        while (estimatedAfter > properties.getHardTokenBudget()) {
            if (!dropLowestPriority(state)) {
                break;
            }
            promptContext = renderContext(state.summarySlices, state.memoryHits, state.recentMessages);
            estimatedAfter = estimateTotalTokens(systemPrompt, currentMessage, prefetchedContext, retrievalHits,
                    state.recentMessages, materializeSummary(state.summarySlices), state.memoryHits);
        }

        return new AssembledChatContext(
                promptContext,
                state.recentMessages.size(),
                state.summarySlices.size(),
                state.memoryHits.size(),
                estimatedBefore,
                estimatedAfter,
                summaryUpdated,
                microCompactTriggered,
                microCompactedItems,
                extractSummaryKinds(state.summarySlices),
                extractMemoryKinds(state.memoryHits),
                state.droppedContextKinds
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
        } else if (!memories.isEmpty()) {
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
                    || !safeList(executionPlan.getExcludedCategories()).isEmpty()
                    || !safeList(executionPlan.getNegativePreferences()).isEmpty()) {
                return true;
            }
        }
        String normalized = StrUtil.blankToDefault(currentMessage, "").toLowerCase(Locale.ROOT);
        return normalized.contains("预算")
                || normalized.contains("不要")
                || normalized.contains("两个人")
                || normalized.contains("约会")
                || normalized.contains("附近");
    }

    private MicroCompactResult microCompact(List<Message> currentMessages, AiContextCompressionProperties properties) {
        if (currentMessages == null || currentMessages.isEmpty()) {
            return new MicroCompactResult(new ArrayList<Message>(), new ArrayList<String>());
        }
        int protectedCount = Math.max(2, properties.getRecentTurnPairs() * 2);
        int keepRecentRounds = Math.max(1, properties.getMicroCompactKeepRecentToolRounds());
        int protectedFromIndex = Math.max(0, currentMessages.size() - Math.max(protectedCount, keepRecentRounds * 2));
        List<Message> compacted = new ArrayList<Message>();
        List<String> compactedItems = new ArrayList<String>();
        for (int i = 0; i < currentMessages.size(); i++) {
            Message message = currentMessages.get(i);
            if (i >= protectedFromIndex) {
                compacted.add(message);
                continue;
            }
            String text = StrUtil.blankToDefault(message.getText(), "");
            String replacement = microCompactReplacement(message, text);
            if (replacement != null) {
                compactedItems.add(replacement);
                compacted.add(copyMessage(message, replacement));
            } else {
                compacted.add(message);
            }
        }
        return new MicroCompactResult(compacted, unique(compactedItems));
    }

    private String microCompactReplacement(Message message, String text) {
        if (message instanceof UserMessage) {
            return null;
        }
        String normalized = normalize(text);
        if (normalized.isBlank()) {
            return "旧回答已折叠";
        }
        if (normalized.contains("recommendation#")
                || normalized.contains("shop#")
                || normalized.contains("prefetched business facts")
                || normalized.contains("background knowledge snippets")) {
            return "旧业务检索结果已折叠并转入摘要";
        }
        if (normalized.contains("address=")
                && normalized.contains("avgprice=")
                && normalized.contains("couponsummary=")) {
            return "旧店铺候选明细已折叠";
        }
        if (text.length() > 220) {
            return "冗长历史回答已压缩，关键信息保留在摘要中";
        }
        return null;
    }

    private Message copyMessage(Message message, String replacement) {
        if (message instanceof UserMessage) {
            return new UserMessage(replacement);
        }
        return new AssistantMessage(replacement);
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

        ConversationSummary mergedSummary = normalizeSummary(currentSummary);
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
                    return normalizeSummary(parsed);
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
            builder.append(objectMapper.writeValueAsString(normalizeSummary(currentSummary)));
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
        AiContextCompressionProperties properties = aiProperties.getContextCompression();
        ConversationSummary summary = normalizeSummary(new ConversationSummary());
        String allText = joinMessages(messages);
        summary.setUserGoal(lastUserMessage(messages));

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
            if (!(message instanceof AssistantMessage)) {
                continue;
            }
            if (containsAny(normalize(text), "recommendation#", "shop#", "avgprice=", "distance")) {
                summary.getToolOutcomes().add(extractToolOutcome(text));
            } else if (text.contains("推荐") || text.contains("地址") || text.contains("人均")) {
                summary.getToolOutcomes().add(trimForSummary(text));
            }
            if (text.contains("RAG") || text.contains("知识")) {
                summary.getRagTakeaways().add(trimForSummary(text));
            }
        }

        summary.setPersistentPreferences(unique(summary.getPersistentPreferences()));
        summary.setHardConstraints(unique(summary.getHardConstraints()));
        summary.setNegativePreferences(unique(summary.getNegativePreferences()));
        summary.setResolvedFacts(unique(summary.getResolvedFacts()));
        summary.setToolOutcomes(limit(unique(summary.getToolOutcomes()), properties.getSummaryToolOutcomeLimit()));
        summary.setRagTakeaways(limit(unique(summary.getRagTakeaways()), properties.getSummaryRagTakeawayLimit()));
        summary.setUpdatedAt(Instant.now());
        return summary;
    }

    private ConversationSummary mergeSummaries(ConversationSummary current, ConversationSummary delta) {
        AiContextCompressionProperties properties = aiProperties.getContextCompression();
        ConversationSummary merged = normalizeSummary(current);
        if (delta == null) {
            return merged;
        }
        if (StrUtil.isNotBlank(delta.getUserGoal())) {
            merged.setUserGoal(delta.getUserGoal());
        }
        merged.setPersistentPreferences(mergeStringLists(merged.getPersistentPreferences(), delta.getPersistentPreferences()));
        merged.setHardConstraints(mergeStringLists(merged.getHardConstraints(), delta.getHardConstraints()));
        merged.setNegativePreferences(mergeStringLists(merged.getNegativePreferences(), delta.getNegativePreferences()));
        merged.setResolvedFacts(mergeResolvedFacts(merged.getResolvedFacts(), delta.getResolvedFacts()));
        merged.setOpenThreads(limit(mergeOpenThreads(merged.getOpenThreads(), delta.getOpenThreads()), 4));
        merged.setToolOutcomes(limit(mergeStringLists(delta.getToolOutcomes(), merged.getToolOutcomes()), properties.getSummaryToolOutcomeLimit()));
        merged.setRagTakeaways(limit(mergeStringLists(delta.getRagTakeaways(), merged.getRagTakeaways()), properties.getSummaryRagTakeawayLimit()));
        merged.setUpdatedAt(Instant.now());
        return merged;
    }

    private List<SummarySlice> selectSummaryDocs(ConversationSummary summary,
                                                 String currentMessage,
                                                 AgentExecutionPlan executionPlan) {
        if (summary == null || !summary.hasContent()) {
            return new ArrayList<SummarySlice>();
        }
        List<SummarySlice> docs = new ArrayList<SummarySlice>();
        if (StrUtil.isNotBlank(summary.getUserGoal())) {
            docs.add(new SummarySlice("user_goal", "user_goal: " + summary.getUserGoal(), 1));
        }
        addDocs(docs, "persistent_preference", summary.getPersistentPreferences(), 3);
        addDocs(docs, "hard_constraint", summary.getHardConstraints(), 1);
        addDocs(docs, "negative_preference", summary.getNegativePreferences(), 1);
        addDocs(docs, "resolved_fact", summary.getResolvedFacts(), 2);
        addDocs(docs, "open_thread", summary.getOpenThreads(), 4);
        addDocs(docs, "tool_outcome", summary.getToolOutcomes(), 5);
        addDocs(docs, "rag_takeaway", summary.getRagTakeaways(), 6);
        return rankSummarySlices(resolveRankingQuery(currentMessage, executionPlan), docs, 6);
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
        hits.sort(Comparator.comparingInt(this::memoryPriority));
        return hits;
    }

    private List<SummarySlice> rankSummarySlices(String query, List<SummarySlice> slices, int limit) {
        if (slices.isEmpty()) {
            return slices;
        }
        List<String> docs = new ArrayList<String>();
        Map<String, SummarySlice> index = new LinkedHashMap<String, SummarySlice>();
        for (SummarySlice slice : slices) {
            docs.add(slice.text());
            index.put(slice.text(), slice);
        }
        List<String> rankedDocs = rankDocuments(query, docs, limit);
        List<SummarySlice> ranked = new ArrayList<SummarySlice>();
        for (String doc : rankedDocs) {
            SummarySlice slice = index.get(doc);
            if (slice != null) {
                ranked.add(slice);
            }
        }
        ranked.sort(Comparator.comparingInt(SummarySlice::priority));
        return ranked;
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
        if (normalizedDoc.contains("hard_constraint") || normalizedDoc.contains("negative_preference")) {
            score += 0.8D;
        }
        if (normalizedDoc.contains("resolved_fact") || normalizedDoc.contains("user_profile")) {
            score += 0.4D;
        }
        return score;
    }

    private Set<String> extractTerms(String query) {
        String normalized = normalize(query);
        Set<String> terms = new LinkedHashSet<String>();
        for (String token : normalized.split("[\\s,，。！？!.:/\\\\|]+")) {
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
            parts.addAll(safeList(executionPlan.getNegativePreferences()));
        }
        parts.add(currentMessage);
        return String.join(" ", parts);
    }

    private String renderContext(List<SummarySlice> summaryDocs,
                                 List<LongTermMemoryFact> memoryHits,
                                 List<Message> recentMessages) {
        StringBuilder builder = new StringBuilder();
        if (!summaryDocs.isEmpty()) {
            builder.append("\nConversation summary context:\n");
            for (SummarySlice doc : summaryDocs) {
                builder.append("- ").append(doc.text()).append("\n");
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

    private ConversationSummary materializeSummary(List<SummarySlice> docs) {
        ConversationSummary summary = normalizeSummary(new ConversationSummary());
        for (SummarySlice doc : docs) {
            if ("hard_constraint".equals(doc.kind())) {
                summary.getHardConstraints().add(doc.text());
            } else if ("negative_preference".equals(doc.kind())) {
                summary.getNegativePreferences().add(doc.text());
            } else {
                summary.getResolvedFacts().add(doc.text());
            }
        }
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

    private boolean dropLowestPriority(AssemblyState state) {
        for (int priority = 6; priority >= 1; priority--) {
            int removed = removeSummaryByPriority(state.summarySlices, priority);
            if (removed > 0) {
                state.droppedContextKinds.add("summary:" + removedKind(priority));
                return true;
            }
        }
        if (!state.memoryHits.isEmpty()) {
            LongTermMemoryFact removed = state.memoryHits.remove(state.memoryHits.size() - 1);
            state.droppedContextKinds.add("memory:" + removed.getType());
            return true;
        }
        if (state.recentMessages.size() > 2) {
            state.recentMessages = tailMessages(state.recentMessages, state.recentMessages.size() - 2);
            state.droppedContextKinds.add("recent_turns");
            return true;
        }
        return false;
    }

    private int removeSummaryByPriority(List<SummarySlice> slices, int priority) {
        for (int i = slices.size() - 1; i >= 0; i--) {
            if (slices.get(i).priority() == priority) {
                slices.remove(i);
                return 1;
            }
        }
        return 0;
    }

    private String removedKind(int priority) {
        return switch (priority) {
            case 6 -> "rag_takeaway";
            case 5 -> "tool_outcome";
            case 4 -> "open_thread";
            case 3 -> "persistent_preference";
            case 2 -> "resolved_fact";
            default -> "hard_constraint";
        };
    }

    private int memoryPriority(LongTermMemoryFact fact) {
        if (fact == null || fact.getType() == null) {
            return 10;
        }
        if (fact.getType().startsWith("avoidance")) {
            return 1;
        }
        if (fact.getType().startsWith("profile.city")) {
            return 2;
        }
        if (fact.getType().startsWith("profile.frequent_areas")) {
            return 3;
        }
        if (fact.getType().startsWith("preference.price_band") || fact.getType().startsWith("preference.party_size")) {
            return 4;
        }
        return 5;
    }

    private List<Message> tailMessages(List<Message> messages, int keepCount) {
        if (messages == null || messages.isEmpty()) {
            return new ArrayList<Message>();
        }
        int fromIndex = Math.max(0, messages.size() - Math.max(1, keepCount));
        return new ArrayList<Message>(messages.subList(fromIndex, messages.size()));
    }

    private List<String> mergeStringLists(List<String> first, List<String> second) {
        Set<String> merged = new LinkedHashSet<String>();
        if (first != null) {
            merged.addAll(first);
        }
        if (second != null) {
            merged.addAll(second);
        }
        return new ArrayList<String>(merged);
    }

    private List<String> mergeResolvedFacts(List<String> base, List<String> append) {
        Map<String, String> merged = new LinkedHashMap<String, String>();
        for (String value : safeList(base)) {
            merged.put(factKey(value), value);
        }
        for (String value : safeList(append)) {
            merged.put(factKey(value), value);
        }
        return new ArrayList<String>(merged.values());
    }

    private List<String> mergeOpenThreads(List<String> base, List<String> append) {
        List<String> merged = new ArrayList<String>();
        for (String value : safeList(base)) {
            if (!containsClosedMarker(value)) {
                merged.add(value);
            }
        }
        for (String value : safeList(append)) {
            if (!containsClosedMarker(value)) {
                merged.add(value);
            }
        }
        return unique(merged);
    }

    private boolean containsClosedMarker(String value) {
        String normalized = normalize(value);
        return normalized.contains("已解决") || normalized.contains("已确认") || normalized.contains("已完成");
    }

    private String factKey(String value) {
        String normalized = normalize(value);
        int index = normalized.indexOf(':');
        return index > 0 ? normalized.substring(0, index) : normalized;
    }

    private List<String> splitTags(String value) {
        if (StrUtil.isBlank(value)) {
            return Collections.emptyList();
        }
        Set<String> tags = new LinkedHashSet<String>();
        for (String token : value.split("[,，、/]")) {
            if (StrUtil.isNotBlank(token)) {
                tags.add(token.trim());
            }
        }
        return new ArrayList<String>(tags);
    }

    private List<String> safeList(List<String> values) {
        return values == null ? new ArrayList<String>() : values;
    }

    private void addDocs(List<SummarySlice> docs, String prefix, List<String> values, int priority) {
        for (String value : safeList(values)) {
            docs.add(new SummarySlice(prefix, prefix + ": " + value, priority));
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

    private String extractToolOutcome(String text) {
        String normalized = trimForSummary(text);
        if (normalized.contains("recommendation#") || normalized.contains("shop#")) {
            return "已执行过店铺检索，候选结果已写入摘要";
        }
        return normalized;
    }

    private Long extractBudget(String source) {
        Matcher matcher = BUDGET_PATTERN.matcher(StrUtil.blankToDefault(source, ""));
        return matcher.find() ? Long.parseLong(matcher.group(1)) : null;
    }

    private ConversationSummary normalizeSummary(ConversationSummary summary) {
        ConversationSummary normalized = summary == null ? new ConversationSummary() : summary;
        if (normalized.getPersistentPreferences() == null) {
            normalized.setPersistentPreferences(new ArrayList<String>());
        }
        if (normalized.getHardConstraints() == null) {
            normalized.setHardConstraints(new ArrayList<String>());
        }
        if (normalized.getNegativePreferences() == null) {
            normalized.setNegativePreferences(new ArrayList<String>());
        }
        if (normalized.getResolvedFacts() == null) {
            normalized.setResolvedFacts(new ArrayList<String>());
        }
        if (normalized.getOpenThreads() == null) {
            normalized.setOpenThreads(new ArrayList<String>());
        }
        if (normalized.getToolOutcomes() == null) {
            normalized.setToolOutcomes(new ArrayList<String>());
        }
        if (normalized.getRagTakeaways() == null) {
            normalized.setRagTakeaways(new ArrayList<String>());
        }
        return normalized;
    }

    private String normalize(String value) {
        return StrUtil.blankToDefault(value, "").trim().toLowerCase(Locale.ROOT);
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

    private <T> List<T> limit(List<T> values, Integer limit) {
        int safeLimit = limit == null ? Integer.MAX_VALUE : limit;
        if (values == null || values.size() <= safeLimit) {
            return values == null ? new ArrayList<T>() : values;
        }
        return new ArrayList<T>(values.subList(0, safeLimit));
    }

    private List<String> unique(List<String> values) {
        if (values == null || values.isEmpty()) {
            return new ArrayList<String>();
        }
        return new ArrayList<String>(new LinkedHashSet<String>(values));
    }

    private List<String> extractSummaryKinds(List<SummarySlice> slices) {
        List<String> kinds = new ArrayList<String>();
        for (SummarySlice slice : slices) {
            kinds.add(slice.kind());
        }
        return unique(kinds);
    }

    private List<String> extractMemoryKinds(List<LongTermMemoryFact> memoryHits) {
        List<String> kinds = new ArrayList<String>();
        for (LongTermMemoryFact fact : memoryHits) {
            kinds.add(fact.getType());
        }
        return unique(kinds);
    }

    private record CompactResult(List<Message> recentMessages,
                                 ConversationSummary summary,
                                 boolean summaryUpdated) {
    }

    private record MicroCompactResult(List<Message> messages,
                                      List<String> compactedItems) {
    }

    private record SummarySlice(String kind, String text, int priority) {
    }

    private static class AssemblyState {
        private List<SummarySlice> summarySlices;
        private List<LongTermMemoryFact> memoryHits;
        private List<Message> recentMessages;
        private final List<String> droppedContextKinds;

        private AssemblyState(List<SummarySlice> summarySlices,
                              List<LongTermMemoryFact> memoryHits,
                              List<Message> recentMessages,
                              List<String> droppedContextKinds) {
            this.summarySlices = new ArrayList<SummarySlice>(summarySlices);
            this.memoryHits = new ArrayList<LongTermMemoryFact>(memoryHits);
            this.recentMessages = new ArrayList<Message>(recentMessages);
            this.droppedContextKinds = droppedContextKinds;
        }
    }
}
