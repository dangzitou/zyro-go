package com.hmdp.ai.rag;

import com.hmdp.ai.embedding.LocalHashEmbeddingModel;
import com.hmdp.config.AiProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalLifeRagServiceTest {

    @Test
    void shouldIndexSupportAndGuideKnowledge() throws Exception {
        AiProperties properties = new AiProperties();
        properties.setEnabled(true);
        properties.setRagEnabled(true);
        properties.setRagSimilarityThreshold(0.10D);

        Path tempFile = Files.createTempFile("zyro-rag-test", ".json");
        properties.setRagStoreFile(tempFile.toString());

        RagKnowledgeSource knowledgeSource = () -> List.of(
                new RagKnowledgeDocument(
                        2001L,
                        "support",
                        "整个软件怎么用",
                        "用户先登录，再浏览首页、分类、优惠券和探店内容。如果不知道去哪吃，可以进入 AI 助手页面，输入位置、预算和偏好获取推荐。",
                        List.of("产品使用", "客服"),
                        Map.of("audience", "general")
                ),
                new RagKnowledgeDocument(
                        2002L,
                        "support",
                        "收不到验证码怎么办",
                        "先检查手机号格式，再确认 Redis、发送频率限制和短信通道。开发环境下验证码可能写入日志而不是实际发送短信。",
                        List.of("验证码", "客服", "排障"),
                        Map.of("audience", "general")
                )
        );

        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("embeddingModel", new LocalHashEmbeddingModel(256));
        ObjectProvider<EmbeddingModel> embeddingProvider = beanFactory.getBeanProvider(EmbeddingModel.class);

        LocalLifeRagService ragService = new LocalLifeRagService(
                properties,
                embeddingProvider,
                List.of(knowledgeSource)
        );

        ragService.rebuildIndex();
        List<Document> usageHits = ragService.search("这个软件怎么用", 3);
        List<Document> verificationCodeHits = ragService.search("为什么收不到验证码", 3);

        assertFalse(usageHits.isEmpty());
        assertTrue(usageHits.stream().anyMatch(document -> document.getText().contains("AI 助手页面")));
        assertFalse(verificationCodeHits.isEmpty());
        assertTrue(verificationCodeHits.stream().anyMatch(document -> document.getText().contains("Redis")));
    }
}
