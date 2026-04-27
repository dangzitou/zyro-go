package com.hmdp.ai.rag;

import cn.hutool.core.util.StrUtil;
import com.hmdp.config.AiProperties;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Shop;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IShopService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 博客知识源只承载“体验和场景感”。
 * 它不会替代实时店铺事实查询，而是为约会、环境、聚餐、安静等语义提供补充线索。
 */
@Slf4j
@Component
public class BlogRagKnowledgeSource implements RagKnowledgeSource {

    private static final String SOURCE_TYPE = "blog";

    private final AiProperties aiProperties;
    private final IBlogService blogService;
    private final IShopService shopService;

    public BlogRagKnowledgeSource(AiProperties aiProperties,
                                  IBlogService blogService,
                                  IShopService shopService) {
        this.aiProperties = aiProperties;
        this.blogService = blogService;
        this.shopService = shopService;
    }

    @Override
    public List<RagKnowledgeDocument> loadDocuments() {
        if (!aiProperties.getBlogRag().isEnabled()) {
            return List.of();
        }
        LocalDateTime cutoff = LocalDateTime.now().minusDays(aiProperties.getBlogRag().getRecentDays());
        List<Blog> blogs = blogService.query()
                .ge("create_time", cutoff)
                .ge("liked", aiProperties.getBlogRag().getMinLiked())
                .isNotNull("shop_id")
                .isNotNull("content")
                .orderByDesc("liked")
                .orderByDesc("create_time")
                .last("LIMIT " + aiProperties.getBlogRag().getMaxDocuments())
                .list();
        if (blogs == null || blogs.isEmpty()) {
            return List.of();
        }

        Set<Long> shopIds = blogs.stream()
                .map(Blog::getShopId)
                .filter(id -> id != null && id > 0)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, Shop> shopById = shopIds.isEmpty() ? Map.of() : shopService.listByIds(shopIds).stream()
                .collect(Collectors.toMap(Shop::getId, Function.identity(), (left, right) -> left));

        List<RagKnowledgeDocument> documents = new ArrayList<>();
        for (Blog blog : blogs) {
            Shop shop = shopById.get(blog.getShopId());
            if (shop == null) {
                continue;
            }
            String text = buildExperienceText(blog, shop);
            if (StrUtil.isBlank(text)) {
                continue;
            }
            documents.add(new RagKnowledgeDocument(
                    blog.getId(),
                    SOURCE_TYPE,
                    buildTitle(blog, shop),
                    text,
                    buildTags(blog, shop),
                    Map.of(
                            "shopId", shop.getId(),
                            "shopName", shop.getName(),
                            "liked", blog.getLiked() == null ? 0 : blog.getLiked(),
                            "createTime", blog.getCreateTime() == null ? "" : String.valueOf(blog.getCreateTime()),
                            "audience", "experience"
                    )
            ));
        }
        log.info("Loaded {} blog RAG documents for experience-oriented retrieval.", documents.size());
        return documents;
    }

    private String buildTitle(Blog blog, Shop shop) {
        String blogTitle = StrUtil.blankToDefault(blog.getTitle(), "探店体验");
        return shop.getName() + "｜" + blogTitle;
    }

    /**
     * 把原始博客转成更适合语义检索的摘要文档，显式补上门店和价格等上下文。
     */
    private String buildExperienceText(Blog blog, Shop shop) {
        String content = normalizeBlogContent(blog.getContent());
        if (StrUtil.isBlank(content)) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("这是一篇本地生活探店体验摘要。");
        builder.append("门店名称：").append(shop.getName()).append("。");
        if (StrUtil.isNotBlank(shop.getArea())) {
            builder.append("商圈：").append(shop.getArea()).append("。");
        }
        if (shop.getAvgPrice() != null) {
            builder.append("人均：").append(shop.getAvgPrice()).append("元。");
        }
        if (shop.getScore() != null) {
            builder.append("店铺评分：").append(String.format(Locale.US, "%.1f", shop.getScore() / 10.0D)).append("。");
        }
        builder.append("博客标题：").append(StrUtil.blankToDefault(blog.getTitle(), "探店体验")).append("。");
        builder.append("体验内容：").append(content).append("。");
        if (blog.getLiked() != null) {
            builder.append("热度：点赞").append(blog.getLiked()).append("。");
        }
        if (blog.getCreateTime() != null) {
            builder.append("发布时间：").append(blog.getCreateTime()).append("。");
        }
        return builder.toString();
    }

    private List<String> buildTags(Blog blog, Shop shop) {
        List<String> tags = new ArrayList<>();
        tags.add("experience");
        if (shop.getAvgPrice() != null && shop.getAvgPrice() <= 80) {
            tags.add("good_value");
        }
        String text = (StrUtil.blankToDefault(blog.getTitle(), "") + " " + StrUtil.blankToDefault(blog.getContent(), "")).toLowerCase(Locale.ROOT);
        if (containsAny(text, "约会", "氛围", "浪漫")) {
            tags.add("date");
        }
        if (containsAny(text, "安静", "聊天", "舒服")) {
            tags.add("quiet");
        }
        if (containsAny(text, "聚餐", "朋友", "多人")) {
            tags.add("group");
        }
        if (containsAny(text, "家庭", "长辈", "家人")) {
            tags.add("family");
        }
        if (containsAny(text, "简餐", "工作日", "顺路")) {
            tags.add("casual");
        }
        return tags;
    }

    private String normalizeBlogContent(String content) {
        String normalized = StrUtil.blankToDefault(content, "")
                .replace("<br/>", " ")
                .replace("<br>", " ")
                .replace("\\n", " ")
                .replace("\n", " ")
                .replace("\r", " ")
                .replace("｜", " ")
                .replace("|", " ");
        normalized = normalized.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 320) {
            return normalized;
        }
        int end = Math.min(normalized.length(), 320);
        return normalized.substring(0, end);
    }

    private boolean containsAny(String text, String... values) {
        for (String value : values) {
            if (text.contains(value)) {
                return true;
            }
        }
        return false;
    }
}
