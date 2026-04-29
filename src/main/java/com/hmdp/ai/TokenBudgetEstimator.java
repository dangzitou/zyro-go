package com.hmdp.ai;

import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class TokenBudgetEstimator {

    public int estimateTextTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int chineseOrSymbolCount = 0;
        int asciiCount = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isWhitespace(ch)) {
                continue;
            }
            if (ch <= 127) {
                asciiCount++;
            } else {
                chineseOrSymbolCount++;
            }
        }
        return chineseOrSymbolCount + Math.max(1, (int) Math.ceil(asciiCount / 4.0D));
    }

    public int estimateMessagesTokens(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (Message message : messages) {
            total += 6;
            total += estimateTextTokens(message == null ? null : message.getText());
        }
        return total;
    }

    public int estimateSummaryTokens(ConversationSummary summary) {
        if (summary == null) {
            return 0;
        }
        int total = estimateTextTokens(summary.getUserGoal());
        total += estimateListTokens(summary.getPersistentPreferences());
        total += estimateListTokens(summary.getHardConstraints());
        total += estimateListTokens(summary.getNegativePreferences());
        total += estimateListTokens(summary.getResolvedFacts());
        total += estimateListTokens(summary.getOpenThreads());
        total += estimateListTokens(summary.getToolOutcomes());
        total += estimateListTokens(summary.getRagTakeaways());
        return total;
    }

    public int estimateMemoryTokens(List<LongTermMemoryFact> facts) {
        if (facts == null || facts.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (LongTermMemoryFact fact : facts) {
            total += 5;
            total += estimateTextTokens(fact == null ? null : fact.toRetrievableText());
        }
        return total;
    }

    private int estimateListTokens(List<String> values) {
        if (values == null || values.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (String value : values) {
            total += 3;
            total += estimateTextTokens(value);
        }
        return total;
    }
}
