<h1 align="center">Zyro Go</h1>

<p align="center">
  Enterprise-oriented local-life agent backend built with Spring Boot, Spring AI, Redis, and MyBatis-Plus.
</p>

<p align="center">
  <a href="#overview">Overview</a> |
  <a href="#highlights">Highlights</a> |
  <a href="#quick-start">Quick Start</a> |
  <a href="#api">API</a> |
  <a href="#documentation">Documentation</a> |
  <a href="#license">License</a>
</p>

<p align="center">
  <a href="#中文">中文</a> |
  <a href="#english">English</a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/runtime-Java_21-3c873a?style=flat-square" alt="runtime Java 21" />
  <img src="https://img.shields.io/badge/framework-Spring_Boot_3.5-6db33f?style=flat-square" alt="framework Spring Boot 3.5" />
  <img src="https://img.shields.io/badge/agent-Spring_AI_1.1-0ea5e9?style=flat-square" alt="agent Spring AI 1.1" />
  <img src="https://img.shields.io/badge/vector-RAG-7c3aed?style=flat-square" alt="vector RAG" />
  <img src="https://img.shields.io/badge/cache-Redis-red?style=flat-square" alt="cache Redis" />
  <img src="https://img.shields.io/badge/license-MIT-black?style=flat-square" alt="license MIT" />
</p>

## 中文

### 项目定位

`zyro-go` 是一个面向本地生活场景的 Agent 后端项目。它保留了原有 Redis 高并发业务链路，并在此基础上补齐了规划、RAG、工具调用、会话记忆、流式输出、限流、审计和基础可观测能力。

它不是一个“聊天接口套壳”，而是一个可回归、可部署、可继续工程化演进的 Java Agent backend。

### 核心能力

- `Structured Planning`：先规划，再决定工具与检索策略
- `Tool Calling`：业务事实通过受控工具返回
- `Local RAG`：基于 `SimpleVectorStore` 的本地静态规则检索
- `Redis Memory`：短期会话记忆与上下文窗口
- `Streaming`：`SSE` 流式对话接口
- `Governance`：限流、审计、trace、Actuator、Prometheus

### 技术栈

| Layer | Stack |
| --- | --- |
| Runtime | Java 21, Spring Boot 3.5.13 |
| Agent | Spring AI 1.1.4 |
| Data | MySQL 8, Redis, Redisson |
| ORM | MyBatis-Plus 3.5.15 |
| Ops | Actuator, Prometheus |
| Build | Maven |

### API

- `POST /ai/agent/chat`
- `POST /ai/agent/chat/stream`
- `DELETE /ai/agent/session`

详细说明见 [docs/AGENT_API.md](docs/AGENT_API.md)。

### Quick Start

#### Requirements

- JDK 21
- Maven 3.9+
- MySQL 8.x
- Redis 6.x+

#### Configuration

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

#### Run

```bash
mvn spring-boot:run
```

or

```bash
mvn clean package -DskipTests
java -jar target/*.jar --spring.profiles.active=prod
```

#### Test

```bash
mvn test
```

### Project Docs

- [Architecture](docs/AGENT_ARCHITECTURE.md)
- [API](docs/AGENT_API.md)
- [Interview Notes](AI_AGENT_INTERVIEW_PREP.md)

### License

MIT License. See [LICENSE](LICENSE).

## English

### Overview

`zyro-go` is an enterprise-oriented local-life agent backend. It keeps the original Redis-based high-concurrency business core and upgrades the system with planning, retrieval, tool use, short-term memory, streaming, rate limiting, auditing, and observability.

This project is designed as a practical Java agent backend rather than a thin chat wrapper.

### Highlights

- Structured planning before execution
- Controlled tool calling for dynamic facts
- Local vector RAG with `SimpleVectorStore`
- Redis-backed conversation memory
- SSE streaming chat endpoint
- Rate limiting, audit trail, trace context, metrics

### Stack

| Layer | Stack |
| --- | --- |
| Runtime | Java 21, Spring Boot 3.5.13 |
| Agent | Spring AI 1.1.4 |
| Data | MySQL 8, Redis, Redisson |
| ORM | MyBatis-Plus 3.5.15 |
| Ops | Actuator, Prometheus |
| Build | Maven |

### Endpoints

- `POST /ai/agent/chat`
- `POST /ai/agent/chat/stream`
- `DELETE /ai/agent/session`

Detailed API reference: [docs/AGENT_API.md](docs/AGENT_API.md)

### Quick Start

#### Requirements

- JDK 21
- Maven 3.9+
- MySQL 8.x
- Redis 6.x+

#### Run

```bash
mvn spring-boot:run
```

or

```bash
mvn clean package -DskipTests
java -jar target/*.jar --spring.profiles.active=prod
```

#### Test

```bash
mvn test
```

### Documentation

- [Architecture](docs/AGENT_ARCHITECTURE.md)
- [API](docs/AGENT_API.md)
- [Interview Notes](AI_AGENT_INTERVIEW_PREP.md)

### License

MIT License. See [LICENSE](LICENSE).
