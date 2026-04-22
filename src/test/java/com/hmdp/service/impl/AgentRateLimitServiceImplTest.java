package com.hmdp.service.impl;

import com.hmdp.config.AiProperties;
import com.hmdp.config.AiRateLimitProperties;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentRateLimitServiceImplTest {

    @Test
    void shouldRejectWhenChatRequestsExceedThreshold() {
        StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(anyString())).thenReturn(1L, 2L, 3L);
        when(stringRedisTemplate.expire(anyString(), anyLong(), eq(TimeUnit.SECONDS))).thenReturn(true);

        AiProperties aiProperties = new AiProperties();
        AiRateLimitProperties rateLimitProperties = new AiRateLimitProperties();
        rateLimitProperties.setEnabled(true);
        rateLimitProperties.setWindowSeconds(60);
        rateLimitProperties.setChatMaxRequests(2);
        aiProperties.setRateLimit(rateLimitProperties);

        AgentRateLimitServiceImpl service = new AgentRateLimitServiceImpl(stringRedisTemplate, aiProperties);

        assertTrue(service.tryAcquireChat("user:1"));
        assertTrue(service.tryAcquireChat("user:1"));
        assertFalse(service.tryAcquireChat("user:1"));
    }
}
