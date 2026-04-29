<h1 align="center">zyro-local</h1>

<p align="center">
  Enterprise-grade local-life agent backend with Spring Boot, Spring AI, Redis, and MyBatis-Plus.
</p>

<p align="center">
  <a href="#zh">中文</a> |
  <a href="#en">English</a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Java-21-3c873a?style=flat-square" alt="Java 21" />
  <img src="https://img.shields.io/badge/Spring_Boot-3.5-6db33f?style=flat-square" alt="Spring Boot 3.5" />
  <img src="https://img.shields.io/badge/Spring_AI-1.1-0ea5e9?style=flat-square" alt="Spring AI 1.1" />
  <img src="https://img.shields.io/badge/RAG-guide--first-7c3aed?style=flat-square" alt="Guide-first RAG" />
  <img src="https://img.shields.io/badge/Redis-high_concurrency-dc2626?style=flat-square" alt="Redis high concurrency" />
  <img src="https://img.shields.io/badge/License-MIT-111827?style=flat-square" alt="MIT License" />
</p>

<p align="center">
  <a href="#overview-en">Overview</a> |
  <a href="#highlights-en">Highlights</a> |
  <a href="#quick-start-en">Quick Start</a> |
  <a href="#deployment-en">Deployment</a> |
  <a href="#verification-en">Verification</a> |
  <a href="#documentation-en">Documentation</a>
</p>

---

<a id="zh"></a>

## 中文

### 项目简介

`zyro-local` 是一个面向本地生活业务的企业级 Agent 后端项目。它不是在传统点评项目上“包一层聊天接口”，而是把动态业务事实、静态知识、模型规划、工具调用、推荐执行、会话记忆和可观测能力明确拆层，形成一条更适合真实业务演进的 Java Agent 主链。

这个项目保留了原始项目在 Redis、高并发、缓存、秒杀链路上的工程基础，并进一步补齐：
- 结构化语义规划
- 受控工具调用
- Guide-first RAG
- Redis 会话记忆
- SSE 流式输出
- 限流、审计、trace、metrics
- 可复现、可部署、可解释的 Agent 工程实践

### 这不是一个什么项目

- 不是只会“陪聊”的 Demo
- 不是把所有业务数据一股脑塞进 RAG 的试验品
- 不是为了堆技术栈而堆技术栈
- 不是只能跑 happy path 的面试演示项目

### 核心亮点

- `结构化规划优先`：先把自然语言变成结构化执行计划，再决定是否检索、是否调用工具。
- `动态事实工具化`：门店、优惠、位置、博客等动态数据统一走工具和数据库，不让模型自由猜。
- `RAG 有边界`：RAG 主要承载静态规则、客服说明、使用帮助、业务解释，不直接主导实时推荐。
- `高并发底座保留`：没有为了做 Agent 把原本 Redis 场景下的工程价值丢掉。
- `流式体验完整`：支持 SSE 流式输出，适配前端实时聊天。
- `工程治理在线`：具备限流、审计、trace、Actuator、Prometheus 等基础生产能力。
- `模型热插拔友好`：沿用 Spring AI 的统一抽象，尽量避免 provider 专属分支。

### 一条典型链路怎么跑

用户输入：
> 帮我推荐一下广州正佳附近好吃不贵、适合两个人约会的餐厅，不要火锅

后端执行链路：
1. `Planner` 把自然语言解析成结构化 `AgentExecutionPlan`
2. `Location` 解析城市、地点、附近意图、预算、人数、排除类别
3. `Tool Layer` 查询门店、坐标、分类、价格带、评价等真实业务事实
4. `RAG` 只在需要解释规则、客服说明、平台使用帮助时补充静态知识
5. `LLM` 基于结构化计划和工具结果组织最终回答
6. `Streaming` 以 SSE 形式把答案流式返回前端

### 技术架构

| Layer | Responsibility | Stack |
| --- | --- | --- |
| API Layer | HTTP / SSE 接口、登录态、限流、请求入口 | Spring MVC, Interceptor |
| Planning Layer | 意图识别、结构化计划、语义兜底 | Spring AI, custom planner |
| Tool Layer | 门店、博客、位置、优惠等动态事实访问 | Service, MyBatis-Plus, Redis |
| Knowledge Layer | 静态指南、客服说明、产品规则 | local RAG, embeddings |
| Memory Layer | 多轮会话短期记忆 | Redis |
| Governance Layer | 审计、trace、health、metrics | Actuator, Prometheus |

### 技术栈

| Layer | Stack |
| --- | --- |
| Runtime | Java 21, Spring Boot 3.5.13 |
| Agent | Spring AI 1.1.4 |
| Data | MySQL 8, Redis, Redisson |
| ORM | MyBatis-Plus 3.5.15 |
| Observability | Spring Boot Actuator, Prometheus |
| Build | Maven |

### 技术取舍说明

#### 为什么继续用 Spring AI

项目已经运行在 Spring Boot 体系里。继续沿用 Spring AI，可以统一复用：
- chat model 抽象
- advisor / memory 能力
- tool calling
- OpenAI-compatible provider 接入方式

这样做的好处不是“技术更新”，而是“系统更稳、后续更好维护、provider 更容易热插拔”。

#### 为什么不把实时门店数据直接放进 RAG

因为门店、距离、价格、优惠、库存、营业状态这些都属于动态业务事实，时效性强，放进 RAG 很容易过期。对企业项目来说，最怕的不是“答得慢一点”，而是“答得像真的但其实错了”。

所以这里明确分层：
- 动态事实：走工具和数据库
- 静态知识：走 RAG
- 模型：负责理解、编排、表达

#### 为什么推荐主链优先走工具

推荐结果不是纯文本创作，它本质上是业务事实输出。企业场景里更重要的是：
- 可解释
- 可审计
- 可复现
- 可验证

因此推荐不依赖模型自由发挥，而是优先使用工具层给出的真实候选，再由模型做最后表达。

#### 为什么采用“模型优先，规则兜底”

纯规则在口语、省略、噪声、否定偏好等复杂输入下很快失稳；纯模型又容易越界。当前策略是：
- planner 模型负责主理解
- sanitize 只做保守修正
- fallback 只在模型失败时兜底

这是效果和稳定性的折中。

#### 为什么无命中时保守回答

如果当前数据不满足条件，系统优先明确告诉用户“当前没有可靠候选”，而不是强行伪造结果。对企业系统来说，可信度比表面上的“总能答一点”更重要。

### 可复现

#### 环境要求

- JDK 21
- Maven 3.9+
- MySQL 8.x
- Redis 6.x+

#### 1. 初始化数据库

```bash
mysql -u root -p < db/hmdp.sql
```

#### 2. 配置核心参数

主要配置文件：
- `src/main/resources/application.yaml`
- `src/main/resources/application-prod.yaml`

常用环境变量：
- `MYSQL_URL`
- `MYSQL_USERNAME`
- `MYSQL_PASSWORD`
- `REDIS_HOST`
- `REDIS_PORT`
- `REDIS_PASSWORD`
- `AI_ENABLED`
- `AI_BASE_URL`
- `AI_API_KEY`
- `AI_MODEL`
- `AI_EMBEDDING_PROVIDER`
- `AI_EMBEDDING_BASE_URL`
- `AI_EMBEDDING_API_KEY`
- `AI_EMBEDDING_MODEL`

如果本地 Redis 没有密码，启动时记得覆盖：

```bash
--spring.data.redis.password=
```

#### 3. 本地启动

开发模式：

```bash
mvn spring-boot:run
```

打包运行：

```bash
mvn clean package -DskipTests
java -jar target/hm-dianping-0.0.1-SNAPSHOT.jar --spring.profiles.active=prod
```

常用本地联调启动示例：

```bash
java -jar target/hm-dianping-0.0.1-SNAPSHOT.jar ^
  --spring.data.redis.password= ^
  --hmdp.ai.embedding.provider=local-hash ^
  --hmdp.ai.rag-rebuild-on-startup=false
```

#### 4. 最小验证

应用启动后至少验证：
- `GET /actuator/health`
- `POST /ai/agent/chat`
- `POST /ai/agent/chat/stream`

示例请求：

```bash
curl -X POST "http://127.0.0.1:8081/ai/agent/chat" ^
  -H "Content-Type: application/json" ^
  -d "{\"message\":\"帮我推荐广州体育西附近好吃不贵的餐厅\"}"
```

### 可部署

<a id="deployment-zh"></a>

#### 部署前提

- MySQL、Redis 可用
- 模型 provider 可访问
- 生产环境配置已注入
- 监控采集可抓取 `/actuator/prometheus`

#### 推荐部署方式

1. 使用 `prod` profile 打包发布
2. 将数据库、Redis、模型配置通过环境变量或外部配置注入
3. 打开健康检查与 metrics
4. 通过 Nginx 或网关转发 SSE 路由
5. 对 `/ai/agent/chat` 与 `/ai/agent/chat/stream` 做压测和灰度验证

#### 生产关注点

- Redis 连接池与密码配置一致
- provider base-url 与模型名配置正确
- SSE 路由不要被代理层错误缓存或缓冲
- RAG 重建不要阻塞主业务链
- 限流和审计日志要保留

### 可解释

#### 为什么它是“可解释”的

因为它不是黑盒问答，而是显式拆成了几类责任：
- planner 负责意图和结构化计划
- 工具层负责动态业务事实
- RAG 负责静态知识
- LLM 负责最终自然语言输出

所以当结果不对时，可以判断到底是：
- 意图理解偏了
- 地点解析错了
- 工具召回不够
- 知识边界放错了
- 模型表达偏了

这就是企业项目非常看重的“可定位、可审计、可复盘”。

### 测试过程

<a id="verification-zh"></a>

#### 验证分层

- `编译验证`：先跑 `mvn -q -DskipTests compile`
- `单元测试`：重点覆盖地点解析、planner、推荐过滤
- `真实接口联调`：验证 `/ai/agent/chat` 和 `/ai/agent/chat/stream`
- `极端提示词回归`：重点测口语、省略、噪声、预算、否定偏好、地点短句

#### 我重点验证什么

- 不再出现 `Agent 调用失败`
- 推荐类请求不再误落到 `general` 或 `factual_lookup`
- `locationHint / keyword` 不再出现明显脏值
- 否定偏好、预算、人数、细类目能进入结构化 plan
- 流式输出能正常返回

#### 典型回归样本

- `广州五山附近的人均30以内的快餐店推荐`
- `体育西? 便宜点 吃饭`
- `广州正佳附近不要火锅，想吃点清淡的，两个人，80以内`
- `广州正佳!!!预算80，两个人，约会，安静一点，别太贵`
- `SM附近来点学生党喝得起的咖啡或者奶茶`
- `给我找个附近吃饭的地方`

#### 测试命令

```bash
mvn test
```

只跑 Agent 相关测试：

```bash
mvn -Dtest=LocationTextParserTest,AgentPlanningServiceImplTest,ShopRecommendationServiceImplTest test
```

### API 与运维入口

业务接口：
- `POST /ai/agent/chat`
- `POST /ai/agent/chat/stream`
- `DELETE /ai/agent/session`

运维接口：
- `GET /actuator/health`
- `GET /actuator/metrics`
- `GET /actuator/prometheus`
- `POST /ai/agent/knowledge/rebuild`

### 项目结构

```text
src/main/java/com/hmdp
|- ai                # Agent tools, trace, RAG support
|- config            # Spring, AI, Redis, observability config
|- controller        # HTTP endpoints
|- dto               # Request / response models
|- entity            # MySQL entities
|- interceptor       # Login, token refresh, rate limit
|- service           # Business and agent services
`- utils             # Redis, cache, auth, helper utilities
```

### 文档

- [Architecture](docs/AGENT_ARCHITECTURE.md)
- [API](docs/AGENT_API.md)
- [Interview Notes](docs/AI_AGENT_INTERVIEW_PREP.md)
- [Troubleshooting](docs/AGENT_TROUBLESHOOTING.md)
- [Docs Index](docs/README.md)

---

<a id="en"></a>

## English

<a id="overview-en"></a>

### Overview

`zyro-local` is an enterprise-grade local-life agent backend. It keeps the original Redis-based high-concurrency business backbone and upgrades it into a production-oriented agent system with semantic planning, controlled tool calling, guide-first RAG, session memory, streaming responses, and operational governance.

This is not a thin chat wrapper over CRUD. It explicitly separates dynamic business facts, static knowledge, model reasoning, and recommendation execution so the system stays reproducible, deployable, and explainable.

### What This Project Solves

- It turns a traditional local-life backend into an agent-ready service layer.
- It keeps dynamic recommendations grounded in real business data.
- It preserves high-concurrency engineering value instead of replacing it with a toy AI demo.
- It gives interview-ready and production-ready engineering stories: planning, tool calling, RAG boundaries, streaming, observability, and regression hardening.

<a id="highlights-en"></a>

### Highlights

- `Structured planning first`: convert natural language into an execution plan before retrieval or tools.
- `Tool-grounded facts`: shops, coupons, blogs, geo information, and other dynamic facts come from tools and databases.
- `Guide-first RAG`: static rules, help content, and service explanations go to RAG; real-time facts do not.
- `Redis-backed memory`: short-term session continuity for multi-turn chat.
- `Streaming-ready`: SSE output for real-time chat UX.
- `Operational baseline`: rate limiting, audit trail, trace context, health endpoints, and metrics.
- `Hot-swappable provider strategy`: keep model integration under Spring AI abstractions instead of provider-specific branches.

### End-to-End Flow

Example request:
> Recommend a good but affordable restaurant near Grandview Mall in Guangzhou for a date, but no hotpot.

Execution flow:
1. `Planner` converts the request into a structured `AgentExecutionPlan`
2. `Location` parsing extracts city, location hint, nearby intent, budget, party size, and negative preferences
3. `Tool Layer` queries real shop, geo, category, and pricing data
4. `RAG` is used only when static guidance or product explanation is needed
5. `LLM` composes the final response using structured plan plus tool-grounded facts
6. `Streaming` sends the answer back to the client over SSE

### Architecture

| Layer | Responsibility | Stack |
| --- | --- | --- |
| API Layer | HTTP / SSE entrypoints, auth context, rate limit | Spring MVC, Interceptor |
| Planning Layer | intent routing, structured plan, fallback | Spring AI, custom planner |
| Tool Layer | dynamic facts for shops, blogs, geo, coupons | Service, MyBatis-Plus, Redis |
| Knowledge Layer | static guides, help content, business rules | local RAG, embeddings |
| Memory Layer | short-term session continuity | Redis |
| Governance Layer | audit, trace, metrics, health | Actuator, Prometheus |

### Stack

| Layer | Stack |
| --- | --- |
| Runtime | Java 21, Spring Boot 3.5.13 |
| Agent | Spring AI 1.1.4 |
| Data | MySQL 8, Redis, Redisson |
| ORM | MyBatis-Plus 3.5.15 |
| Observability | Spring Boot Actuator, Prometheus |
| Build | Maven |

### Technical Trade-offs

#### Why Spring AI instead of introducing another framework

The project already runs inside the Spring Boot ecosystem. Staying with Spring AI keeps chat models, advisors, memory, tool calling, and OpenAI-compatible providers under one abstraction, which improves consistency and lowers maintenance cost.

#### Why not put real-time shop data directly into RAG

Distance, price, coupon availability, inventory, and business status are dynamic facts. They change too often and should come from tools or databases, not from a knowledge store that can go stale.

#### Why recommendation is tool-first

Recommendation output is a business fact, not just generated text. In real systems, the priorities are:
- explainability
- auditability
- reproducibility
- verifiability

That is why the recommendation path first obtains real candidates from tools, then lets the model produce the final wording.

#### Why model-first with conservative fallback

Rule-only systems become brittle on noisy, abbreviated, or preference-heavy prompts. Model-only systems drift without boundaries. The current design uses the planner model as the primary interpreter, while fallback rules only keep the system usable when the model path fails.

#### Why conservative degradation matters

When the system cannot produce a reliable result, it should say so instead of fabricating a plausible but incorrect recommendation.

### Reproducibility

<a id="quick-start-en"></a>

#### Requirements

- JDK 21
- Maven 3.9+
- MySQL 8.x
- Redis 6.x+

#### 1. Initialize the database

```bash
mysql -u root -p < db/hmdp.sql
```

#### 2. Configure the environment

Core config files:
- `src/main/resources/application.yaml`
- `src/main/resources/application-prod.yaml`

Common environment variables:
- `MYSQL_URL`
- `MYSQL_USERNAME`
- `MYSQL_PASSWORD`
- `REDIS_HOST`
- `REDIS_PORT`
- `REDIS_PASSWORD`
- `AI_ENABLED`
- `AI_BASE_URL`
- `AI_API_KEY`
- `AI_MODEL`
- `AI_EMBEDDING_PROVIDER`
- `AI_EMBEDDING_BASE_URL`
- `AI_EMBEDDING_API_KEY`
- `AI_EMBEDDING_MODEL`

If local Redis does not use a password:

```bash
--spring.data.redis.password=
```

#### 3. Start the application

Development mode:

```bash
mvn spring-boot:run
```

Packaged mode:

```bash
mvn clean package -DskipTests
java -jar target/hm-dianping-0.0.1-SNAPSHOT.jar --spring.profiles.active=prod
```

A common local verification startup example:

```bash
java -jar target/hm-dianping-0.0.1-SNAPSHOT.jar ^
  --spring.data.redis.password= ^
  --hmdp.ai.embedding.provider=local-hash ^
  --hmdp.ai.rag-rebuild-on-startup=false
```

#### 4. Minimal smoke check

Validate at least:
- `GET /actuator/health`
- `POST /ai/agent/chat`
- `POST /ai/agent/chat/stream`

Example:

```bash
curl -X POST "http://127.0.0.1:8081/ai/agent/chat" ^
  -H "Content-Type: application/json" ^
  -d "{\"message\":\"Recommend an affordable restaurant near Guangzhou Tiyuxi\"}"
```

<a id="deployment-en"></a>

### Deployment

#### Deployment prerequisites

- MySQL and Redis are available
- the model provider is reachable
- production config is injected externally
- `/actuator/prometheus` can be scraped

#### Recommended deployment pattern

1. package with the `prod` profile
2. inject database, Redis, and model settings with env vars or external config
3. expose health and metrics endpoints
4. route SSE endpoints correctly through Nginx or gateway
5. run pressure tests and gray validation on `/ai/agent/chat` and `/ai/agent/chat/stream`

#### Production watch points

- Redis password and connection settings must match actual runtime
- provider base URL and model name must be correct
- SSE routes must not be buffered or cached incorrectly by proxies
- RAG rebuilds should not block the main business path
- rate-limit and audit logs should remain enabled

### Explainability

Why this system is explainable:
- planner handles intent and structured interpretation
- tools handle dynamic business facts
- RAG handles static knowledge
- LLM handles final wording

When a response is wrong, you can still locate whether the issue came from intent understanding, location parsing, tool recall, knowledge boundaries, or final model expression.

<a id="verification-en"></a>

### Verification Process

#### Validation layers

- `Compilation gate`: run `mvn -q -DskipTests compile`
- `Unit tests`: focus on location parsing, planner output, and recommendation filtering
- `Real API integration`: verify `/ai/agent/chat` and `/ai/agent/chat/stream`
- `Noisy prompt regression`: test abbreviated, noisy, geo-heavy, budget-bound, and negative-preference prompts

#### What is explicitly checked

- no more `Agent 调用失败`
- recommendation requests should not drift into `general` or `factual_lookup`
- `locationHint / keyword` should not contain obvious garbage
- budget, party size, negative preferences, and subcategories should enter the structured plan
- streaming output should work end to end

#### Representative regression prompts

- `广州五山附近的人均30以内的快餐店推荐`
- `体育西? 便宜点 吃饭`
- `广州正佳附近不要火锅，想吃点清淡的，两个人，80以内`
- `广州正佳!!!预算80，两个人，约会，安静一点，别太贵`
- `SM附近来点学生党喝得起的咖啡或者奶茶`
- `给我找个附近吃饭的地方`

#### Test commands

```bash
mvn test
```

Agent-focused tests:

```bash
mvn -Dtest=LocationTextParserTest,AgentPlanningServiceImplTest,ShopRecommendationServiceImplTest test
```

### API & Ops

- `POST /ai/agent/chat`
- `POST /ai/agent/chat/stream`
- `DELETE /ai/agent/session`
- `GET /actuator/health`
- `GET /actuator/metrics`
- `GET /actuator/prometheus`
- `POST /ai/agent/knowledge/rebuild`

### Project Layout

```text
src/main/java/com/hmdp
|- ai                # Agent tools, trace, and RAG support
|- config            # Spring, AI, Redis, and observability config
|- controller        # HTTP endpoints
|- dto               # Request / response models
|- entity            # MySQL entities
|- interceptor       # Login, token refresh, and rate limit
|- service           # Business and agent services
`- utils             # Redis, cache, auth, and helper utilities
```

<a id="documentation-en"></a>

### Documentation

- [Architecture](docs/AGENT_ARCHITECTURE.md)
- [API](docs/AGENT_API.md)
- [Interview Notes](docs/AI_AGENT_INTERVIEW_PREP.md)
- [Troubleshooting](docs/AGENT_TROUBLESHOOTING.md)
- [Docs Index](docs/README.md)

## License

MIT License. See [LICENSE](LICENSE).
