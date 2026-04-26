# Agent API

## 1. 鉴权说明

Agent 接口默认需要登录，不在匿名白名单中。

请求头：

```http
Authorization: <token>
```

`token` 由现有登录接口 `/user/login` 返回。

## 2. 会话对话接口

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

### 2.3 字段说明

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `message` | 是 | 用户输入 |
| `conversationId` | 否 | 会话 ID；为空时默认使用 `default` |
| `useKnowledge` | 否 | 是否启用知识检索；为空时走系统配置 |
| `knowledgeTopK` | 否 | 检索命中条数；范围 `1-8` |

### 2.4 响应体

```json
{
  "success": true,
  "data": {
    "conversationId": "dinner-plan",
    "answer": "如果你想控制预算，我建议先看附近有券的火锅店，我已经基于实时店铺和优惠数据筛出几家候选。",
    "toolTrace": [
      "plan(intent=recommendation, useKnowledge=true, preferredTools=[recommend_shops, get_shop_coupons])",
      "knowledge(query=火锅, hits=1, vectorRagReady=true)",
      "recommend_shops(keyword=火锅, typeId=-, budget=100, couponOnly=true, limit=-) -> 3 recommendation(s)",
      "get_shop_coupons(shopId=1) -> 2 coupon(s)"
    ],
    "retrievalHits": [
      {
        "sourceType": "guide",
        "sourceId": 2,
        "title": "动态事实约束",
        "snippet": "价格、评分、营业时间、优惠券、店铺详情等动态事实必须来自工具或数据库。",
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

### 2.5 响应字段说明

| 字段 | 说明 |
| --- | --- |
| `conversationId` | 当前会话 ID |
| `answer` | Agent 最终回复 |
| `toolTrace` | 规划、检索、工具调用轨迹 |
| `retrievalHits` | 背景知识命中片段，仅用于补充上下文 |
| `plan` | 本轮结构化执行计划 |

## 3. RAG 与动态数据边界

这是当前项目里最重要的约束之一：

- `retrievalHits` 只承载静态背景知识，不承载动态业务真相。
- 向量索引当前只收录一类内容：
  - 静态规则/说明文档 `guide`
- 店铺详情、价格、优惠券、评分、营业时间、库存等动态事实：
  - 不进入向量索引
  - 不应作为 `retrievalHits` 的事实依据
  - 必须通过工具调用或数据库查询获取
- 探店博客也不再进入 RAG：
  - 博客属于半动态内容
  - 更适合走工具查询或内容接口
  - 不再出现在 `retrievalHits`

换句话说，`RAG` 负责“补规则背景”，`Tools/DB` 负责“给业务真相”。

## 4. 流式对话接口

### 4.1 URL

```http
POST /ai/agent/chat/stream
```

### 4.2 说明

- 使用 `SSE` 返回增量内容
- 鉴权方式与普通对话接口一致，仍然需要 `Authorization`
- 首个事件返回 `meta`，包含 `conversationId`、`plan`、`retrievalHits`
- 中间持续返回 `chunk`
- 完成时返回 `done`
- 异常时返回 `error`

### 4.3 事件示例

```text
event: meta
data: {"conversationId":"dinner-plan","plan":{"intent":"recommendation"}}

event: chunk
data: {"content":"如果你想控制预算，"}

event: chunk
data: {"content":"我建议先看附近有券的火锅店。"}

event: done
data: {"conversationId":"dinner-plan","answer":"如果你想控制预算，我建议先看附近有券的火锅店。"}
```

## 5. 清理会话接口

### 5.1 URL

```http
DELETE /ai/agent/session?conversationId=dinner-plan
```

### 5.2 说明

- `conversationId` 为空时默认清理 `default`
- 只清理当前登录用户自己的会话记忆

## 6. 手动重建知识索引

### 6.1 URL

```http
POST /ai/agent/knowledge/rebuild
```

### 6.2 说明

- 需要登录
- 会触发 `LocalLifeRagService.rebuildIndex()`
- 成功时返回当前索引是否可用
- 当远程 embedding 网关不可用时，系统会按配置自动回退到本地 embedding
- 适合以下场景：
  - 调整了静态规则文档
  - 首次部署后需要初始化向量索引

## 7. 联调建议

联调时优先看这 4 件事：

1. `plan` 是否识别对了用户意图
2. `toolTrace` 是否真的调用了应调用的工具
3. `retrievalHits` 是否只出现 `guide`
4. 最终 `answer` 是否引用了工具返回的动态事实
