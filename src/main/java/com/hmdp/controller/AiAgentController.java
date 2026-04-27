package com.hmdp.controller;

import com.hmdp.ai.rag.LocalLifeRagService;
import com.hmdp.dto.AiChatRequest;
import com.hmdp.dto.AiChatResponse;
import com.hmdp.dto.Result;
import com.hmdp.service.IAiAgentService;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/ai/agent")
public class AiAgentController {

    @Resource
    private IAiAgentService aiAgentService;

    @Resource
    private LocalLifeRagService localLifeRagService;

    /**
     * 同步 Agent 对话接口。
     * 适合普通问答、推荐查询和联调时直接拿完整回答。
     */
    @PostMapping("/chat")
    public Result chat(@Valid @RequestBody AiChatRequest request) {
        request.setMessage(request.getMessage().trim());
        AiChatResponse response = aiAgentService.chat(request);
        return Result.ok(response);
    }

    /**
     * 流式 Agent 对话接口。
     * 通过 SSE 分块输出，适合前端做实时打字机效果。
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@Valid @RequestBody AiChatRequest request) {
        request.setMessage(request.getMessage().trim());
        return aiAgentService.chatStream(request);
    }

    /**
     * 手动重建知识库索引。
     * 一般用于知识文档更新后，立即刷新 RAG 检索底座。
     */
    @PostMapping("/knowledge/rebuild")
    public Result rebuildKnowledgeIndex() {
        localLifeRagService.rebuildIndex();
        return Result.ok(localLifeRagService.isReady());
    }

    /**
     * 清理会话记忆。
     * 不传 conversationId 时，会清理默认会话。
     */
    @DeleteMapping("/session")
    public Result clearSession(@RequestParam(value = "conversationId", required = false) String conversationId) {
        aiAgentService.clearSession(conversationId);
        return Result.ok();
    }
}
