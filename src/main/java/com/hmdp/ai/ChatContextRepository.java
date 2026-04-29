package com.hmdp.ai;

import java.util.List;

public interface ChatContextRepository {

    ConversationSummary loadConversationSummary(String conversationId);

    void saveConversationSummary(String conversationId, ConversationSummary summary);

    void clearConversationSummary(String conversationId);

    List<LongTermMemoryFact> loadLongTermMemories(Long userId);

    void saveLongTermMemories(Long userId, List<LongTermMemoryFact> facts);
}
