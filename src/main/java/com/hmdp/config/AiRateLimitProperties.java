package com.hmdp.config;

import lombok.Data;

@Data
public class AiRateLimitProperties {
    private boolean enabled = true;
    private int windowSeconds = 60;
    private int chatMaxRequests = 30;
    private int streamMaxRequests = 10;
}
