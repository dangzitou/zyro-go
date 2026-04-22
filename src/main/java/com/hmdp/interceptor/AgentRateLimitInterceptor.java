package com.hmdp.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.config.AiProperties;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.service.IAgentRateLimitService;
import com.hmdp.utils.UserHolder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AgentRateLimitInterceptor implements HandlerInterceptor {

    private final IAgentRateLimitService agentRateLimitService;
    private final ObjectMapper objectMapper;
    private final AiProperties aiProperties;

    public AgentRateLimitInterceptor(IAgentRateLimitService agentRateLimitService,
                                     ObjectMapper objectMapper,
                                     AiProperties aiProperties) {
        this.agentRateLimitService = agentRateLimitService;
        this.objectMapper = objectMapper;
        this.aiProperties = aiProperties;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!aiProperties.getRateLimit().isEnabled()) {
            return true;
        }
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String uri = request.getRequestURI();
        if (!uri.startsWith("/ai/agent/chat")) {
            return true;
        }

        boolean allowed = uri.endsWith("/stream")
                ? agentRateLimitService.tryAcquireStream(resolvePrincipalKey(request))
                : agentRateLimitService.tryAcquireChat(resolvePrincipalKey(request));
        if (allowed) {
            return true;
        }

        response.setStatus(429);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(Result.fail("请求过于频繁，请稍后再试")));
        return false;
    }

    private String resolvePrincipalKey(HttpServletRequest request) {
        UserDTO user = UserHolder.getUser();
        if (user != null && user.getId() != null) {
            return "user:" + user.getId();
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return "ip:" + forwarded.split(",")[0].trim();
        }
        return "ip:" + request.getRemoteAddr();
    }
}
