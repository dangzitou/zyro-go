package com.hmdp.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.UserHolder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class LoginInterceptor implements HandlerInterceptor {

    private final ObjectMapper objectMapper;
    private final String debugUserId;

    public LoginInterceptor(ObjectMapper objectMapper,
                            @Value("${hmdp.ai.debug-user-id:}") String debugUserId) {
        this.objectMapper = objectMapper;
        this.debugUserId = debugUserId;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (UserHolder.getUser() != null) {
            return true;
        }
        if (request.getRequestURI().startsWith("/ai/agent") && debugUserEnabled()) {
            UserDTO debugUser = new UserDTO();
            debugUser.setId(Long.parseLong(debugUserId));
            debugUser.setNickName("debug-user");
            UserHolder.saveUser(debugUser);
            return true;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(Result.fail("请先登录")));
        return false;
    }

    private boolean debugUserEnabled() {
        return debugUserId != null && !debugUserId.isBlank();
    }
}
