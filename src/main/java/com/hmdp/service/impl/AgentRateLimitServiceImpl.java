package com.hmdp.service.impl;

import com.hmdp.config.AiProperties;
import com.hmdp.service.IAgentRateLimitService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

@Service
public class AgentRateLimitServiceImpl implements IAgentRateLimitService {

    private final StringRedisTemplate stringRedisTemplate;
    private final AiProperties aiProperties;

    public AgentRateLimitServiceImpl(StringRedisTemplate stringRedisTemplate, AiProperties aiProperties) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.aiProperties = aiProperties;
    }

    /**
     * 针对普通对话接口尝试获取限流配额。
     */
    @Override
    public boolean tryAcquireChat(String principalKey) {
        return tryAcquire("chat", principalKey, aiProperties.getRateLimit().getChatMaxRequests());
    }

    /**
     * 针对流式对话接口尝试获取限流配额。
     */
    @Override
    public boolean tryAcquireStream(String principalKey) {
        return tryAcquire("stream", principalKey, aiProperties.getRateLimit().getStreamMaxRequests());
    }

    /**
     * 基于固定时间窗口做计数限流。
     */
    private boolean tryAcquire(String scene, String principalKey, int maxRequests) {
        if (!aiProperties.getRateLimit().isEnabled()) {
            return true;
        }
        int windowSeconds = Math.max(1, aiProperties.getRateLimit().getWindowSeconds());
        long bucket = Instant.now().getEpochSecond() / windowSeconds;
        String key = "ai:rate-limit:" + scene + ":" + principalKey + ":" + bucket;
        Long count = stringRedisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            stringRedisTemplate.expire(key, windowSeconds + 5L, TimeUnit.SECONDS);
        }
        return count != null && count <= maxRequests;
    }
}
