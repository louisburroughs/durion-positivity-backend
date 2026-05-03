# NLQ to API Second Review

Generated from a direct code review of `pos-mcp-server` on 2026-05-03. This is a second-opinion review of [nlq-to-api-analysis.md](/home/louis-burroughs/IdeaProjects/durion-positivity-backend/pos-mcp-server/docs/nlq-to-api-analysis.md), with emphasis on code-level risks, corrections, and places where I would choose a different fix.

## Findings

### 1. Semantic selection is globally limited before role/workflow gating

This is the biggest correctness issue I found. The semantic query pulls the top K tools from the entire enabled tool catalog first, then applies role/workflow gating in Java afterward. See [ToolMetadataRepositoryImpl.java](/home/louis-burroughs/IdeaProjects/durion-positivity-backend/pos-mcp-server/src/main/java/com/positivity/mcp/internal/repository/ToolMetadataRepositoryImpl.java:45) and [ToolRegistryService.java](/home/louis-burroughs/IdeaProjects/durion-positivity-backend/pos-mcp-server/src/main/java/com/positivity/mcp/internal/service/ToolRegistryService.java:64).

Why this matters:

- If the global top 10 nearest embeddings are mostly tools outside the caller's role, the correct role-allowed tool never even enters the scored set.
- That means low recall can happen even when the role has a perfectly good matching tool.
- This is a stronger explanation for "wrong tool set" than candidate limit alone.

Recommendation:

- Push role and workflow gating into the SQL similarity query.
- If that is awkward with the current schema, query a larger semantic pool and stop pretending `topK=2` or `semanticLimit=10` is the main lever.
- Prefer "role-gated ANN search" over "global ANN search then Java filter."

### 2. The runtime tool registry does not preload all roles the controllers can emit

[McpChatController.java](/home/louis-burroughs/IdeaProjects/durion-positivity-backend/pos-mcp-server/src/main/java/com/positivity/mcp/internal/controller/McpChatController.java:60) and [McpStreamingChatController.java](/home/louis-burroughs/IdeaProjects/durion-positivity-backend/pos-mcp-server/src/main/java/com/positivity/mcp/internal/controller/McpStreamingChatController.java:60) can resolve `ROLE_TECHNICIAN` and fall back to `ROLE_USER`. But [ToolRegistryLoader.java](/home/louis-burroughs/IdeaProjects/durion-positivity-backend/pos-mcp-server/src/main/java/com/positivity/mcp/internal/service/ToolRegistryLoader.java:28) only preloads:

- `ROLE_CASHIER`
- `ROLE_SERVICE_WRITER`
- `ROLE_MANAGER`
- `ROLE_ADMIN`
- `ROLE_SUPPLIER`

Consequences:

- `ROLE_TECHNICIAN` tools will never be present in `ToolRegistry.roleToolMap`.
- `ROLE_USER` will also never be present.
- Even if the database contains valid mappings for those roles, `resolveToolsForRole()` returns an empty list.

This is a real bug, not just a tuning issue.

Recommendation:

- Stop hardcoding `KNOWN_ROLES`.
- Load all roles from `mcp_role`, or at minimum keep one shared role enumeration used by both controllers and the loader.

### 3. Agent caches ignore the configured TTL

Both managers apply `expireAfterAccess` to request-count and chat-memory caches, but not to the cached LangChain agents themselves. See [SessionAgentManager.java](/home/louis-burroughs/IdeaProjects/durion-positivity-backend/pos-mcp-server/src/main/java/com/positivity/mcp/internal/orchestration/SessionAgentManager.java:109) and [StreamingSessionAgentManager.java](/home/louis-burroughs/IdeaProjects/durion-positivity-backend/pos-mcp-server/src/main/java/com/positivity/mcp/internal/orchestration/StreamingSessionAgentManager.java:80).

Consequences:

- DB prompt edits can remain invisible until process restart or cache pressure eviction.
- DB tool-role mapping edits can remain invisible for prebuilt/full-role agents.
- Tool description improvements help selection, but built agents may still carry stale tool lists.

Recommendation:

- Apply the same TTL to `roleAgentCache`.
- Add explicit invalidation hooks when prompts or tool mappings change.

### 4. The streaming path is not just "simplified"; it is behaviorally different in ways that affect accuracy

[StreamingSessionAgentManager.java](/home/louis-burroughs/IdeaProjects/durion-positivity-backend/pos-mcp-server/src/main/java/com/positivity/mcp/internal/orchestration/StreamingSessionAgentManager.java:160) always builds one full agent per role and appends `ExaWebSearchTool`. It does not use semantic tool selection, and it does not run the simple-chat gate.

Consequences:

- Blocking and streaming can answer the same question with materially different tool surfaces.
- Latency and tool confusion are likely worse in streaming for narrow domain questions.
- Operational debugging becomes harder because endpoint choice changes model behavior, not just transport.

Recommendation:

- Share a common tool-selection service between blocking and streaming.
- Keep transport differences at the controller/service boundary, not in orchestration semantics.

### 5. Workflow-state support is still conceptual, not implemented

The existing analysis correctly flags unfinished workflow-state support, but the code is even more static than the document implies:

- [SessionAgentManager.java](/home/louis-burroughs/IdeaProjects/durion-positivity-backend/pos-mcp-server/src/main/java/com/positivity/mcp/internal/orchestration/SessionAgentManager.java:51) hardcodes `WORKFLOW_IDLE`.
- [ToolRegistryLoader.java](/home/louis-burroughs/IdeaProjects/durion-positivity-backend/pos-mcp-server/src/main/java/com/positivity/mcp/internal/service/ToolRegistryLoader.java:26) only preloads `IDLE`.
- The loader comment says non-idle tools are resolved dynamically, but there is no dynamic non-idle bean resolution path today.

Recommendation:

- Either remove workflow-state language from the design doc until it exists, or implement a real session workflow state and dynamic tool-resolution path.

## Corrections to the Existing Analysis

### 6. `@Tool` descriptions do not drive semantic selection today

One statement in the original document is inaccurate. Semantic selection is driven by `mcp_tool.description` from the database, embedded by [ToolEmbeddingInitializer.java](/home/louis-burroughs/IdeaProjects/durion-positivity-backend/pos-mcp-server/src/main/java/com/positivity/mcp/internal/config/ToolEmbeddingInitializer.java:34). The `@Tool` descriptions on the Java methods matter for LangChain4j tool calling after the tool bean has already been selected, but they are not the source of the pgvector embeddings.

The practical tuning model is:

- DB `mcp_tool.description` influences tool retrieval.
- Java `@Tool` and `@P` text influences the model's function-choice and argument extraction once the tool is already in the prompt.

That distinction matters because it changes where operators should tune behavior.

### 7. The simple-chat example in the document overstates the current risk

The document says a message like "What is the stock count for part 1234?" might slip through the simple-chat gate. With the current classifier in [SimpleChatClassifier.java](/home/louis-burroughs/IdeaProjects/durion-positivity-backend/pos-mcp-server/src/main/java/com/positivity/mcp/internal/orchestration/SimpleChatClassifier.java:55), a literal question mark already counts as a strong task signal unless the message is social/capability-oriented.

The real risk is slightly different:

- short imperative queries without punctuation
- domain terms missing from the DB-backed keyword sets
- terse messages like `stock part 1234` or `users in system`

So I agree the gate is brittle, but I would change the example and the mitigation.

## Where I Disagree With the Proposed Fixes

### 8. I would not raise `candidate-tool-limit` back to 4-5 as a default

I disagree with recommendation 11.1 as written.

Reasons:

- The current model is `llama3.1:8b`, and you already have evidence of latency and tool confusion under wider tool sets.
- The bigger recall bug is global semantic limiting before role gating, not merely `topK=2`.
- Broadening every request to 4-5 tools is a blunt fix that trades recall for more prompt bloat and weaker tool precision.

Better approach:

- Fix the gated semantic query first.
- Keep the default small for the 8B model.
- Expand candidate count only when selector confidence is low or when no gated semantic result survives.

### 9. I would not remove deterministic fallback in favor of embeddings only

I only partially agree with recommendation 11.5.

The current fallback is crude, but it provides a deterministic guardrail that is useful when embeddings are stale, missing, or semantically weak. Replacing it with "embedding-based fallback" alone removes a cheap recovery path and makes the system more opaque.

Counterproposal:

- Keep deterministic routing, but make it intentional.
- Promote it into a small domain router with explicit aliases and confidence rules.
- Use it before semantic ranking for cases that are operationally obvious, such as admin access queries, inventory SKU lookups, or order-ID lookups.

Nuance:

- The document's concern about `po` matching many unrelated tokens is overstated because the current regex uses word boundaries in [SessionAgentManager.java](/home/louis-burroughs/IdeaProjects/durion-positivity-backend/pos-mcp-server/src/main/java/com/positivity/mcp/internal/orchestration/SessionAgentManager.java:382). The real issue is semantic ambiguity, not substring leakage.

### 10. The cache redesign recommendation is premature relative to the simpler fixes

I only partially agree with recommendation 11.4.

Yes, the cache key can multiply by tool subset. But before redesigning the orchestration model around runtime tool injection, I would first fix:

- stale caches with no TTL
- role-gated ANN retrieval
- streaming/blocking parity

Why:

- The current number of roles and core tool families is still small.
- Runtime dynamic tool injection in LangChain4j is not free; it will complicate observability and testing.
- The more immediate production issue is wrong or stale tool availability, not cache cardinality.

I would instrument cache cardinality and hit rate before replacing the agent-build model.

## Additional Suggestions Not Covered Well in the First Document

### 11. Treat prompts as authorization-adjacent configuration, not just chat copy

[RolePromptResolverImpl.java](/home/louis-burroughs/IdeaProjects/durion-positivity-backend/pos-mcp-server/src/main/java/com/positivity/mcp/internal/service/RolePromptResolverImpl.java:24) falls back from role-specific prompt to `default`, and then to a very thin built-in prompt in [SystemPromptDefaults.java](/home/louis-burroughs/IdeaProjects/durion-positivity-backend/pos-mcp-server/src/main/java/com/positivity/mcp/internal/service/SystemPromptDefaults.java:9).

I agree with the original recommendation to seed role-specific prompts, but I would frame it more strongly:

- prompt content is part of policy enforcement quality
- missing role prompts should be observable
- fallback to `default` should probably emit a warning with the resolved role

### 12. Add one shared role-resolution component

The role-priority list is duplicated in both controllers. See [McpChatController.java](/home/louis-burroughs/IdeaProjects/durion-positivity-backend/pos-mcp-server/src/main/java/com/positivity/mcp/internal/controller/McpChatController.java:60) and [McpStreamingChatController.java](/home/louis-burroughs/IdeaProjects/durion-positivity-backend/pos-mcp-server/src/main/java/com/positivity/mcp/internal/controller/McpStreamingChatController.java:60).

This is small, but it is exactly the kind of duplication that drifts over time and creates role bugs. Extract one resolver and use it everywhere.

## Recommended Order of Work

1. Fix the semantic query to gate by role/workflow in SQL.
2. Remove hardcoded `KNOWN_ROLES` or at least add `ROLE_TECHNICIAN` and `ROLE_USER`.
3. Add TTL and invalidation for `roleAgentCache`.
4. Unify blocking and streaming tool-selection behavior.
5. Only then revisit `candidate-tool-limit`, scoring normalization, and broader routing refinements.
