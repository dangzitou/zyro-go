# Context Engineering v2

## Summary

This round upgrades the agent context pipeline from a basic
`recent turns + rolling summary + long-term memory + token estimate`
implementation into a more production-ready context engineering stack.

The goal is not to copy Claude Code mechanically. The goal is to absorb the
parts that fit a local-life recommendation agent:

- `MicroCompact`: zero-API cleanup for stale low-value history
- `Memory governance`: classify, expire, and de-conflict long-term memory
- `Budget-driven assembly`: build prompt context by priority instead of by
  simple append-and-trim

## What Changed

### 1. MicroCompact was added before summary compaction

`ContextCompressionService` now runs a zero-cost compression pass before
deciding whether to summarize old turns.

It mainly targets:

- old prefetched recommendation payloads
- long `recommendation#N` / `shop#N` result blocks
- repeated historical retrieval snippets
- verbose assistant explanations that can be regenerated later

It does not touch:

- recent raw user/assistant turns
- current-round plan facts
- current hard constraints such as budget, nearby, exclusions, party size

Instead of deleting aggressively, it replaces stale low-value content with
small placeholders so the conversation remains structurally understandable.

### 2. Long-term memory is now governed instead of blindly appended

`LongTermMemoryFact` now carries:

- `memoryClass`
- `expiresAt`
- `stale`

The current memory classes are:

- `user_profile`
- `stable_preference`
- `hard_avoidance`

`MemoryExtractionService` now:

- only extracts cross-turn useful facts
- classifies memory by type
- assigns expiry windows for weaker preferences
- filters expired facts before use
- drops facts that conflict with the current execution plan

Examples:

- a historical food preference that clashes with this turn's excluded category
  will not be injected
- expired weak preference facts will quietly age out
- city/profile facts stay more stable than lightweight scene preferences

### 3. Summary growth is now bounded

`ConversationSummary` still keeps the same high-level structure, but the merge
policy is stricter now:

- `toolOutcomes` keep conclusions, not candidate lists
- `ragTakeaways` keep reusable knowledge, not long retrieval text
- `resolvedFacts` are merged by fact-key instead of duplicated forever
- `openThreads` can naturally shrink once they are effectively resolved

This prevents the common anti-pattern where "summary" becomes a second bloated
transcript.

### 4. Prompt context is assembled by fixed priority

The assembled prompt now follows a stable order:

1. system prompt
2. current user message
3. current execution plan
4. current prefetched location and business facts
5. recent raw turns
6. conversation summary slices
7. long-term memory hits
8. retrieval snippets

If token pressure remains too high, context is dropped by priority instead of
randomly shrinking everything:

- low-priority summary slices go first
- then lower-value memory hits
- recent raw turns are the last thing to shrink

This keeps user hard constraints much more stable under long conversations.

### 5. System prompt assembly now has internal block boundaries

`AiAgentServiceImpl.buildSystemPrompt(...)` no longer behaves as one flat
builder internally.

It now assembles prompt blocks such as:

- static rules
- planner block
- prefetched facts block
- compressed context block
- knowledge snippets block

The external Spring AI contract is unchanged: the model still receives a single
system string. But the internal shape is now ready for future prompt caching or
deeper prompt analysis.

### 6. Trace became much more useful

Context trace now exposes:

- `microCompactTriggered`
- `microCompactedItems`
- `summaryKinds`
- `memoryKinds`
- `droppedContextKinds`
- `tokensBefore`
- `tokensAfter`
- `summaryUpdated`

That means a bad answer can now be debugged as:

- planner issue
- location issue
- stale memory issue
- summary over-compression issue
- low-priority context dropped under budget pressure

instead of a vague "the model forgot".

## Validation

This round was verified with:

- `mvn -q -DskipTests compile`
- `mvn -q "-Dtest=ContextCompressionServiceTest,MemoryExtractionServiceTest,AiAgentServiceImplTest" test`

The targeted tests cover:

- micro-compaction of stale historical recommendation payloads
- long-term memory extraction and expiry/conflict filtering
- browser geolocation + reverse geocoding injection path
- long multi-turn conversations that trigger summary and memory reuse
- trace visibility for token budgeting and context dropping

Note:

- the test suite may print Spring AI `streaming is not supported` logs when the
  test double uses a non-streaming `ChatModel`
- those logs are expected test-noise, not runtime failures

## Why This Matters

This upgrade is important because context bugs in enterprise agents are rarely
"the model is dumb" bugs. They are usually:

- stale tool payloads crowding out fresh constraints
- memory drift
- accidental prompt bloat
- summary becoming another transcript
- hidden context trimming that removes the wrong thing

The v2 design makes those failure modes explicit and controllable.
