---
title: Spring AI Migration — Post-Migration Review
status: complete
owner: pos-mcp-server
last_updated: 2026-08-07
---

# Spring AI Migration — Post-Migration Review

Written 2026-08-07 to close out the migration checklist (#1197). Companion to
`docs/spring-ai-big-bang-migration-checklist.md` (now `status: complete`, final decision
**PASS (de facto)**).

## What moved

The migration replaced the LangChain4j runtime with Spring AI in a single big-bang cutover:
branch work started 2026-07-18 (`30b68b628` "Moving to spring ai"), completed on the branch
2026-07-20 (`9ed7b7abb`), and merged to main 2026-07-21 via PR #987 (merge commit `661ea1c5d`).

- **Chat runtime** — LangChain4j `AiServices`/chat agents → Spring AI `ChatClient` for both the
  blocking and streaming paths (PR #987).
- **Tool calling** — LangChain4j `ToolProvider`/`ToolExecutor` → Spring AI `ToolCallback`, for
  both the 16 facade tools and the dynamically discovered OpenAPI tools, keeping the per-request
  permission-gated resolution model (PR #987; verified end-to-end later under #779, closed via
  the PR #1102 / PR #1120 hardening wave).
- **RAG + embeddings** — retrieval and ingestion re-wired onto the Spring AI vector-store and
  embedding abstractions over the existing pgvector schema (PR #987; schema mismatch fixed in
  PR #1109).
- **Config namespace** — legacy `langchain4j.*`-era keys → `spring.ai.*` plus module-local
  `mcp.*` keys (`30b68b628`, `9ed7b7abb`); wording residue swept in `301777467`.
- **Tests** — the module suite was migrated with the runtime and was green pre-merge
  (455 tests, 0 failures, 2026-07-20); startup-wiring and callback-test fallout was resolved the
  day of the merge (`23cb6d43a`), alongside PR #987 review findings (`dbe9f9f3f`).

No LangChain4j imports, dependencies, or config keys remain (re-verified 2026-08-07:
`rg -n "langchain4j" pos-mcp-server --glob '!**/target/**'` matches only historical prose under
`docs/`).

## What broke and was fixed

The cutover was fix-forward; no rollback trigger fired. In order of discovery:

- **Startup wiring + callback tests** (`23cb6d43a`, 2026-07-21) — bean wiring and tool-callback
  test fallout found immediately after the merge.
- **#1109 — chat HTTP 500 + vector-store schema mismatch** (`d21ab259f`, 2026-07-25) —
  tool-calling chat options broke the blocking path, and the Spring AI vector-store expectations
  did not match the existing pgvector schema.
- **#1111 — blank chat replies** (`65fe6e4f4`, 2026-07-25; plus `659127bb8`/`4fe1879a9`,
  2026-07-24) — an invalid chat model id produced empty responses; the resolved model was not
  being passed into the tool-calling chat options. Fixed with valid chat + fallback models.
- **#1101 — missing MCP tool annotations** (`29e535644`, 2026-07-24) — discovered tools lacked
  readOnly/destructive/idempotent/openWorld annotations after the ToolCallback move.
- **Trust-header leak vector** (`a576b85df`, 2026-07-24) — the discovered-op proxy did not strip
  inbound gateway trust headers; fixed as part of the PR #1102 hardening wave.
- **#1121 — 125 discovered tools 404 through the gateway** (PR #1122, `07bdbc76e`, 2026-07-26) —
  discovery mis-attributed the service prefix for operations aggregated via swagger-config, so a
  quarter of the discovered surface 404ed; orphaned tools are now pruned during discovery, and a
  gateway-routing IT + route check guards the invariant (PR #1120, `889db983d`).
- **#1115 — AUTHENTICATED facade over-exposure** (`baaca0ab0`, 2026-07-28, PR #1132) — facade
  tools whose permission set included the AUTHENTICATED union grant (notably `AdminFacadeTool`)
  were offered to *any* authenticated user at the selection layer; mixed-sensitivity facades are
  now gated off the AUTHENTICATED floor.

## Lessons learned

1. **A green test suite is not a live smoke test.** 455 green tests did not catch #1109 or
   #1111 — both were configuration/runtime-integration failures only visible against the real
   model backend and real pgvector schema. Any future runtime swap needs a scripted post-deploy
   smoke pass, executed and recorded at T0, not reconstructed later.
2. **Close the checklist during the window, not three weeks after.** The cutover ceremony items
   (freeze, artifact hashes, dashboard snapshots) were skipped or unrecorded, so this closure had
   to mark them "not reconstructible" (#1197). The record survived only because git history and
   the issue tracker were disciplined.
3. **Behavior-preserving migrations still shift security surfaces.** The ToolCallback move
   silently changed exposure semantics: tool annotations disappeared (#1101), trust headers
   leaked through the proxy (`a576b85df`), and the AUTHENTICATED sentinel widened facade
   exposure (#1115). Permission-boundary tests (the IT of `d501b6ea2` / #1114) are the right
   durable countermeasure and now exist.
4. **Validate the discovered tool surface end-to-end, not just at discovery time.** #1121 showed
   125 tools could register successfully yet be uncallable; the gateway-routing IT (PR #1120)
   now asserts route validity for every discovered op.
5. **Big-bang was the right call at this scale.** One module, one owner, no mixed-runtime
   period: the whole migration went from branch start to stable in seven days
   (2026-07-18 → 2026-07-25), and follow-up hardening rode normal issue flow (#645, #778–#785,
   #1101 via PR #1102) instead of a long dual-runtime tail.

## References

PR #987 (migration), PR #1102 / PR #1114 / PR #1120 / PR #1122 / PR #1132 (hardening waves);
issues #1101, #1109, #1111, #1115, #1121, #1197; superseded delivery plan archived at
`docs/archive/spring-ai-issues-delivery-plan.md` (`4a182ac59`).
