package com.hmdp.service;

import com.hmdp.dto.AiChatRequest;
import com.hmdp.dto.AiChatResponse;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface IAiAgentService {
    AiChatResponse chat(AiChatRequest request);

    SseEmitter chatStream(AiChatRequest request);

    void clearSession(String conversationId);
}
