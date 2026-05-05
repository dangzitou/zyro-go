# Agent 故障复盘与解决办法

这份文档沉淀的是本轮升级过程中真正遇到的问题、定位方法、根因和修复方案，既适合后续排障，也适合面试时讲“你是怎么定位和修好问题的”。

## 1. Provider 控制台几乎看不到 LLM 调用

### 现象
- provider 控制台调用数接近 0
- 本地接口能返回部分推荐结果
- 容易误以为系统根本没接上大模型

### 根因
这是两层问题叠加：
1. 推荐类问题先被后端 `prefetchToolContext(...)` 查到了业务结果，直接短路返回，没有进入主 chat model。
2. 真正需要走 planner / chat 的链路，最初又存在网关协议兼容问题，导致调用不通。

### 怎么定位
- 看 `toolTrace`
- 看 `planner` 是否真正调起
- 看 `chat` 是否真正走了模型
- 不只盯着 provider 控制台

### 解决办法
- 推荐类链路保留预取，但把预取到的事实注入模型上下文，让模型组织最终回答
- 同时修正 planner 和主 chat 的模型调用链

## 2. planner / chat 报 404

### 现象
- `Planning failed, fallback to heuristic plan`
- `AI agent chat failed`
- 日志里出现 `HTTP 404`

### 根因
- `spring.ai.openai.base-url` 还指向旧 provider
- 新 provider 的真实路径是 `/v1/...`
- base-url 配置方式和 Spring AI 自动拼接策略不匹配

### 怎么定位
直接用最小 HTTP 请求探测：
- `GET /v1/models`
- `POST /v1/chat/completions`

### 解决办法
- 把 `spring.ai.openai.base-url` 配成 provider 根路径
- 让 Spring AI 自己拼 `/v1/...`
- 替换成正确可用的 API Key 和模型名

## 3. 404 消失后，又报 `text/event-stream` 解码失败

### 现象
- `Could not extract response`
- `no suitable HttpMessageConverter found for content type [text/event-stream]`

### 根因
这个 provider 的同步接口也返回 `text/event-stream`，而 Spring AI 默认的同步 `call().content()` / `call().entity()` 路径期待普通 JSON。

### 错误但诱人的做法
- 为某一个 provider 单独写一套专属网关适配服务

### 最终解法
继续走 Spring AI 统一方案：
- planner 改成内部使用 `stream().content()` 聚合
- 同步 chat 改成内部使用 `stream().content()` 聚合

这样可以：
- 保留 Spring AI 的 memory、advisor、tool calling 体系
- 不做 provider 专属分支
- 保持后续 provider 热插拔能力

## 4. 复杂推荐类问题被误路由到 `general` 或 `factual_lookup`

### 典型输入
- `体育西? 便宜点 吃饭`
- `广州正佳!!!预算80，两个人，约会，安静一点，别太贵`

### 根因
- 旧的本地规则偏粗糙
- planner 输出一旦缺字段，就容易被兜底逻辑强行拉偏
- 推荐类短句缺少稳定的 recommendation cues

### 解决办法
- planner 改为模型优先
- fallback 只在模型失败时生效
- recommendation cues 扩充到：
  - 附近 / 周边 / 吃什么 / 便宜点 / 有没有 / 适合 / 约会 / 聚餐
- `sanitize()` 只补空，不强行改写已合理结果

## 5. 地点解析过于激进，噪声词被识别成地点

### 典型输入
- `给我找个附近吃饭的地方`
- `为什么这个项目不把实时门店信息直接放进RAG里`

### 根因
旧 parser 过于相信短语截断结果，只要像中文词组就可能当地点。

### 解决办法
重写 `LocationTextParser`，改为保守策略：
- 优先识别明确 `附近 / 周边 / 一带`
- 再识别城市
- 再截取地点短语
- 对噪声词做 blacklist 兜底
- 宁可缺失 `locationHint`，也不制造脏 `locationHint`

## 6. 否定偏好理解反了

### 典型输入
- `广州正佳附近不要火锅，想吃点清淡的，两个人，80以内`

### 旧问题
- `火锅` 会被识别成正向 `category`

### 解决办法
在 `AgentExecutionPlan` 中新增：
- `excludedCategories`
- `negativePreferences`

并在 planner / fallback / 推荐层保持一致。

最终效果：
- `火锅` 进入排除类别
- `清淡` 进入正向偏好或子类目
- 推荐层显式过滤排除类别

## 7. 查询词很脏，召回不稳定

### 典型输入
- `的30以内的快餐`
- `找个 餐厅`

### 根因
旧逻辑主要靠删词得到 `keyword`，字符串残片会直接传给推荐层。

### 解决办法
新增 `ShopRecommendationQuery`，让 Agent 层改成结构化驱动：
- city
- locationHint
- category / subcategory
- qualityPreference
- budget
- excludedCategories
- negativePreferences

再由推荐层用这些结构化字段做过滤和加权，而不是继续拼脏字符串。

## 8. 缺城市时结果不稳定

### 典型输入
- `正佳附近好吃不贵的餐厅`
- `SM附近来点学生党喝得起的咖啡或者奶茶`

### 策略
默认继承当前登录用户城市或定位上下文。

### 原因
- 这更符合本地生活真实使用习惯
- 用户大多在查“自己当前所在城市附近”
- 比直接拒答更利于成交和导购

### 风险
- 如果用户实际想查异地，可能会和当前城市不一致

### 当前处理
- 工程上保留这个兜底
- 回答中尽量体现“当前按你当前城市或定位附近查询”

## 9. 推荐为空，但不能乱推

### 问题
企业系统里最怕“没数据时硬编结果”。

### 当前策略
- 附近问题无命中时，明确告知用户没有满足条件的候选
- 提示用户放宽预算、换品类、调整位置
- 不回退到全局无关结果

### 价值
这比答错一个外地店更符合企业级可信度要求。

## 10. 改完一处，别的提示词容易回退

### 根因
语义理解本身就是高度耦合的：意图、地点、预算、品类、否定偏好彼此影响。

### 解决办法
固化回归集，至少覆盖：
- `广州五山附近的人均30以内的快餐店推荐`
- `体育西? 便宜点 吃饭`
- `广州正佳附近不要火锅，想吃点清淡的，两个人，80以内`
- `广州正佳!!!预算80，两个人，约会，安静一点，别太贵`
- `SM附近来点学生党喝得起的咖啡或者奶茶`
- `给我找个附近吃饭的地方`

并验证：
- 不再 `Agent 调用失败`
- 不再出现明显脏 `locationHint/keyword`
- 推荐类问题大多落到 `recommendation + recommend_shops`

## 11. 技术取舍说明

### 为什么继续选 Spring AI
- 现有项目已经建立在 Spring Boot + Spring AI 体系之上
- memory、advisor、tool calling、OpenAI-compatible provider 都已打通
- 对企业项目来说，一致性和维护成本通常比多接一个框架更重要

### 为什么不做 provider 专属适配
- 单独为某个 provider 写网关适配，会破坏热插拔能力
- 后面换别的 OpenAI-compatible provider 又要重写
- 最终选择继续沿用 Spring AI 统一抽象，只调整消费方式

### 为什么 recommendation 默认不依赖知识检索
- 推荐类问题的核心是位置、预算、品类、优惠等动态约束
- 主答案应来自工具和数据库
- 知识层更适合解释规则和边界，不应主导动态推荐

### 为什么缺城市时默认继承当前用户城市
- 更符合本地生活真实使用习惯
- 用户大多在查“自己当前所在城市附近”
- 比直接拒答更利于成交和导购

### 为什么无命中时优先保守回答
- 企业系统里，可信度比“看起来答得完整”更重要
- 没有可靠数据时，宁可明确告诉用户当前没命中，也不乱推

## 12. 测试过程怎么讲

### 我做了哪几层验证
1. 单元测试：验证 planner、地点解析、推荐过滤规则
2. 真实接口联调：跑 `/ai/agent/chat` 和 `/ai/agent/chat/stream`
3. 极端提示词轰炸：专门测口语、省略、噪声、否定偏好和边界场景

### 我重点验证哪些点
1. 不再出现 `Agent 调用失败`
2. 推荐类短句不再误路由
3. `locationHint` 和 `keyword` 不再出现明显脏值
4. 否定偏好、预算、人数、细类目能落到结构化 plan

### 我固化了哪些极端提示词
- `广州五山附近的人均30以内的快餐店推荐`
- `体育西? 便宜点 吃饭`
- `广州正佳附近不要火锅，想吃点清淡的，两个人，80以内`
- `广州正佳!!!预算80，两个人，约会，安静一点，别太贵`
- `SM附近来点学生党喝得起的咖啡或者奶茶`
- `给我找个附近吃饭的地方`

### 面试时怎么说验证结果
推荐回答：
> 我不会只说“测过了”，而是会说明：这轮改完之后，编译和针对性单测都通过；服务起起来后又用一组正常和极端提示词做真实接口轰炸，结果里不再出现 `Agent 调用失败`，推荐类问题也能稳定落到 `recommendation + recommend_shops`，同时 planner 返回的结构化字段比之前干净很多。

## 13. 这轮最重要的经验

### 技术经验
- 不要把 provider 问题误判成模型问题
- 不要为单一 provider 写专属分支，优先保持统一调用抽象
- 不要让动态事实和静态知识混层
- 不要让推荐类答案只靠大模型自由发挥

### 面试经验
讲这轮升级时，不要只说“我接通了 GPT-5.4-mini”，更要讲：
1. 你遇到了什么真实问题
2. 你怎么定位
3. 你为什么没选更差的方案
4. 你如何验证修复真的生效

这比单纯说“我会用 Spring AI / RAG / Tool Calling”更有说服力。
## 14. Context engineering v2 related troubleshooting

### Symptom: long conversations start to feel "forgetful"

Check:

- whether `context_compression(...)` trace shows `summaryUpdated=true`
- whether `summaryHits` and `longTermMemoryHits` are still non-zero
- whether `droppedContextKinds` is repeatedly removing useful summary slices

Likely causes:

- hard token budget too small for the current prompt shape
- planner outputs too much dynamic context in one round
- memory facts are expiring faster than expected

### Symptom: old recommendation payloads keep polluting later turns

Check:

- whether `microCompactTriggered=true`
- whether `microCompactedItems` contains folded recommendation/history markers

If not:

- verify `hmdp.ai.context-compression.micro-compact-enabled=true`
- inspect whether the oversized historical text still matches the current
  micro-compact heuristics

### Symptom: a stored preference is clearly wrong for the current turn

Check:

- whether the old fact should now be expired
- whether it conflicts with this turn's `excludedCategories` or
  `negativePreferences`
- whether the trace still shows that memory fact in `memoryKinds`

The intended behavior is:

- current-turn hard constraints override stale or conflicting history
- long-term memory should help continuity, not fight the current request

Reference:

- [CONTEXT_ENGINEERING_V2.md](./CONTEXT_ENGINEERING_V2.md)
