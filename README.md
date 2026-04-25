# Zyro Go

[中文](#中文) | [English](#english)

Enterprise-oriented local-life agent backend built with Spring Boot, Spring AI, Redis, and MyBatis-Plus.

## 中文

### 简介

`zyro-go` 是一个面向本地生活场景的 Agent 后端项目。在保留原有高并发 Redis 业务链路的基础上，引入了结构化规划、RAG、工具调用、会话记忆、流式输出、限流、审计与基础可观测能力。

### 特性

- Java 21 + Spring Boot 3.5
- Spring AI Agent 编排
- 结构化规划与工具调用
- 基于 `SimpleVectorStore` 的本地向量 RAG
- Redis 会话记忆
- SSE 流式对话接口
- 限流与审计留痕
- Actuator 与 Prometheus 指标
- 核心 Agent 链路单元测试

### 技术栈

- Java 21
- Spring Boot 3.5.13
- Spring AI 1.1.4
- MyBatis-Plus 3.5.15
- Redis / Redisson
- MySQL 8
- Maven

### 主要接口

- `POST /ai/agent/chat`
- `POST /ai/agent/chat/stream`
- `DELETE /ai/agent/session`

### 快速开始

#### 环境要求

- JDK 21
- Maven 3.9+
- MySQL 8.x
- Redis 6.x+

#### 配置

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

#### 启动

```bash
mvn spring-boot:run
```

或：

```bash
mvn clean package -DskipTests
java -jar target/*.jar --spring.profiles.active=prod
```

#### 测试

```bash
mvn test
```

### 文档

- [Architecture](docs/AGENT_ARCHITECTURE.md)
- [API](docs/AGENT_API.md)
- [Interview Notes](AI_AGENT_INTERVIEW_PREP.md)

### License

本项目使用 MIT License，见 [LICENSE](LICENSE)。

## English

### Overview

`zyro-go` is an enterprise-oriented local-life agent backend that combines a Redis-based high-concurrency business core with modern agent capabilities such as planning, retrieval, tool use, chat memory, streaming, rate limiting, auditing, and observability.

### Highlights

- Built with Java 21 and Spring Boot 3.5
- Spring AI powered agent workflow
- Structured planning and tool calling
- Local vector RAG with `SimpleVectorStore`
- Redis-backed short-term memory
- SSE streaming chat endpoint
- Rate limiting and audit trail
- Actuator and Prometheus integration
- Testable core agent service layer

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

Or package first:

```bash
mvn clean package -DskipTests
java -jar target/*.jar --spring.profiles.active=prod
```

#### Test

```bash
mvn test
```

### Endpoints

- `POST /ai/agent/chat`
- `POST /ai/agent/chat/stream`
- `DELETE /ai/agent/session`

### Docs

- [Architecture](docs/AGENT_ARCHITECTURE.md)
- [API](docs/AGENT_API.md)
- [Interview Notes](AI_AGENT_INTERVIEW_PREP.md)

### License

MIT License. See [LICENSE](LICENSE).
