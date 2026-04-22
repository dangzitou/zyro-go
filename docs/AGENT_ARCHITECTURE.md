# Agent Architecture

## 1. 为什么这版是“实用型 Agent”

这次升级不是把黑马点评简单接个大模型聊天接口，而是把它改造成了一个可落到业务事实上的工具调用 Agent。

边界很明确：

- 它是单 Agent，不是多智能体协同系统
- 它是规划 + RAG + 工具增强问答与推荐，不是多智能体自治平台
- 它用了本地向量 RAG，但没有引入重型外部向量数据库

这样设计的原因是：

- 店铺、券、评分、营业时间、热门内容这些信息都是真实业务数据，最应该由后端工具层给出
- 大模型最擅长的是理解意图、做结构化规划、组织语言和解释，不是替代数据库
- 所以我把这版能力加强成“规划 + RAG + 工具调用”，而不是盲目铺多 Agent

## 2. 总体分层

### 2.1 交互层

入口控制器：

- `com.hmdp.controller.AiAgentController`

职责：

- 接收 `/ai/agent/chat`
- 接收 `/ai/agent/session`
- 规范化请求参数
- 返回统一 `Result`

### 2.2 Agent 编排层

核心服务：

- `com.hmdp.service.impl.AiAgentServiceImpl`

职责：

- 校验 Agent 是否启用
- 解析登录用户和 `conversationId`
- 调用规划服务输出结构化执行计划
- 触发知识补充
- 决定是否启用 RAG advisor
- 构造系统提示词
- 调用 Spring AI `ChatClient`
- 绑定 Redis 会话记忆
- 回传规划 / 工具 / 检索 trace 与知识命中

### 2.3 工具层

核心工具类：

- `com.hmdp.ai.LocalLifeAgentTools`

当前暴露给模型的能力：

- `search_shops`
- `get_shop_detail`
- `get_shop_coupons`
- `get_hot_blogs`
- `recommend_shops`

这些方法都通过 `@Tool` 暴露给 Spring AI，模型只能调用这组受控工具，不能直接访问 controller、mapper 或任意 URL。

### 2.4 会话记忆层

核心组件：

- `com.hmdp.ai.RedisChatMemoryRepository`
- `com.hmdp.config.AiAgentConfig`

实现方式：

- Redis Value 存储会话消息列表
- `MessageWindowChatMemory` 控制窗口大小
- `conversationId` 以 `prefix:userId:conversationId` 方式隔离
- TTL 到期后会话自动过期

### 2.5 规划层

核心服务：

- `com.hmdp.service.impl.AgentPlanningServiceImpl`

当前实现：

- 用 Spring AI 先产出 `AgentExecutionPlan`
- 结合 `StructuredOutputValidationAdvisor` 校验结构
- 规划内容包括 `intent`、`preferredTools`、`retrievalQuery`、`responseStyle`

作用：

- 先规划，再让主 Agent 按规划调用工具和 RAG
- 这比“所有请求都直接扔给模型自行发挥”更可控

### 2.6 知识与 RAG 层

核心服务：

- `com.hmdp.service.impl.AiKnowledgeServiceImpl`
- `com.hmdp.ai.rag.LocalLifeRagService`

当前实现：

- 用 `SimpleVectorStore` 构建本地向量索引
- 索引内容来自店铺、优惠券、博客和少量静态规则文档
- 检索阶段做“向量召回 + 关键词召回”融合
- 主对话通过 `QuestionAnswerAdvisor` 启用官方 RAG 增强

为什么先这样做：

- 当前场景里的高价值信息主要是结构化业务数据，所以向量 RAG 的角色是增强背景，而不是替代工具
- 用本地向量库先把 RAG 真正落地，比一开始上外部向量数据库更适合这个项目

### 2.7 推荐层

核心服务：

- `com.hmdp.service.impl.ShopRecommendationServiceImpl`

职责：

- 候选召回
- 价格、距离、券、博客热度等因素打分
- 生成结构化推荐结果

这里的原则是：

- 推荐排序放后端
- 模型负责解释推荐理由
- 不把全量店铺原始数据塞给模型

## 3. 请求处理链路

一次 `/ai/agent/chat` 的完整链路如下：

1. 用户带 token 请求 Agent 接口
2. `RefreshTokenInterceptor` 解析登录用户并刷新 Redis 登录态
3. `AiAgentController` 接收请求
4. `AiAgentServiceImpl` 生成内部会话 ID
5. `AgentPlanningServiceImpl` 先生成 `AgentExecutionPlan`
6. 如果开启知识增强，`AiKnowledgeServiceImpl` 做混合召回
7. 如果本地向量索引可用，挂上 `QuestionAnswerAdvisor`
8. 创建 `MessageChatMemoryAdvisor`
9. 用 Spring AI `ChatClient` 发起模型调用
10. `ToolCallAdvisor` 负责递归工具调用循环
11. 工具调用真实业务 service，返回裁剪后的结构化结果
12. 模型生成最终回答
13. `MessageWindowChatMemory` 将 user / assistant 消息写回 Redis
14. 返回 `answer + toolTrace + retrievalHits + plan`

## 4. 为什么不用更“重”的方案

### 4.1 为什么不用多 Agent

当前业务问题大多围绕：

- 查店
- 查券
- 查热门内容
- 推荐附近店铺

这些问题属于单轮或轻多轮工具调用，单 Agent 足够。上多 Agent 会引入额外的调度复杂度、测试成本和错误边界。

### 4.2 为什么没有外部向量数据库

当前最重要的动态事实都在 MySQL / Redis 里。

如果一上来就上向量数据库，会出现两个问题：

- 最关键的信息仍然要靠工具兜底
- 但系统复杂度、成本和运维面会明显上升

所以当前版本选择：

- 结构化事实走工具
- 语义背景走本地向量 RAG

### 4.3 为什么没有保留 MCP 外部 server

旧工作树里已经有一套本地 MCP wrapper 骨架，但它本质上还是本地工具转发，并没有带来真正独立的运行时收益。

这次升级把它收敛掉，改成更直接的 Spring AI 工具调用，原因是：

- 依赖更少
- 代码更短
- 调试更直接
- 面试时也更容易讲清楚

## 5. 安全与可维护性

### 5.1 能力边界

模型能做的事情只限于：

- 调用受控工具
- 根据工具结果和记忆组织语言

模型不能做的事情：

- 直接访问数据库
- 直接调内部 controller
- 自行拼接任意 URL
- 获得原始敏感配置

### 5.2 配置安全

这次升级把 `application.yaml` 里的明文模型密钥移除了，统一改成环境变量覆盖。

### 5.3 可回归测试

旧版测试依赖真实 MySQL / Redis / Redisson，不适合持续回归。

新版测试改成了：

- Agent 服务单测
- 知识召回单测
- 推荐链路单测

这样即使没有基础设施，也能验证关键逻辑。

## 6. 关键类索引

Agent 相关核心类：

- `com.hmdp.controller.AiAgentController`
- `com.hmdp.service.impl.AiAgentServiceImpl`
- `com.hmdp.service.impl.AgentPlanningServiceImpl`
- `com.hmdp.ai.LocalLifeAgentTools`
- `com.hmdp.ai.RedisChatMemoryRepository`
- `com.hmdp.ai.rag.LocalLifeRagService`
- `com.hmdp.ai.AgentTraceContext`
- `com.hmdp.config.AiAgentConfig`
- `com.hmdp.config.AiProperties`
- `com.hmdp.service.impl.AiKnowledgeServiceImpl`
- `com.hmdp.service.impl.ShopRecommendationServiceImpl`

## 7. 当前边界

当前版本仍然有意保留的边界：

- 会话记忆是短期窗口，不是长期用户画像
- RAG 当前是本地向量库，不是独立外部检索基础设施
- 工具 / 规划 trace 目前只回传给接口，没有持久化分析
- 推荐服务还是规则 + 业务特征打分，不是完整工业级推荐系统

## 8. 下一步迭代建议

- 增加流式输出
- 为工具调用增加观测埋点
- 为向量索引增加增量更新机制
- 增加用户画像和偏好因子
- 为业务链路补更多集成测试

## 9. 企业级运行时补充

这版后续已经补上了一层更贴近企业落地的运行时治理，重点不是换架构，而是在现有单 Agent 架构外面补齐生产能力。

### 9.1 流式响应层

新增：

- `POST /ai/agent/chat/stream`
- `AiAgentServiceImpl#chatStream`

作用：

- 降低长回答首字等待时间
- 让前端可以做流式渲染和中断
- 适合客服、导购、运营 Copilot 这类交互场景

### 9.2 限流层

新增：

- `com.hmdp.interceptor.AgentRateLimitInterceptor`
- `com.hmdp.service.IAgentRateLimitService`
- `com.hmdp.service.impl.AgentRateLimitServiceImpl`

策略：

- 普通对话和流式对话分开计数
- 优先按登录用户限流
- 无登录态时回退到 IP 限流
- 限流逻辑放在 MVC 拦截器层，避免请求真正进入模型编排链路后才拒绝

### 9.3 审计与可观测性

新增：

- `com.hmdp.service.IAgentAuditService`
- `com.hmdp.service.impl.AgentAuditServiceImpl`
- `com.hmdp.dto.AgentAuditRecord`
- `com.hmdp.config.RequestTraceFilter`

能力：

- 为每个请求生成 `traceId`
- 把 traceId 注入日志 MDC
- 把关键 Agent 请求写入 Redis Stream 审计流
- 记录计划、trace、耗时、状态，方便回放和问题定位

### 9.4 运维侧能力

新增：

- `spring-boot-starter-actuator`
- `micrometer-registry-prometheus`
- `application-prod.yaml`

落地点：

- 健康检查走 `/actuator/health`
- 指标走 `/actuator/metrics`
- Prometheus 抓取走 `/actuator/prometheus`
- 生产配置打开优雅停机、压缩和更高的 Tomcat 并发参数

### 9.5 现在这版怎么定义更准确

如果按企业交付口径来描述，现在这版更适合定义为：

- 一个保留原黑马点评高并发业务链路的本地生活 Agent 后端
- 一个单 Agent、强工具边界、带 RAG 和规划能力的业务智能服务
- 一个已经具备流式输出、限流、审计、观测、生产 profile 的可上线雏形
