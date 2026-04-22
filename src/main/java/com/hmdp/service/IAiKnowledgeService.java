package com.hmdp.service;

import com.hmdp.dto.AiRetrievalHit;

import java.util.List;

public interface IAiKnowledgeService {
    List<AiRetrievalHit> retrieve(String query, Integer topK);
}
