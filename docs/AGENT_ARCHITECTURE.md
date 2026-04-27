# Agent Architecture

## 1. 项目定位

`zyro-local` 是一个面向本地生活业务的单 Agent 后端。

这版的核心目标不是“把大模型接进来”，而是把原有高并发业务后端升级成一个：

- 以工具调用为主
- 以静态 RAG 为辅
- 以工程可控为前提
- 以企业可落地为目标

## 2. 核心边界

当前系统明确区分三类职责：

- `Tools / DB`：负责动态业务真相
- `RAG`：负责静态规则和解释性背景
- `LLM`：负责理解问题、编排工具、组织回答

这里最重要的是：

- 店铺、价格、评分、营业时间、优惠券、库存等动态事实不进 RAG
- 探店博客也不再进 RAG
- `retrievalHits` 只保留静态 `guide`

这样做的原因很简单：

- 动态数据容易过期
- 博客属于半动态内容，带有主观体验和时效性
- 如果把它们混进检索层，会让 `retrievalHits` 看起来像实时事实来源

## 3. 总体分层

### 3.1 接口层

核心入口：

- `AiAgentController`

负责：

- 同步对话 `POST /ai/agent/chat`
- 流式对话 `POST /ai/agent/chat/stream`
- 清理会话 `DELETE /ai/agent/session`
- 手动重建索引 `POST /ai/agent/knowledge/rebuild`

### 3.2 编排层

核心服务：

- `AiAgentServiceImpl`

负责：

- 登录态与会话处理
- 规划结果生成
- 是否启用知识检索
- 是否启用 RAG Advisor
- 工具调用和最终回答汇总

### 3.3 规划层

核心服务：

- `AgentPlanningServiceImpl`

负责先生成 `AgentExecutionPlan`，再驱动工具与回答执行。

### 3.4 工具层

核心工具类：

- `LocalLifeAgentTools`

当前对模型暴露的主要能力：

- `search_shops`
- `get_shop_detail`
- `get_shop_coupons`
- `get_hot_blogs`
- `recommend_shops`

### 3.5 会话记忆层

核心组件：

- `RedisChatMemoryRepository`
- `MessageWindowChatMemory`

这是短期会话记忆，不是长期用户画像。

### 3.6 知识检索层

核心组件：

- `AiKnowledgeServiceImpl`
- `LocalLifeRagService`

当前设计重点：

- 向量索引只收录静态 `guide`
- `retrievalHits` 只用于补充规则、约束、解释性上下文
- 博客完全移出 RAG 和检索层
- 店铺和优惠券等动态事实统一走工具或数据库

## 4. 请求链路

一轮 `POST /ai/agent/chat` 的典型链路如下：

1. 前端携带登录 token 请求 Agent 接口
2. `RefreshTokenInterceptor` 从 Redis 恢复登录态
3. `LoginInterceptor` 校验受保护接口
4. `AgentRateLimitInterceptor` 进行限流
5. `AiAgentController` 接收请求
6. `AiAgentServiceImpl` 生成内部会话 ID
7. `AgentPlanningServiceImpl` 生成结构化计划
8. `AiKnowledgeServiceImpl` 按需返回 `guide` 命中
9. 若本地向量索引可用，则挂上 `QuestionAnswerAdvisor`
10. 模型按计划决定是否调用工具
11. 工具层查询真实业务服务
12. 汇总回答、工具轨迹、知识命中、计划结果

## 5. RAG 当前收录什么

当前只收录：

- 静态规则文档
- 解释性提示文档

当前不收录：

- 探店博客
- 店铺详情
- 优惠券
- 价格
- 评分
- 营业时间
- 库存

## 6. 为什么连博客也移出 RAG

因为博客虽然不像店铺价格那样强动态，但它依然不是稳定知识真相：

- 会持续更新
- 带有主观体验
- 容易和当前真实营业信息不一致
- 更适合走内容查询或业务工具

所以这版最终边界是：

- `RAG` 只补静态规则背景
- `Blogs` 走工具
- `Dynamic facts` 走工具/数据库

## 7. Embedding 与 RAG 兜底策略

当前项目支持将聊天模型和 embedding 模型解耦配置。

新增的 embedding 相关配置：

- `AI_EMBEDDING_PROVIDER`
- `AI_EMBEDDING_BASE_URL`
- `AI_EMBEDDING_API_KEY`
- `AI_EMBEDDING_MODEL`
- `AI_LOCAL_EMBEDDING_DIMENSIONS`

支持三种模式：

1. `auto`
2. `openai`
3. `local-hash`

其中：

- `auto`：优先探测远程 embedding，失败自动回退到本地
- `openai`：强制使用远程 OpenAI 兼容 embedding
- `local-hash`：完全使用本地 deterministic embedding

## 8. 当前已验证状态

当前已确认：

- `shop` / `voucher` 已移出检索层
- `blog` 已移出 RAG 和 `retrievalHits`
- RAG 现在是 `guide-only`
- embedding 网关不可用时，会自动回退到 `local-hash`
- 在 fallback 模式下，guide-only 向量索引依然可以成功构建

## 9. 结论

这版 `zyro-local` 现在的事实边界更清晰了：

- `RAG` 不再承载博客、店铺、优惠券等半动态或强动态内容
- `retrievalHits` 不再容易被误解为实时事实来源
- 整个 Agent 更适合企业真实落地和后续治理
