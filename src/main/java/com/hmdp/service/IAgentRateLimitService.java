package com.hmdp.service;

public interface IAgentRateLimitService {
    boolean tryAcquireChat(String principalKey);

    boolean tryAcquireStream(String principalKey);
}
