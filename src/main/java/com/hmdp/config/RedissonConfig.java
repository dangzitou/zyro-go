package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {

    @Bean
    @ConditionalOnProperty(prefix = "hmdp.redisson", name = "enabled", havingValue = "true", matchIfMissing = true)
    public RedissonClient redissonClient(RedisProperties redisProperties) {
        Config config = new Config();
        String address = "redis://" + redisProperties.getHost() + ":" + redisProperties.getPort();
        config.useSingleServer().setAddress(address);
        if (redisProperties.getPassword() != null && !redisProperties.getPassword().isBlank()) {
            config.useSingleServer().setPassword(redisProperties.getPassword());
        }
        return Redisson.create(config);
    }
}
