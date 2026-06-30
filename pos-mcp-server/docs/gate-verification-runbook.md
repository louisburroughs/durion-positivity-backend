# Gate Verification Runbook — pos-mcp-server NL interface

How to build and verify each phase gate **locally**. Two tiers:

- **A. Offline checks** — build + unit/structural tests. No Ollama, no Postgres. These are what's been verified so far and run in CI.
- **B. Live checks** — baseline metrics, fail-closed, persona behavior. Need a model backend + pgvector/Postgres. Deferred batch (path 2).

All commands run from the **worktree root** unless noted:
```
cd ~/IdeaProjects/durion-positivity-backend/.worktrees/nl-interface
```
Branch: `feat/nl-interface-gates`. Toolchain: JDK 25 (`java -version` → 25.x), Maven wrapper `./mvnw`.

---

## 0. One-time build (required before any test run)

A clean reactor build must run once so upstream modules are installed and annotation processing (Lombok) runs. **Do not** use bare `-am compile` from a clean worktree — use `install`:

```bash
./mvnw clean install -am -pl pos-mcp-server -DskipTests
```
Expected: `BUILD SUCCESS`. (A bare `compile` on a never-built worktree fails on Lombok getters — that is the build invocation, not a code bug.)

After this, incremental offline runs work:
```bash
./mvnw -o -pl pos-mcp-server <goal>
```

---

## A. Offline gate checks (no backend)

### Run every implemented gate's tests in one shot
```bash
./mvnw -o -pl pos-mcp-server test \
  -Dtest='Eval*,RolePromptAssemblyTest,MasterAgentRegistryLoaderTest,PermissionGatingInvariantTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: `Tests run: 17, Failures: 0, Errors: 0, Skipped: 1` → `BUILD SUCCESS`.
(The 1 skipped is the Gate 0 fixture-minimum exit gate — see Gate 0 below.)

### Format check (drift guard — must be clean before commit)
```bash
./mvnw -o -pl pos-mcp-server spotless:check     # or spotless:apply to fix
```

### Per-gate breakdown

| Gate | Command | Expected |
|---|---|---|
| **0** Telemetry + eval harness | `./mvnw -o -pl pos-mcp-server test -Dtest='Eval*' -Dsurefire.failIfNoSpecifiedTests=false` | 9 run, 1 skipped, 0 failed |
| **1** Role-first layered prompts | `./mvnw -o -pl pos-mcp-server test -Dtest='RolePromptAssemblyTest' -Dsurefire.failIfNoSpecifiedTests=false` | 5 run, 0 failed |
| **2A** Shared path + preload | (covered by `RolePromptAssemblyTest#preloadCoverage`) | included above |
| **2B** Permission-gating cleanup | `./mvnw -o -pl pos-mcp-server test -Dtest='PermissionGatingInvariantTest,MasterAgentRegistryLoaderTest' -Dsurefire.failIfNoSpecifiedTests=false` | 3 run, 0 failed |

### Gate 0 config-hygiene assertions (no test — verify by inspection)
```bash
# tuning disabled by default
grep -A2 'tuning:' pos-mcp-server/src/main/resources/application.yml      # enabled: ${MCP_TUNING_ENABLED:false}
# single documented default model (yml and README agree)
grep 'OLLAMA_CHAT_MODEL' pos-mcp-server/src/main/resources/application.yml
grep 'chat-model.model-name' pos-mcp-server/README.md
```

### Gate 0 fixture-minimum exit gate (currently @Disabled)
Counts are seed-only (4/4/4 vs required 100/50/30). To check current counts:
```bash
for d in tool-selection rag-retrieval write-safety; do
  echo -n "$d: "
  python3 -c "import json,glob,sys;print(sum(len(json.load(open(f))['fixtures']) for f in glob.glob('pos-mcp-server/src/test/resources/eval/'+sys.argv[1]+'/*.json')))" "$d"
done
# current: tool-selection: 4 / rag-retrieval: 4 / write-safety: 4
```
When suites reach 100/50/30, remove the `@Disabled` on `EvalFixtureValidationTest#minimumFixtureCountsMet` and it must pass.

---

## B. Live gate checks (need backend) — deferred batch

These cover the HOLD items on Gates 0–2B: baseline metrics, RAG recall, fail-closed permission behavior, persona differences, and the V19/V17 migration.

### B.1 Backend env vars
```bash
export OLLAMA_CHAT_BASE_URL=https://ollama.com
export OLLAMA_CHAT_MODEL=qwen3.5:cloud
export OLLAMA_EMBEDDING_BASE_URL=http://ollama:11434     # internal host — needs tunnel/DNS
export OLLAMA_EMBEDDING_MODEL=nomic-embed-text
export OLLAMA_API_KEY=********                            # DO NOT commit; rotate after use
export MCP_DB_HOST=... MCP_DB_PORT=... MCP_DB_NAME=... MCP_DB_USER=... MCP_DB_PASSWORD=...  # via tunnel
```
> **Security:** keep the API key in the shell/secret store only. Never commit it; rotate if it has been pasted anywhere.

### B.2 Chat backend reachability smoke test (verified working)
```bash
curl -sS --max-time 60 https://ollama.com/api/chat \
  -H "Authorization: Bearer ${OLLAMA_API_KEY}" -H 'Content-Type: application/json' \
  -d '{"model":"qwen3.5:cloud","stream":false,"messages":[{"role":"user","content":"Reply with: pong"}]}'
```
Expect HTTP 200 + `"content":"pong"`. (Observed ~28s — qwen3.5:cloud is a reasoning model; too slow for the Gate 4 T1 router, by design use a small model there.)

### B.3 Run the service against the live stack
```bash
./mvnw -o -pl pos-mcp-server spring-boot:run -Dspring-boot.run.profiles=alpha
```
Requires Postgres+pgvector reachable (tunnel) and the embedding host resolvable. On boot, Flyway applies migrations including **V19** (legacy-table rename).

> **Before first boot against a shared DB:** snapshot it. V19/V17 are reversible (`RENAME TO *_deprecated`, `IF EXISTS`) but take the snapshot anyway per the gate rollback policy.

### B.4 Baseline capture (Gate 0 HOLD)
The metric math (`EvalMetrics`) and fixtures exist; the live driver that feeds rankings from the running selector is the remaining wiring. Until then, capture manually against the running service and fill `src/test/resources/eval/baseline.json`:
- tool-selection hit@5 / MRR — run each `tool-selection` fixture through `/v1/mcp/chat` selection, score with `EvalMetrics`.
- RAG recall@k — run each `rag-retrieval` query, score retrieved doc ids.
- latency p50/p95 by tier.

### B.5 Fail-closed spot check (Gate 2B HOLD)
With the service up, confirm a caller lacking a tool's permission never receives it, and `mcp_role`/`mcp_tool_role` are gone:
```bash
# tables renamed (no live query should reference the originals)
psql "$MCP_DB_URL" -c "\dt mcp_role*"        # expect only *_deprecated
psql "$MCP_DB_URL" -c "\dt mcp_tool_role*"   # expect only *_deprecated
```

---

## C. Full module test suite (optional, broader)
```bash
./mvnw -o -pl pos-mcp-server test
```
Note: some pre-existing tests may require the `alpha` profile / backend; the gate tests in section A are the curated offline set.

---

## Quick reference

| I want to… | Command |
|---|---|
| Build once | `./mvnw clean install -am -pl pos-mcp-server -DskipTests` |
| Verify all implemented gates (offline) | `./mvnw -o -pl pos-mcp-server test -Dtest='Eval*,RolePromptAssemblyTest,MasterAgentRegistryLoaderTest,PermissionGatingInvariantTest' -Dsurefire.failIfNoSpecifiedTests=false` |
| Check formatting | `./mvnw -o -pl pos-mcp-server spotless:check` |
| Smoke-test chat backend | curl in B.2 |
| Run service (live) | `./mvnw -o -pl pos-mcp-server spring-boot:run -Dspring-boot.run.profiles=alpha` |

Gate status + sign-offs live in [`implementation_checklist.md`](implementation_checklist.md). Gate definitions in [`implementation_phase_gates.md`](implementation_phase_gates.md).
