# Agent API

## 1. 鉴权说明

Agent 接口不在匿名白名单中，默认需要登录。

请求头：

```http
Authorization: <token>
```

`token` 由原有登录接口 `/user/login` 返回。

## 2. 对话接口

### 2.1 URL

```http
POST /ai/agent/chat
```

### 2.2 请求体

```json
{
  "message": "给我推荐附近 100 元以内、有券的火锅",
  "conversationId": "dinner-plan",
  "useKnowledge": true,
  "knowledgeTopK": 4
}
```

字段说明：

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `message` | 是 | 用户输入 |
| `conversationId` | 否 | 会话 ID；为空时默认使用 `default` |
| `useKnowledge` | 否 | 是否启用知识补充；为空时走配置默认值 |
| `knowledgeTopK` | 否 | 知识召回条数；为空时走配置默认值 |

### 2.3 响应体

```json
{
  "success": true,
  "data": {
    "conversationId": "dinner-plan",
    "answer": "如果你想控制预算，优先看辣府火锅，它评分高、距离近，而且当前有券。",
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

字段说明：

| 字段 | 说明 |
| --- | --- |
| `conversationId` | 当前对话所属会话 |
| `answer` | 模型最终回答 |
| `toolTrace` | 本轮规划、检索、工具调用轨迹 |
| `retrievalHits` | 混合检索命中的背景片段 |
| `plan` | 本轮结构化执行计划 |

## 3. 清理会话接口

### 3.1 URL

```http
DELETE /ai/agent/session?conversationId=dinner-plan
```

说明：

- `conversationId` 为空时，默认清理 `default`
- 清理的是当前登录用户对应会话，不会影响其他用户

### 3.2 成功响应

```json
{
  "success": true,
  "data": null
}
```

## 4. 错误场景

典型返回：

### 4.1 Agent 未启用

```json
{
  "success": true,
  "data": {
    "conversationId": "default",
    "answer": "AI Agent 当前未启用，请先设置 AI_ENABLED=true。",
    "toolTrace": [],
    "retrievalHits": []
  }
}
```

### 4.2 未登录

由于接口受登录拦截器保护，通常会先返回 HTTP 401。

### 4.3 模型网关异常

返回说明性文本：

```json
{
  "success": true,
  "data": {
    "conversationId": "default",
    "answer": "Agent 调用失败，请检查模型网关、API Key 或稍后重试。",
    "toolTrace": [],
    "retrievalHits": []
  }
}
```

## 5. 推荐调试方法

联调时建议看三件事：

1. `plan` 是否和用户意图匹配
2. `toolTrace` 是否符合预期
3. `retrievalHits` 是否说明 RAG 拿到了正确背景

如果 `answer` 很空泛，通常先看：

- 是否真的触发了 `recommend_shops`
- 是否有命中知识补充
- 模型配置是否可用

## 6. 流式对话接口

### 6.1 URL

```http
POST /ai/agent/chat/stream
```

### 6.2 说明

- 使用 `SSE` 返回增量内容。
- 鉴权方式和普通对话接口一致，仍然需要 `Authorization` 头。
- 首个事件会返回 `meta`，其中包含 `conversationId`、`plan`、`retrievalHits`。
- 中间事件会持续返回 `chunk`。
- 结束时返回 `done`，结构与普通 `chat` 接口中的 `data` 基本一致。
- 如果中途失败，会返回 `error` 事件。

### 6.3 事件示例

```text
event: meta
data: {"conversationId":"dinner-plan","plan":{"intent":"recommendation"}}

event: chunk
data: {"content":"如果你想控制预算，"}

event: chunk
data: {"content":"我更建议先看附近有券的火锅店。"}

event: done
data: {"conversationId":"dinner-plan","answer":"如果你想控制预算，我更建议先看附近有券的火锅店。"}
```

## 7. 限流与错误码

- `/ai/agent/chat` 和 `/ai/agent/chat/stream` 都会经过 `AgentRateLimitInterceptor`。
- 默认优先按登录用户限流；拿不到用户时回退到 IP。
- 当请求过于频繁时返回 HTTP `429`。

示例：

```json
{
  "success": false,
  "errorMsg": "请求过于频繁，请稍后再试"
}
```

## 8. 审计与追踪

- 每次 Agent 对话都会生成 `traceId` 并进入日志上下文。
- 审计记录会写入 Redis Stream，默认 key 为 `hmdp:agent:audit`。
- 审计内容包含用户、会话、请求、计划、trace、耗时、状态等字段。
- 默认不把完整回答正文写进审计流，避免审计数据量和隐私风险过高；如有需要可通过配置开启。
