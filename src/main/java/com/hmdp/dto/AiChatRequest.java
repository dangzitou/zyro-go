package com.hmdp.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AiChatRequest {
    @NotBlank(message = "message cannot be blank")
    private String message;
    private String conversationId;
    private Integer knowledgeTopK;
    private Boolean useKnowledge;
}
