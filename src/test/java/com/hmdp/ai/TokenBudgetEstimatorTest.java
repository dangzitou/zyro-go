package com.hmdp.ai;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenBudgetEstimatorTest {

    private final TokenBudgetEstimator estimator = new TokenBudgetEstimator();

    @Test
    void shouldEstimateMoreTokensForLongerText() {
        int shortTokens = estimator.estimateTextTokens("广州体育西");
        int longTokens = estimator.estimateTextTokens("广州体育西附近好吃不贵适合两个人约会的安静餐厅");
        assertTrue(longTokens > shortTokens);
    }

    @Test
    void shouldCountMessageOverhead() {
        List<Message> messages = List.of(
                new UserMessage("我在广州"),
                new AssistantMessage("收到，你在广州。")
        );
        assertTrue(estimator.estimateMessagesTokens(messages) > estimator.estimateTextTokens("我在广州收到，你在广州。"));
    }
}
