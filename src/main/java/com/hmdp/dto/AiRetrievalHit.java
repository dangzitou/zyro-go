package com.hmdp.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AiRetrievalHit {
    private String sourceType;
    private Long sourceId;
    private String title;
    private String snippet;
    private Double score;
}
