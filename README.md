# Zyro Go Agent

`zyro-go` 现在不是“经典黑马点评练手后端”的原样拷贝了，而是一个面向本地生活场景的实用型 Agent 后端。

这次升级遵循两个原则：

1. 技术栈要新，但必须真有用。
2. Agent 要落在业务上，而不是为了“像 Agent”而堆框架。

所以这版项目保留了黑马点评原有的 Redis 高并发业务链路，同时把 AI 能力升级为基于 Spring AI 的规划式 Agent：先做结构化执行规划，再按需启用向量 RAG、递归工具调用和会话记忆。模型负责理解意图和组织回答，业务事实一律由后端工具层和检索层给出，避免店铺、优惠券、评分、营业时间这类动态信息被模型瞎编。

## 1. 项目定位

一句话概括：

`Spring Boot 3.5 + Spring AI + Redis + MyBatis-Plus` 的本地生活 Agent 后端，既能跑原有点评业务，也能提供带结构化规划、向量 RAG、递归工具调用和会话记忆的智能导购/问答能力。

适合拿来体现的能力：

- Java 老项目升级到 Java 21 / Spring Boot 3 的迁移能力
- Redis 在登录态、缓存、GEO、Stream、分布式锁、签到位图中的典型用法
- Agent 工程化落地：结构化规划、向量 RAG、递归工具调用、会话记忆、推荐链路、配置安全、单测可回归

## 2. 升级后的核心能力

原有业务能力保留：

- 手机验证码登录与用户态维护
- 商铺缓存查询与缓存更新
- 基于 Redis GEO 的附近商铺查询
- 探店博客发布、点赞、关注
- 优惠券秒杀
- Lua + Redis Stream 异步下单
- Redisson 分布式锁控制一人一单
- Redis 全局唯一 ID 生成

新增 Agent 能力：

- `POST /ai/agent/chat`：智能对话入口
- `DELETE /ai/agent/session`：按会话清空短期记忆
- Spring AI `ChatClient` + `ToolCallAdvisor` 递归工具调用
- 结构化规划：先输出执行计划，再决定优先工具和检索策略
- 基于 Redis 的多轮会话记忆
- 基于 `SimpleVectorStore` 的本地向量 RAG
- 混合检索：向量召回 + 关键词召回融合
- 推荐工具：把候选召回、过滤、打分放在后端，模型只做解释和交互
- 工具 / 规划 / 检索 trace 回传：方便联调和面试时展示 Agent 实际调用了哪些能力

## 3. 技术栈

升级后的主栈：

- Java 21
- Spring Boot 3.5.13
- Spring AI 1.1.4
- Spring AI Vector Store / RAG Advisors
- MyBatis-Plus 3.5.15
- MySQL 8
- Redis 6+
- Redisson 3.52.0
- Hutool 5.8.38
- JUnit 5 + Mockito

为什么这样选：

- 选择 `Spring AI`，是因为它和 Spring Boot 生态贴合，足够新，也足够实用，能直接落结构化输出、RAG advisor、工具调用和记忆，不需要为了“看起来高级”引入一整套复杂 Agent 框架。
- 继续保留 `Redis` 和 `Redisson`，因为黑马点评原有业务链路本来就是项目亮点，升级 AI 不应该把已有高并发能力删掉。
- RAG 选用了 `SimpleVectorStore` 做本地向量检索，是为了在不引入额外数据库的情况下，把语义检索真正落地，并保证项目仍然可以轻量运行和测试。
- 没有硬塞多 Agent 编排或外部 MCP server，是刻意取舍。当前场景里最重要的动态事实都来自结构化业务数据，所以我把重心放在“规划 + RAG + 工具调用”的闭环上。

## 4. Agent 架构概览

当前 Agent 是“单 Agent + 规划 + RAG + 多工具”的实用架构，不是多智能体编排系统。

主流程：

1. 用户登录后调用 `/ai/agent/chat`
2. 服务端根据 `conversationId` 取出 Redis 短期记忆
3. 规划器先输出结构化执行计划
4. 根据计划决定优先工具、检索 query 和回答风格
5. 如果启用 RAG，则从本地向量库做语义召回
6. Spring AI 把问题、上下文、RAG advisor、工具能力一起发给模型
7. 模型通过递归工具调用拿到业务事实
8. 工具层走真实业务 service，返回裁剪后的结构化结果
9. 模型基于 RAG + 工具结果生成最终回答
10. 本轮 user/assistant 消息写回 Redis 会话记忆

工具列表：

- `search_shops`
- `get_shop_detail`
- `get_shop_coupons`
- `get_hot_blogs`
- `recommend_shops`

更多设计细节见：

- `docs/AGENT_ARCHITECTURE.md`
- `docs/AGENT_API.md`

## 5. 目录结构

```text
.
├── src/main/java/com/hmdp
│   ├── ai                 # Agent trace、Redis memory、业务工具
│   │   └── rag            # 本地向量 RAG
│   ├── config             # Spring / MyBatis / Redisson / AI 配置
│   ├── controller         # 原业务接口 + Agent 接口
│   ├── dto                # DTO 与 Agent 响应对象
│   ├── entity             # 数据库实体
│   ├── interceptor        # 登录态与 Token 刷新
│   ├── mapper             # MyBatis-Plus Mapper
│   ├── service            # 业务接口
│   ├── service/impl       # 业务实现、推荐服务、知识服务、Agent 服务
│   └── utils              # Redis / Regex / ThreadLocal 等工具
├── src/main/resources
│   ├── application.yaml   # 默认配置（无明文密钥）
│   ├── db/hmdp.sql        # 初始化 SQL
│   ├── lua                # 秒杀脚本
│   └── mapper             # Mapper XML
├── src/test/java
│   └── com/hmdp/service   # Agent、知识召回、推荐链路单测
└── docs
    ├── AGENT_API.md
    └── AGENT_ARCHITECTURE.md
```

## 6. 环境要求

- JDK 21
- Maven 3.9+
- MySQL 8.x
- Redis 6.x 或更高

本机已验证：

- `mvn clean test` 可在无 MySQL / 无 Redis / 无 AI Key 的干净环境下通过

## 7. 配置说明

默认配置文件：

- `src/main/resources/application.yaml`

关键配置全部改成了环境变量覆盖，避免把真实密钥写进仓库。

常用环境变量：

| 变量名 | 说明 | 默认值 |
| --- | --- | --- |
| `MYSQL_URL` | MySQL JDBC 地址 | `jdbc:mysql://127.0.0.1:3306/hmdp...` |
| `MYSQL_USERNAME` | MySQL 用户名 | `root` |
| `MYSQL_PASSWORD` | MySQL 密码 | `1234` |
| `REDIS_HOST` | Redis Host | `127.0.0.1` |
| `REDIS_PORT` | Redis 端口 | `6379` |
| `REDIS_PASSWORD` | Redis 密码 | `123456` |
| `REDISSON_ENABLED` | 是否启用 Redisson | `true` |
| `AI_ENABLED` | 是否启用 Agent | `false` |
| `AI_BASE_URL` | OpenAI 兼容网关地址 | `https://api.openai.com` |
| `AI_API_KEY` | 模型 API Key | `demo-key` |
| `AI_MODEL` | 模型名 | `gpt-4.1-mini` |
| `AI_TEMPERATURE` | 温度参数 | `0.2` |
| `AI_MEMORY_TURNS` | Redis 会话窗口轮数 | `6` |
| `AI_MEMORY_TTL_HOURS` | 会话保留时长 | `12` |
| `AI_KNOWLEDGE_ENABLED` | 是否开启知识补充 | `true` |
| `AI_KNOWLEDGE_TOP_K` | 检索命中条数 | `4` |
| `AI_EMBEDDING_MODEL` | Embedding 模型 | `text-embedding-3-small` |
| `AI_RAG_ENABLED` | 是否启用向量 RAG | `true` |
| `AI_RAG_REBUILD_ON_STARTUP` | 启动时是否重建索引 | `false` |
| `AI_RAG_STORE_FILE` | 本地向量索引文件 | `data/ai/hmdp-vector-store.json` |
| `AI_RAG_SIMILARITY_THRESHOLD` | 向量检索阈值 | `0.62` |
| `AI_PLANNER_ENABLED` | 是否启用结构化规划 | `true` |
| `AI_PLANNER_VALIDATION_ENABLED` | 是否启用规划结果校验重试 | `true` |
| `AI_RECURSIVE_TOOL_LOOP_ENABLED` | 是否启用递归工具循环 | `true` |

推荐做法：

1. 本地先把 MySQL 和 Redis 跑起来
2. 导入 `src/main/resources/db/hmdp.sql`
3. 如果只想跑传统业务，不设置任何 AI 环境变量也可以
4. 如果要体验 Agent，再额外设置 `AI_ENABLED=true` 和你的模型网关参数
5. 如果要真正体验 RAG，把 `AI_RAG_REBUILD_ON_STARTUP=true` 开一次，让系统构建本地向量索引

## 8. 初始化数据

SQL 文件：

- `src/main/resources/db/hmdp.sql`

初始化步骤：

1. 在 MySQL 中创建数据库 `hmdp`
2. 执行 `hmdp.sql`

## 9. 启动方式

开发模式：

```bash
mvn spring-boot:run
```

打包启动：

```bash
mvn clean package -DskipTests
java -jar target/hm-dianping-0.0.1-SNAPSHOT.jar
```

启动入口：

- `com.hmdp.HmDianPingApplication`

## 10. Agent 接口快速示例

先正常登录，拿到 token。

请求：

```http
POST /ai/agent/chat
Authorization: <token>
Content-Type: application/json

{
  "message": "给我推荐附近 100 元以内、有券的火锅",
  "conversationId": "dinner-plan",
  "useKnowledge": true,
  "knowledgeTopK": 4
}
```

返回示例：

```json
{
  "success": true,
  "data": {
    "conversationId": "dinner-plan",
    "answer": "如果你想控制在 100 元以内，优先看辣府火锅，它离你更近、评分更高，而且当前有双人套餐券。",
    "toolTrace": [
      "plan(intent=recommendation, useKnowledge=true, preferredTools=[recommend_shops, get_shop_coupons])",
      "knowledge(query=火锅, hits=4, vectorRagReady=true)",
      "recommend_shops(keyword=火锅, typeId=-, budget=100, couponOnly=true, limit=-) -> 3 recommendation(s)",
      "get_shop_coupons(shopId=1) -> 2 coupon(s)"
    ],
    "retrievalHits": [
      {
        "sourceType": "shop",
        "sourceId": 1,
        "title": "辣府火锅",
        "snippet": "评分4.9，套餐力度大",
        "score": 1.8
      }
    ],
    "plan": {
      "intent": "recommendation",
      "useKnowledge": true,
      "useTools": true,
      "retrievalQuery": "火锅",
      "responseStyle": "concise",
      "reasoningFocus": "compare_candidates",
      "preferredTools": ["recommend_shops", "get_shop_coupons"]
    }
  }
}
```

清理会话：

```http
DELETE /ai/agent/session?conversationId=dinner-plan
Authorization: <token>
```

完整接口说明见：

- `docs/AGENT_API.md`

## 11. 测试

执行：

```bash
mvn clean test
```

当前自动化测试覆盖：

- Agent 服务在启用 / 禁用两种状态下的行为
- 结构化规划对主链路的驱动
- Spring AI 客户端接入后的会话记忆持久化
- 业务知识召回与 RAG fallback
- 推荐服务的优惠券过滤与排序

说明：

- 旧版依赖本机 MySQL/Redis 的 `@SpringBootTest` 集成测试已移除
- 现在默认测试不依赖外部基础设施，更适合持续回归

## 12. 关键取舍

这版升级刻意没有做的事情：

- 没上复杂多 Agent 编排
- 没引入外部向量数据库
- 没把 MCP 作为外部 server 铺开

原因不是不会，而是当前业务里：

- 动态事实最可靠的来源仍然是业务 service，所以 RAG 是补强背景而不是替代工具
- 推荐链路更适合后端先召回和打分，再让模型负责解释
- 用本地向量库先把 RAG 落地，比直接引入外部基础设施更适合这个项目

## 13. 后续可扩展方向

- 给 Agent 增加流式输出接口
- 为 RAG 增加增量索引和定时重建
- 增加 tool trace 持久化和可观测性看板
- 细化推荐排序因子和用户画像
- 为秒杀、缓存链路补充更完整的集成测试

## 14. 文档索引

- `docs/AGENT_ARCHITECTURE.md`
- `docs/AGENT_API.md`
- `AI_AGENT_INTERVIEW_PREP.md`

## 15. 企业级增强补充

这一轮升级不只是把项目做成“能跑的 Agent Demo”，还把几个真正影响企业落地的能力补进来了，而且都沿着原项目风格演进，没有把系统改得面目全非。

已经落地的增强包括：

- 流式输出接口：新增 `POST /ai/agent/chat/stream`，基于 `SseEmitter` 返回增量结果，便于前端做逐字输出、长答案渲染和可中断交互。
- 接口级限流：对 `/ai/agent/chat` 和 `/ai/agent/chat/stream` 做独立配额控制，优先按登录用户限流，未登录或匿名场景回退到 IP 维度。
- 审计留痕：新增 Agent 审计服务，把请求、计划、trace、耗时、结果状态写入 Redis Stream，便于后续排障、回放和安全审计。
- 请求链路追踪：为每次请求生成 `traceId` 并写入日志 MDC，联调时可以把控制器、服务、Agent 编排、审计记录串起来看。
- 观测与健康检查：接入 Spring Boot Actuator 和 Prometheus 指标导出，保留健康检查、指标、Prometheus 抓取入口。
- 生产参数分层：新增 `application-prod.yaml`，把并发线程、压缩、优雅停机、Agent 开关和限流阈值做成更适合线上环境的配置。

对应实现入口：

- `src/main/java/com/hmdp/controller/AiAgentController.java`
- `src/main/java/com/hmdp/service/impl/AiAgentServiceImpl.java`
- `src/main/java/com/hmdp/interceptor/AgentRateLimitInterceptor.java`
- `src/main/java/com/hmdp/service/impl/AgentRateLimitServiceImpl.java`
- `src/main/java/com/hmdp/service/impl/AgentAuditServiceImpl.java`
- `src/main/java/com/hmdp/config/AgentOpsConfig.java`
- `src/main/java/com/hmdp/config/RequestTraceFilter.java`
- `src/main/resources/application-prod.yaml`

## 16. 生产部署建议

如果要把这版作为企业内部 PoC、业务中台智能问答、导购助手或运营助手的后端服务，建议至少按下面方式部署：

- 启动时使用 `prod` profile。
- AI 网关、模型名、Embedding 模型、数据库密码、Redis 密码全部走环境变量，不要把真实密钥回写进仓库。
- Redis 至少承担三类职责：登录态、业务缓存、Agent 会话记忆与审计流；生产环境建议独立库或独立 key 前缀隔离。
- 首次上线前先执行一次向量索引构建，再把 `AI_RAG_REBUILD_ON_STARTUP` 关回去，避免每次重启都全量重建。
- Prometheus 抓取 `/actuator/prometheus`，应用健康检查走 `/actuator/health`。
- 流式接口前面如果挂 Nginx，需要关闭代理缓冲或对 SSE 单独配置，避免前端收不到增量内容。

最小化的生产启动示例：

```bash
java -jar target/hm-dianping-0.0.1-SNAPSHOT.jar --spring.profiles.active=prod
```

## 17. 新增环境变量

除了前面原有的 AI / MySQL / Redis 配置，这一轮新增了更偏生产治理的参数：

| 变量名 | 说明 | 默认值 |
| --- | --- | --- |
| `AI_RATE_LIMIT_ENABLED` | 是否启用 Agent 限流 | `true` |
| `AI_RATE_LIMIT_WINDOW_SECONDS` | 限流窗口秒数 | `60` |
| `AI_RATE_LIMIT_CHAT_MAX_REQUESTS` | 普通对话窗口内最大请求数 | `30` |
| `AI_RATE_LIMIT_STREAM_MAX_REQUESTS` | 流式对话窗口内最大请求数 | `10` |
| `AI_AUDIT_ENABLED` | 是否启用审计留痕 | `true` |
| `AI_AUDIT_STREAM_KEY` | Redis Stream 审计 key | `hmdp:agent:audit` |
| `AI_AUDIT_STREAM_MAX_LEN` | 审计流最大长度 | `5000` |
| `AI_AUDIT_INCLUDE_ANSWER` | 审计中是否保留回答正文 | `false` |

## 18. 当前版本交付结论

这版现在已经具备下面这些可以直接对企业讲清楚的交付特征：

- 保留了原黑马点评的高并发 Redis 业务链路，没有把原项目亮点抹掉。
- Agent 侧不是空壳聊天，而是“规划 + RAG + 工具调用 + 记忆”的完整可运行闭环。
- 有流式接口、限流、审计、链路追踪、Actuator、Prometheus、prod 配置这些生产化基础能力。
- `mvn test` 和 `mvn -DskipTests package` 已通过，具备可回归、可打包、可继续上线化演进的条件。
