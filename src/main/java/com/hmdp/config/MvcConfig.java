package com.hmdp.config;

import com.hmdp.interceptor.AgentRateLimitInterceptor;
import com.hmdp.interceptor.LoginInterceptor;
import com.hmdp.interceptor.RefreshTokenInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class MvcConfig implements WebMvcConfigurer {

    private final StringRedisTemplate stringRedisTemplate;
    private final AgentRateLimitInterceptor agentRateLimitInterceptor;

    public MvcConfig(StringRedisTemplate stringRedisTemplate, AgentRateLimitInterceptor agentRateLimitInterceptor) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.agentRateLimitInterceptor = agentRateLimitInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate)).addPathPatterns("/**").order(0);
        registry.addInterceptor(new LoginInterceptor())
                 .excludePathPatterns(
                         "/user/code",
                         "/user/login",
                         "/shop/**",
                         "/shop-type/**",
                         "/voucher/**",
                         "/upload/**",
                         "/blog/hot"
                 ).order(1);
        registry.addInterceptor(agentRateLimitInterceptor)
                .addPathPatterns("/ai/agent/chat", "/ai/agent/chat/stream")
                .order(2);
    }
}

