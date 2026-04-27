<h1 align="center">zyro-local</h1>

<p align="center">
  Enterprise local-life agent backend built with Spring Boot, Spring AI, Redis, and MyBatis-Plus.
</p>

<p align="center">
  <a href="#中文">中文</a> |
  <a href="#english">English</a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Java-21-3c873a?style=flat-square" alt="Java 21" />
  <img src="https://img.shields.io/badge/Spring_Boot-3.5-6db33f?style=flat-square" alt="Spring Boot 3.5" />
  <img src="https://img.shields.io/badge/Spring_AI-1.1-0ea5e9?style=flat-square" alt="Spring AI 1.1" />
  <img src="https://img.shields.io/badge/RAG-guide--first-7c3aed?style=flat-square" alt="RAG guide first" />
  <img src="https://img.shields.io/badge/Redis-high_concurrency-dc2626?style=flat-square" alt="Redis high concurrency" />
  <img src="https://img.shields.io/badge/License-MIT-111827?style=flat-square" alt="MIT License" />
</p>

<p align="center">
  <a href="#overview">Overview</a> ·
  <a href="#highlights">Highlights</a> ·
  <a href="#quick-start">Quick Start</a> ·
  <a href="#documentation">Documentation</a>
</p>

## 中文

### 项目简介

`zyro-local` 是一个面向本地生活场景的企业级 Agent 后端项目。它在原有 Redis 高并发业务链路基础上，补齐了规划、RAG、工具调用、会话记忆、流式输出、限流、审计与基础可观测能力。

它不是简单的聊天套壳，而是一个可以部署、联调、压测和持续工程化演进的 Java Agent backend。

### 核心特性

- `Planning`：先规划，再决定是否检索、是否调用工具
- `Tool Calling`：店铺、优惠、博客等动态事实统一走受控工具
- `Guide-first RAG`：RAG 主要承载静态规则与说明，不混入动态业务事实
- `Conversation Memory`：基于 Redis 的短期会话记忆
- `Streaming`：支持 SSE 流式输出
- `Governance`：内置限流、审计、trace、Actuator、Prometheus
- `High Concurrency`：保留并强化 Redis 场景下的高并发业务能力

### 技术栈

| Layer | Stack |
| --- | --- |
| Runtime | Java 21, Spring Boot 3.5.13 |
| Agent | Spring AI 1.1.4 |
| Data | MySQL 8, Redis, Redisson |
| ORM | MyBatis-Plus 3.5.15 |
| Ops | Actuator, Prometheus |
| Build | Maven |

### 快速开始

#### 环境要求

- JDK 21
- Maven 3.9+
- MySQL 8.x
- Redis 6.x+

#### 核心配置

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

或

```bash
mvn clean package -DskipTests
java -jar target/*.jar --spring.profiles.active=prod
```

#### 测试

```bash
mvn test
```

### 主要接口

- `POST /ai/agent/chat`
- `POST /ai/agent/chat/stream`
- `DELETE /ai/agent/session`

### 文档

- [Architecture](docs/AGENT_ARCHITECTURE.md)
- [API](docs/AGENT_API.md)
- [Interview Notes](AI_AGENT_INTERVIEW_PREP.md)

## English

### Overview

`zyro-local` is an enterprise-oriented local-life agent backend. It keeps the original Redis-based high-concurrency business core while adding planning, RAG, controlled tool use, conversation memory, streaming, rate limiting, auditability, and baseline observability.

This project is designed as a practical Java agent backend, not a thin chat wrapper.

### Highlights

- Structured planning before execution
- Controlled tool calling for dynamic business facts
- Guide-first local RAG for static rules and explanations
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
| Ops | Actuator, Prometheus |
| Build | Maven |

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

## License

MIT License. See [LICENSE](LICENSE).
