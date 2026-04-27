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

`zyro-local` 是一个面向本地生活场景的企业级 Agent 后端项目。它保留了原有 Redis 高并发业务主链路，并在此基础上补齐了规划、RAG、工具调用、会话记忆、流式输出、限流、审计与基础可观测能力。

这个项目不是简单的聊天接口套壳，而是一个可以部署、联调、压测、持续工程化演进的 Java Agent backend。

### 核心特性

- `Structured Planning`：先规划，再决定是否检索、是否调用工具
- `Tool Calling`：店铺、优惠、博客等动态事实统一走受控工具
- `Guide-first RAG`：RAG 主要承载静态规则、说明和约束，不混入动态业务事实
- `Conversation Memory`：基于 Redis 的短期会话记忆
- `Streaming Response`：支持 SSE 流式输出
- `Governance`：内置限流、审计、trace、Actuator、Prometheus
- `High Concurrency`：保留并强化 Redis 场景下的高并发业务能力

### 技术栈

| Layer | Stack |
| --- | --- |
| Runtime | Java 21, Spring Boot 3.5.13 |
| Agent | Spring AI 1.1.4 |
| Data | MySQL 8, Redis, Redisson |
| ORM | MyBatis-Plus 3.5.15 |
| Ops | Spring Boot Actuator, Prometheus |
| Build | Maven |

<a id="quick-start-zh"></a>

### 快速开始

#### 环境要求

- JDK 21
- Maven 3.9+
- MySQL 8.x
- Redis 6.x+

#### 1. 初始化数据库

执行项目内的 SQL：

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

如果你本地 Redis 没有密码，启动时记得覆盖：

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

本项目当前常用本地联调启动参数示例：

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
- [Interview Notes](AI_AGENT_INTERVIEW_PREP.md)

<a id="en"></a>

## English

### Overview

`zyro-local` is an enterprise-oriented local-life agent backend. It keeps the original Redis-based high-concurrency business core while adding planning, RAG, controlled tool use, conversation memory, streaming, rate limiting, auditability, and baseline observability.

This project is designed as a practical Java agent backend rather than a thin chat wrapper.

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
- [Interview Notes](AI_AGENT_INTERVIEW_PREP.md)

## License

MIT License. See [LICENSE](LICENSE).
