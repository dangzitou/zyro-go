package com.hmdp.config;

import com.hmdp.interceptor.AgentRateLimitInterceptor;
import com.hmdp.interceptor.LoginInterceptor;
import com.hmdp.interceptor.RefreshTokenInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class MvcConfig implements WebMvcConfigurer {

    private final RefreshTokenInterceptor refreshTokenInterceptor;
    private final LoginInterceptor loginInterceptor;
    private final AgentRateLimitInterceptor agentRateLimitInterceptor;

    public MvcConfig(RefreshTokenInterceptor refreshTokenInterceptor,
                     LoginInterceptor loginInterceptor,
                     AgentRateLimitInterceptor agentRateLimitInterceptor) {
        this.refreshTokenInterceptor = refreshTokenInterceptor;
        this.loginInterceptor = loginInterceptor;
        this.agentRateLimitInterceptor = agentRateLimitInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(refreshTokenInterceptor)
                .addPathPatterns("/**")
                .order(0);
        registry.addInterceptor(loginInterceptor)
                .addPathPatterns(
                        "/user/logout",
                        "/user/me",
                        "/user/sign",
                        "/user/sign/count",
                        "/blog",
                        "/blog/of/me",
                        "/blog/of/follow",
                        "/blog/like/**",
                        "/follow/**",
                        "/upload/**",
                        "/voucher",
                        "/voucher/seckill",
                        "/voucher-order/**",
                        "/shop",
                        "/ai/agent/**"
                )
                .order(1);
        registry.addInterceptor(agentRateLimitInterceptor)
                .addPathPatterns("/ai/agent/chat", "/ai/agent/chat/stream")
                .order(2);
    }
}

