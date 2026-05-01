# cap-637-processing.md — PRD: Configure pos-mcp-server system prompt, tool init, and static RAG preload

**Source:** GitHub issue #637 — https://github.com/louisburroughs/durion-positivity-backend/issues/637
**Date:** 2026-05-12
**Base branch:** main
**Head branch:** cap/637-mcp-runtime-config
**Module:** pos-mcp-server (only)

---

## Objective

Wire role-aware system prompt selection into chat orchestration, bootstrap static RAG documents
on startup using deterministic document IDs and content-hash dedup, seed default/role system
prompts on startup, and add Micrometer observability for preload lifecycle.

---

## Wave Overview

| Wave | Slices            | Specialist            | Status     |
| ---- | ----------------- | --------------------- | ---------- |
| 1    | A–B (persistence) | Domain Data Coder     | ⬜ PENDING |
| 1    | C (config props)  | Domain Data Coder     | ⬜ PENDING |
| 1    | D (role resolver) | Domain Data Coder     | ⬜ PENDING |
| 1    | E (prompt seed)   | Domain Data Coder     | ⬜ PENDING |
| 1    | F (metrics)       | Domain Data Coder     | ⬜ PENDING |
| 1    | G (tests)         | Backend Testing Agent | ⬜ PENDING |
| 1    | H (docs)          | Documentation Agent   | ⬜ PENDING |
| -    | Review            | Code Review Agent     | ⬜ PENDING |
| -    | PR                | Orchestrator          | ⬜ PENDING |

---

## Profile Resolution

- `PgVectorEmbeddingStore` is `@Profile("alpha")` — RAG only works on alpha.
- `DocumentIngestionService` / `DocumentIngestionJobResumeRunner` are `@Profile("!test")`.
- Preload runner and RAG preload service must be `@Profile("alpha")` to match available infrastructure.
- `SystemPromptSeedRunner` must be `@Profile("!test")` (works without PgVector).
- This is intentional and consistent with existing profile semantics.

---

## Slice A — Preload tracking persistence (Domain Data Coder)

**Files owned:**

- `pos-mcp-server/src/main/java/com/positivity/mcp/internal/entity/RagPreloadRecord.java` (NEW)
- `pos-mcp-server/src/main/java/com/positivity/mcp/internal/repository/RagPreloadRecordRepository.java` (NEW)
- `pos-mcp-server/src/main/resources/db/migration/V9__rag_preload_tracking.sql` (NEW)
- `pos-mcp-server/src/main/resources/db/h2-migration/V9__rag_preload_tracking.sql` (NEW — for H2 test compat)

**Entity fields:**

```
id              UUID (v7, PK)
document_id     VARCHAR(120) NOT NULL
content_hash    VARCHAR(64) NOT NULL
source_path     VARCHAR(255) NOT NULL
status          VARCHAR(20) NOT NULL  -- LOADED, FAILED, SKIPPED
loaded_at       TIMESTAMP NOT NULL
```

**Repository methods:**

- `findFirstByDocumentIdAndStatusOrderByLoadedAtDesc(String documentId, RagPreloadStatus status)` returning `Optional<RagPreloadRecord>`; pass `RagPreloadStatus.LOADED` so SKIPPED rows do not mask the latest successfully loaded record

**Migration V9 SQL:**

```sql
CREATE TABLE mcp_rag_preload_record (
    id           UUID         NOT NULL PRIMARY KEY,
    document_id  VARCHAR(120) NOT NULL,
    content_hash VARCHAR(64)  NOT NULL,
    source_path  VARCHAR(255) NOT NULL,
    status       VARCHAR(20)  NOT NULL,
    loaded_at    TIMESTAMP    NOT NULL
);
CREATE INDEX idx_rag_preload_document_id ON mcp_rag_preload_record (document_id, loaded_at DESC);
```

**H2 migration:** same DDL works for H2.

---

## Slice B — Static RAG preload service + runner (Domain Data Coder)

**Files owned:**

- `pos-mcp-server/src/main/java/com/positivity/mcp/service/StaticRagPreloadService.java` (NEW interface)
- `pos-mcp-server/src/main/java/com/positivity/mcp/internal/service/StaticRagPreloadServiceImpl.java` (NEW)
- `pos-mcp-server/src/main/java/com/positivity/mcp/internal/service/RagPreloadRunner.java` (NEW ApplicationRunner)
- `pos-mcp-server/src/main/resources/rag/de-bookkeeping-rag.md` (COPY from pos-accounting/docs/)
- `pos-mcp-server/src/main/resources/rag/inv-cntrl-rag.md` (COPY from pos-inventory/docs/)

**Service interface:**

```java
public interface StaticRagPreloadService {
    void preloadAll();
}
```

**Impl logic per document:**

1. Load classpath resource bytes.
2. Compute SHA-256 hex hash.
3. Fetch `RagPreloadRecordRepository.findFirstByDocumentIdOrderByLoadedAtDesc(docId)`.
4. If record present and hash matches → persist SKIPPED record, increment skip counter, return.
5. If hash differs or no record → call `DocumentIngestionService.submitDocument(content, metadata)` with `document_id` in metadata.
6. Persist RagPreloadRecord with status=LOADED.
7. On any exception → persist RagPreloadRecord with status=FAILED, log warning, increment failure counter — DO NOT rethrow.

**RagPreloadRunner:** `@Component @Profile("alpha") implements ApplicationRunner` — calls `staticRagPreloadService.preloadAll()` wrapped in try/catch (best-effort).

---

## Slice C — Static doc registry config (Domain Data Coder)

**Files owned:**

- `pos-mcp-server/src/main/java/com/positivity/mcp/internal/config/StaticRagPreloadProperties.java` (NEW)
- `pos-mcp-server/src/main/resources/application.yml` (MODIFY — add mcp.rag.preload.docs)

**ConfigurationProperties:**

```java
@ConfigurationProperties(prefix = "mcp.rag.preload")
public record StaticRagPreloadProperties(List<StaticDocEntry> docs) {
    public record StaticDocEntry(String id, String sourcePath) {}
}
```

**application.yml addition:**

```yaml
mcp:
  rag:
    preload:
      docs:
        - id: "accounting.de-bookkeeping"
          source-path: "classpath:rag/de-bookkeeping-rag.md"
        - id: "inventory.inv-cntrl"
          source-path: "classpath:rag/inv-cntrl-rag.md"
```

---

## Slice D — Role-aware prompt resolution (Domain Data Coder)

**Files owned:**

- `pos-mcp-server/src/main/java/com/positivity/mcp/service/RolePromptResolver.java` (NEW interface)
- `pos-mcp-server/src/main/java/com/positivity/mcp/internal/service/RolePromptResolverImpl.java` (NEW)
- `pos-mcp-server/src/main/java/com/positivity/mcp/internal/orchestration/SessionAgentManager.java` (MODIFY)
- `pos-mcp-server/src/main/java/com/positivity/mcp/internal/orchestration/StreamingSessionAgentManager.java` (MODIFY)

**RolePromptResolver interface:**

```java
public interface RolePromptResolver {
    @NonNull String resolvePrompt(@NonNull String role);
}
```

**Impl resolution chain:**

1. Look up system prompt by name exactly matching `role` (e.g. "ROLE_CASHIER").
2. If not found → look up by name "default".
3. If not found → return built-in fallback: `"You are a concise POS assistant for Durion Positivity. Answer general conversation directly. Do not invent business data."`

**SessionAgentManager changes:**

- Inject `RolePromptResolver rolePromptResolver` (constructor injection)
- In `buildAgent(String role)`: call `rolePromptResolver.resolvePrompt(role)` and pass as `SystemMessage` via `AiServices` builder
- In `simpleChat(...)`: call `rolePromptResolver.resolvePrompt(role)` for the system prompt instead of hardcoded constant
- Remove `SIMPLE_CHAT_SYSTEM_PROMPT` constant

**StreamingSessionAgentManager changes:**

- Inject `RolePromptResolver rolePromptResolver` (constructor injection)
- In `buildAgent(String role)`: call `rolePromptResolver.resolvePrompt(role)` and pass as `SystemMessage`

---

## Slice E — System prompt seed runner (Domain Data Coder)

**Files owned:**

- `pos-mcp-server/src/main/java/com/positivity/mcp/internal/service/SystemPromptSeedRunner.java` (NEW)

**Runner:** `@Component @Profile("!test") implements ApplicationRunner` — best-effort seed.
Seeds the following prompts if not already present (by name):

| Name              | Content                                                                                                                                                                   |
| ----------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `default`         | Built-in concise POS assistant text                                                                                                                                       |
| `ROLE_CASHIER`    | "You are a Durion Positivity ETSMS cashier assistant. Help with transactions, customer checkout, and payment processing. Be concise and accurate."                        |
| `ROLE_MANAGER`    | "You are a Durion Positivity ETSMS manager assistant. Help with store operations, staff coordination, reporting, and policy questions. Be authoritative and data-driven." |
| `ROLE_ADMIN`      | "You are a Durion Positivity ETSMS admin assistant. Help with system configuration, user management, and platform setup. Provide precise technical guidance."             |
| `ROLE_TECHNICIAN` | "You are a Durion Positivity ETSMS technician assistant. Help with workorder execution, parts management, and vehicle service operations. Be practical and step-by-step." |

Uses `SystemPromptService.create()` only when `SystemPromptRepository.existsByName(name)` returns false.
Each seed wrapped in individual try/catch — failure of one does not block others.

---

## Slice F — Metrics / observability (Domain Data Coder)

**Files owned:**

- `pos-mcp-server/src/main/java/com/positivity/mcp/internal/service/StaticRagPreloadServiceImpl.java` (MODIFY with MeterRegistry)

**Counters added via `MeterRegistry`:**

- `mcp.rag.preload.loaded` (tag: `documentId`)
- `mcp.rag.preload.skipped` (tag: `documentId`)
- `mcp.rag.preload.failed` (tag: `documentId`)

Use `Timer` for total `preloadAll()` duration: `mcp.rag.preload.duration`.

---

## Slice G — Tests (Backend Testing Agent)

**Files owned:**

- `pos-mcp-server/src/test/java/com/positivity/mcp/internal/service/RolePromptResolverImplTest.java` (NEW)
- `pos-mcp-server/src/test/java/com/positivity/mcp/internal/service/StaticRagPreloadServiceImplTest.java` (NEW)

**RolePromptResolverImplTest scenarios:**

1. Role exists by name → returns role-specific content.
2. Role not found, "default" exists → returns default content.
3. Neither exists → returns built-in fallback text.

**StaticRagPreloadServiceImplTest scenarios:**

1. Hash unchanged → SKIPPED record persisted, `DocumentIngestionService.submitDocument` NOT called.
2. Hash changed → LOADED record persisted, `submitDocument` called once.
3. No prior record → LOADED record persisted, `submitDocument` called.
4. Ingestion throws → FAILED record persisted, no rethrow (best-effort).
5. One doc fails, other succeeds → both complete, only failing doc gets FAILED record.

---

## Slice H — Documentation (Documentation Agent)

**Files owned:**

- `pos-mcp-server/README.md` (MODIFY)

**Sections to add/update:**

- Role-prompt resolution strategy (naming convention, fallback chain)
- Static RAG preload configuration (`mcp.rag.preload.docs`)
- Dedup/supersede rules (content hash + document_id)
- Profile requirements for RAG/prompt features

---

## Verification Gates

```bash
# Full module verify
cd /home/louis-burroughs/IdeaProjects/durion-positivity-backend && ./mvnw -pl pos-mcp-server -am -DskipTests=false verify 2>&1 | tail -60

# Lint
cd /home/louis-burroughs/IdeaProjects/durion && ./.github/hooks/lint-run-hook.sh --repo /home/louis-burroughs/IdeaProjects/durion-positivity-backend --module pos-mcp-server 2>&1 | tail -40
```

---

## Step Completion Log

| Step         | Status  | Evidence                                                                   |
| ------------ | ------- | -------------------------------------------------------------------------- |
| Plan created | ✅ DONE | This file                                                                  |
| Branch setup | ✅ DONE | `cap/637-mcp-runtime-config` from main                                     |
| Slice A      | ✅ DONE | RagPreloadRecord, RagPreloadRecordRepository, V9 migrations                |
| Slice B      | ✅ DONE | StaticRagPreloadServiceImpl, RagPreloadRunner, classpath rag/ docs         |
| Slice C      | ✅ DONE | StaticRagPreloadProperties, application.yml mcp.rag.preload.docs           |
| Slice D      | ✅ DONE | RolePromptResolverImpl, SessionAgentManager + StreamingSessionAgentManager |
| Slice E      | ✅ DONE | SystemPromptSeedRunner (5 prompts seeded best-effort on !test)             |
| Slice F      | ✅ DONE | mcp.rag.preload.{loaded,skipped,failed} + duration timer                   |
| Slice G      | ✅ DONE | 8 unit tests (3+5), 282 total passing                                      |
| Slice H      | ✅ DONE | README Startup Behaviour, Key Classes, RAG Preload Config                  |
| Verify       | ✅ DONE | BUILD SUCCESS — 282 tests, 0 failures                                      |
| Lint         | ✅ DONE | PASS — 0 findings, 15 files                                                |
| Review       | ✅ DONE | Verdict: PASS — 3 low findings resolved                                    |
| PR           | ✅ DONE | https://github.com/louisburroughs/durion-positivity-backend/pull/638       |
