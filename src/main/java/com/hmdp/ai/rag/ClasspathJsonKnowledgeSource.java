package com.hmdp.ai.rag;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 从 classpath 下的 JSON 知识包加载 RAG 内容。
 * 这里不用大量 markdown，而是使用结构化条目，方便后续从运营后台或数据库迁移。
 */
@Slf4j
@Component
public class ClasspathJsonKnowledgeSource implements RagKnowledgeSource {

    private static final String KNOWLEDGE_PATTERN = "classpath*:knowledge/*.json";

    private final ObjectMapper objectMapper;

    public ClasspathJsonKnowledgeSource(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public List<RagKnowledgeDocument> loadDocuments() {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        try {
            Resource[] resources = resolver.getResources(KNOWLEDGE_PATTERN);
            if (resources == null || resources.length == 0) {
                return Collections.emptyList();
            }
            List<RagKnowledgeDocument> documents = new ArrayList<>();
            for (Resource resource : resources) {
                documents.addAll(readOneResource(resource));
            }
            return documents;
        } catch (Exception e) {
            log.warn("Failed to load JSON knowledge resources from {}", KNOWLEDGE_PATTERN, e);
            return Collections.emptyList();
        }
    }

    private List<RagKnowledgeDocument> readOneResource(Resource resource) {
        try (InputStream inputStream = resource.getInputStream()) {
            List<KnowledgeEntry> entries = objectMapper.readValue(inputStream, new TypeReference<List<KnowledgeEntry>>() {
            });
            if (entries == null || entries.isEmpty()) {
                return Collections.emptyList();
            }
            List<RagKnowledgeDocument> documents = new ArrayList<>(entries.size());
            for (KnowledgeEntry entry : entries) {
                if (entry == null || StrUtil.isBlank(entry.text()) || StrUtil.isBlank(entry.title())) {
                    continue;
                }
                documents.add(new RagKnowledgeDocument(
                        entry.sourceId(),
                        StrUtil.blankToDefault(entry.sourceType(), "guide"),
                        entry.title(),
                        entry.text(),
                        entry.tags() == null ? List.of() : entry.tags(),
                        Map.of("audience", "general")
                ));
            }
            return documents;
        } catch (Exception e) {
            log.warn("Failed to read knowledge resource {}", resource.getDescription(), e);
            return Collections.emptyList();
        }
    }

    private record KnowledgeEntry(Long sourceId, String sourceType, String title, String text, List<String> tags) {
    }
}
