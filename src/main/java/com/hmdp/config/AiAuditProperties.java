package com.hmdp.config;

import lombok.Data;

@Data
public class AiAuditProperties {
    private boolean enabled = true;
    private String streamKey = "hmdp:agent:audit";
    private long maxLen = 5000;
    private boolean includeAnswer = false;
}
