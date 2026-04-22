package com.hmdp.controller;

import com.hmdp.dto.AiChatRequest;
import com.hmdp.dto.AiChatResponse;
import com.hmdp.dto.Result;
import com.hmdp.service.IAiAgentService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.annotation.Resource;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/ai/agent")
public class AiAgentController {

    @Resource
    private IAiAgentService aiAgentService;

    @PostMapping("/chat")
    public Result chat(@Valid @RequestBody AiChatRequest request) {
        request.setMessage(request.getMessage().trim());
        AiChatResponse response = aiAgentService.chat(request);
        return Result.ok(response);
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@Valid @RequestBody AiChatRequest request) {
        request.setMessage(request.getMessage().trim());
        return aiAgentService.chatStream(request);
    }

    @DeleteMapping("/session")
    public Result clearSession(@RequestParam(value = "conversationId", required = false) String conversationId) {
        aiAgentService.clearSession(conversationId);
        return Result.ok();
    }
}

