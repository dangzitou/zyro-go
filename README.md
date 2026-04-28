<h1 align="center">zyro-local</h1>

<p align="center">
  Enterprise local-life agent backend built with Spring Boot, Spring AI, Redis, and MyBatis-Plus.
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
  <a href="#overview">Overview</a> |
  <a href="#highlights">Highlights</a> |
  <a href="#quick-start-en">Quick Start</a> |
  <a href="#api--ops">API &amp; Ops</a> |
  <a href="#documentation">Documentation</a>
</p>

<a id="zh"></a>

## 中文

### 项目简介
`zyro-local` 是一个面向本地生活场景的企业级 Agent 后端项目。它保留了原始项目在 Redis、高并发、缓存一致性、秒杀链路上的工程基础，并在此之上补齐了语义规划、工具调用、会话记忆、流式输出、RAG、限流、审计、基础可观测性与可运维能力。

它不是“给传统 CRUD 套一层聊天壳”，而是把动态业务事实、静态知识、模型理解和推荐执行分层治理，做成一个更适合真实业务演进的 Java Agent Backend。

### 核心亮点
- `Structured Planning`：先做结构化语义规划，再决定是否检索知识、是否调用工具。
- `Tool Calling`：门店、优惠、博客、地理位置等动态事实统一通过受控工具链访问。
- `Guide-first RAG`：RAG 主要承载静态规则、客服说明、使用指南，不把实时业务事实直接塞进知识库。
- `Conversation Memory`：基于 Redis 的短期会话记忆，支持多轮对话延续。
- `Streaming Response`：支持 SSE 流式输出，适合前端实时聊天体验。
- `Governance`：内置限流、审计、trace、Actuator、Prometheus 指标暴露。
- `High Concurrency Backbone`：延续并强化 Redis 场景下的高并发业务能力，不牺牲原项目价值。

### 技术栈

| Layer | Stack |
| --- | --- |
| Runtime | Java 21, Spring Boot 3.5.13 |
| Agent | Spring AI 1.1.4 |
| Data | MySQL 8, Redis, Redisson |
| ORM | MyBatis-Plus 3.5.15 |
| Ops | Spring Boot Actuator, Prometheus |
| Build | Maven |

### 技术取舍
- `为什么继续用 Spring AI`：项目已经运行在 Spring Boot 体系内，继续沿用 Spring AI 可以复用 memory、advisor、tool calling、OpenAI-compatible provider 能力，统一性和维护成本更优。
- `为什么不是所有数据都做 RAG`：实时门店、距离、优惠、库存、营业状态这类动态事实必须走工具和数据库，避免知识过期导致错误推荐。
- `为什么推荐优先走工具，不让模型直接猜`：推荐结果属于业务事实输出，企业场景里“可解释、可审计、可复现”比“看起来更像 AI”更重要。
- `为什么模型优先、规则兜底`：纯规则在复杂口语和极端提示词下很快失稳；纯模型又缺少边界约束。当前方案用 planner 做主理解，fallback 只负责保底可用。
- `为什么保留保守降级`：当数据缺失或条件过严时，优先返回“当前无可靠结果”，而不是伪造命中结果。

<a id="quick-start-zh"></a>

### 快速开始

#### 环境要求
- JDK 21
- Maven 3.9+
- MySQL 8.x
- Redis 6.x+

#### 1. 初始化数据库

执行项目内 SQL：

```bash
mysql -u root -p < db/hmdp.sql
```

#### 2. 配置环境

核心配置文件：
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

如果你的本地 Redis 没有密码，启动时记得覆盖：

```bash
--spring.data.redis.password=
```

#### 3. 启动应用

开发模式：

```bash
mvn spring-boot:run
```

打包运行：

```bash
mvn clean package -DskipTests
java -jar target/hm-dianping-0.0.1-SNAPSHOT.jar --spring.profiles.active=prod
```

常用本地联调启动参数示例：

```bash
java -jar target/hm-dianping-0.0.1-SNAPSHOT.jar ^
  --spring.data.redis.password= ^
  --hmdp.ai.embedding.provider=local-hash ^
  --hmdp.ai.rag-rebuild-on-startup=false
```

#### 4. 运行测试

```bash
mvn test
```

只跑 Agent 相关测试：

```bash
mvn -Dtest=ShopRecommendationServiceImplTest,AiAgentServiceImplTest test
```

### 测试与验证过程
- `编译验证`：先跑 `mvn -q -DskipTests compile`，保证语义规划、推荐链路、文档修订后的代码整体可编译。
- `针对性单测`：重点覆盖 `LocationTextParserTest`、`AgentPlanningServiceImplTest`、`ShopRecommendationServiceImplTest`，验证地点抽取、planner 输出、预算与偏好过滤。
- `真实接口联调`：对 `/ai/agent/chat` 和 `/ai/agent/chat/stream` 做真实请求，验证 planner、tool calling、SSE 输出、会话链路是否打通。
- `极端提示词回归`：使用“体育西? 便宜点 吃饭”“五山附近!!! 30以内!! 快餐!!”“SM附近来点学生党喝得起的咖啡或者奶茶”等口语化、噪声化、否定偏好样本反复轰炸，避免只在 happy path 生效。
- `验收口径`：不再出现 `Agent 调用失败`、不再出现明显脏 `locationHint/keyword`、推荐类问句应稳定落到 `recommendation + recommend_shops` 主链。

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

<a id="documentation"></a>

### 文档
- [Architecture](docs/AGENT_ARCHITECTURE.md)
- [API](docs/AGENT_API.md)
- [Interview Notes](docs/AI_AGENT_INTERVIEW_PREP.md)
- [Troubleshooting](docs/AGENT_TROUBLESHOOTING.md)
- [Docs Index](docs/README.md)

<a id="en"></a>

## English

<a id="overview"></a>

### Overview
`zyro-local` is an enterprise-oriented local-life agent backend. It keeps the original Redis-based high-concurrency business backbone while adding semantic planning, controlled tool calling, conversation memory, streaming responses, RAG, rate limiting, auditability, and baseline observability.

This is not a thin chat wrapper over CRUD. It separates dynamic business facts, static knowledge, model reasoning, and recommendation execution into explicit layers so the system remains explainable, testable, and production-friendly.

<a id="highlights"></a>

### Highlights
- Structured planning before execution
- Controlled tool calling for dynamic business facts
- Guide-first local RAG for static rules, policies, and explanations
- Redis-backed short-term conversation memory
- SSE streaming chat endpoint
- Rate limiting, audit trail, trace context, and metrics
- Production-oriented high-concurrency business backbone

### Stack

| Layer | Stack |
| --- | --- |
| Runtime | Java 21, Spring Boot 3.5.13 |
| Agent | Spring AI 1.1.4 |
| Data | MySQL 8, Redis, Redisson |
| ORM | MyBatis-Plus 3.5.15 |
| Ops | Spring Boot Actuator, Prometheus |
| Build | Maven |

### Technical Trade-offs
- `Why Spring AI instead of introducing another framework`: the project already lives inside the Spring Boot ecosystem, so reusing Spring AI keeps provider integration, memory, advisors, and tool-calling consistent.
- `Why not put real-time shop facts into RAG`: distance, price, coupon availability, inventory, and business status change too often; they must come from tools and databases.
- `Why recommendation is tool-first`: recommendation output is a business fact, so controllability and auditability matter more than free-form model creativity.
- `Why model-first with conservative fallback`: rule-only understanding breaks under noisy prompts, while model-only pipelines drift without boundaries. The current balance keeps both quality and stability.
- `Why conservative degradation matters`: when data is missing, the system would rather say “no reliable result yet” than fabricate a plausible but wrong answer.

<a id="quick-start-en"></a>

### Quick Start

#### Requirements
- JDK 21
- Maven 3.9+
- MySQL 8.x
- Redis 6.x+

#### 1. Initialize the database

Run the bundled SQL file:

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

If your local Redis does not use a password, override it on startup:

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

A commonly used local verification startup command:

```bash
java -jar target/hm-dianping-0.0.1-SNAPSHOT.jar ^
  --spring.data.redis.password= ^
  --hmdp.ai.embedding.provider=local-hash ^
  --hmdp.ai.rag-rebuild-on-startup=false
```

#### 4. Run tests

```bash
mvn test
```

Run only the agent-focused tests:

```bash
mvn -Dtest=ShopRecommendationServiceImplTest,AiAgentServiceImplTest test
```

### Verification Process
- `Compilation gate`: run `mvn -q -DskipTests compile` first to ensure the whole codebase still builds after agent and doc updates.
- `Focused unit tests`: validate location parsing, planner output, and recommendation filtering with dedicated tests.
- `Real API integration`: hit `/ai/agent/chat` and `/ai/agent/chat/stream` to verify planner, tool calling, SSE streaming, and session memory end to end.
- `Noisy prompt regression`: repeatedly test short, ambiguous, budget-constrained, and preference-heavy prompts to avoid a happy-path-only demo.
- `Acceptance standard`: no `Agent 调用失败`, no obviously dirty `locationHint/keyword`, and recommendation prompts should consistently land on the `recommend_shops` path.

<a id="api--ops"></a>

### API & Ops

Business endpoints:
- `POST /ai/agent/chat`
- `POST /ai/agent/chat/stream`
- `DELETE /ai/agent/session`

Operational endpoints:
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

### Documentation
- [Architecture](docs/AGENT_ARCHITECTURE.md)
- [API](docs/AGENT_API.md)
- [Interview Notes](docs/AI_AGENT_INTERVIEW_PREP.md)
- [Troubleshooting](docs/AGENT_TROUBLESHOOTING.md)
- [Docs Index](docs/README.md)

## License

MIT License. See [LICENSE](LICENSE).
