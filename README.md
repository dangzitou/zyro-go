# Zyro Go

[中文](#中文) | [English](#english)

> Enterprise-oriented local-life agent backend built with Spring Boot, Spring AI, Redis, and MyBatis-Plus.

## 中文

### 项目定位

`zyro-go` 是一个面向本地生活场景的 Agent 后端项目。它保留了原有 Redis 高并发业务链路，并在此基础上补齐了规划、RAG、工具调用、会话记忆、流式输出、限流、审计和基础可观测能力。

它不是一个“聊天接口套壳”，而是一个可回归、可部署、可继续工程化演进的 Java Agent backend。

### 核心能力

- `Structured Planning`：先规划，再决定工具与检索策略
- `Tool Calling`：业务事实通过受控工具返回
- `Local RAG`：基于 `SimpleVectorStore` 的本地向量检索
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
