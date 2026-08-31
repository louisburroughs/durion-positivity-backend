# MCP Role Persona Sourcing Strategy

**Status:** proposed
**Owning modules:** `pos-security-service` (role definition), `pos-mcp-server` (prompt assembly)
**Supersedes:** the hardcoded `SystemPromptSeedRunner.seedRolePersonas` / `SystemPromptDefaults` role list

## Problem

`pos-mcp-server` carries a fixed, compile-time list of roles in two places:

- `SystemPromptDefaults` — one `ROLE_*_PROMPT_NAME` constant per role, plus the ranked
  `MCP_ROLE_PRIORITY` list used by `McpRoleResolverImpl.resolvePrimaryRole` and the
  `PRELOADABLE_ROLE_IDENTIFIERS` warm-up set.
- `SystemPromptSeedRunner.seedRolePersonas` — one hand-written persona block per role.

Roles, however, are dynamic. They are seeded by Flyway in `pos-security-service`
(`R__seed_reference_security.sql`, `V3`, `V24`) *and* created at runtime through
`POST /security-service/v1/roles`. Every role created after a `pos-mcp-server` release is invisible
to the assistant until someone edits Java and redeploys.

### Consequence 1 — real roles resolve to the generic fallback

`McpRoleResolverImpl` returns the first `MCP_ROLE_PRIORITY` entry present in the caller's
authorities, else `ROLE_USER`. Seven roles that exist in the database and carry real permission
grants are absent from that list, so every one of their users is treated as an anonymous
"platform user":

| Role in `pos-security-service` | Source | In `MCP_ROLE_PRIORITY`? | Persona seeded? |
|---|---|---|---|
| `SYSTEM_ADMINISTRATOR` | `R__seed_reference_security.sql` | yes | yes |
| `ADMIN` | `R__seed_reference_security.sql` | yes | yes |
| `LOCATION_MANAGER` | `R__seed_reference_security.sql` | yes | yes |
| `ACCOUNT_MANAGER` | `R__seed_reference_security.sql` | yes | yes |
| `ACCOUNTING_ASSOCIATE` | `R__seed_reference_security.sql` | yes | yes |
| `SERVICE_ADVISOR` | `R__seed_reference_security.sql` | yes | yes |
| `DISPATCHER` | `R__seed_reference_security.sql`, `V3` | yes | yes |
| `TECHNICIAN` | `R__seed_reference_security.sql` | yes | yes |
| `CUSTOMER` | `R__seed_reference_security.sql` | yes | **no** (deliberate, Gate 1) |
| `SELF_SERVICE_CUSTOMER` | `R__seed_reference_security.sql`, `V8` | yes | **no** (deliberate, Gate 1) |
| `CONTROLLER` | `V24__seed_controller_role.sql` | **no** | no |
| `GENERAL_MANAGER` | `R__seed_reference_security.sql` | **no** | no |
| `INVENTORY_CONTROLLER` | `R__seed_reference_security.sql` | **no** | no |
| `INVENTORY_LEAD` | `R__seed_reference_security.sql` | **no** | no |
| `INVENTORY_MANAGER` | `R__seed_reference_security.sql` | **no** | no |
| `MANAGER` | `R__seed_reference_security.sql` | **no** | no |
| `SHOP_MANAGER` | `V3__seed_candidate_roles.sql` (ratified, retained by `V23`) | **no** | no |

`SHOP_MANAGER` is the sharpest case: `pos-mcp-server` already seeds a `shop-manager` *domain*
prompt (a RAG scope), so the vocabulary exists — but the `SHOP_MANAGER` *role* still resolves to
`ROLE_USER`.

### Consequence 2 — two priority entries can never assemble a ROLE layer

`CUSTOMER` and `SELF_SERVICE_CUSTOMER` are in `MCP_ROLE_PRIORITY` (so `resolvePrimaryRole` returns
them) but are intentionally not seeded as personas. `RolePromptResolverImpl.assemble` therefore
logs `missing-role-layer` and increments `mcp.prompt.fallback` on **every** request from those
callers. The metric cannot distinguish this designed state from a genuine sync failure.

### Consequence 3 — name-shape drift

`pos-security-service` stores role names unprefixed (`SHOP_MANAGER`). `pos-api-gateway`
(`SecurityGatewayConfig.java:590`) adds the `ROLE_` prefix when it emits authorities.
`pos-mcp-server` hardcodes the prefixed form in string constants that also serve as
`system_prompts.name` keys. The prefix convention is therefore restated in three modules with no
shared normalization point, and the constants' comment (`SQL-seeded security roles from
pos-security-service (R__seed_reference_security.sql)`) is already stale — it does not cover `V24`
or `V3`.

## Strategy

Persona information becomes an attribute of the role, defined where the role is defined, and
`pos-mcp-server` derives every role-keyed artifact from that data. No role name is written in
`pos-mcp-server` Java.

### D1 — Structured persona fields on the role, rendered in `pos-mcp-server`

`pos-security-service` stores three short, structured fields per role; it does **not** store
LLM prompt text.

| Column on `roles` | Meaning | Example |
|---|---|---|
| `persona_title` | Who the caller is, in the second person | `service advisor` |
| `persona_focus` | What they work on | `front-counter customer interactions, appointments, estimates, and workorders` |
| `persona_tone` | How to speak to them | `warm, customer-ready, and explicit about the next step for the customer` |

`pos-mcp-server` keeps the `rolePersona(title, focus, tone)` template and the surrounding layer
contract (BASE / ROLE / DOMAIN / TOOL_USE / WRITE_GATE). Rationale:

- Prompt wording is an MCP concern with its own review and eval loop. Moving the literal text into
  a security-service Flyway migration would make every prompt tweak a security-service release.
- Structured fields are small, validatable (length caps, no newlines), and safe to expose in an
  admin UI for a role author.
- The ROLE layer is persona-only by design — it "never grants access to data, documents, or tools
  beyond the caller's permissions". Keeping the fields descriptive rather than instructional
  preserves that property; free-form prompt text stored by a role author would not.

**Rejected alternative:** storing the rendered prompt on the role. It couples prompt engineering to
schema migrations and lets a role author inject arbitrary instructions into the assembled prompt.

### D2 — Priority becomes data, not a `List.of(...)`

Add `mcp_persona_rank SMALLINT NULL` to `roles`. `McpRoleResolverImpl` resolves the caller's
highest-ranked role from the synced snapshot, ordered by `mcp_persona_rank` ascending, with role
name ascending as a deterministic tiebreak. A `NULL` rank sorts after every ranked role but still
ahead of the `ROLE_USER` fallback — so a role created today with no rank still gets its own
persona rather than the generic one.

The existing 10-entry order is preserved by backfilling ranks 10, 20, 30, … (gaps left for
insertion). New roles are ranked by whoever creates them, or left null.

### D3 — One normalization point for the `ROLE_` prefix

Canonical storage form in `pos-security-service` stays unprefixed and upper-case (matches
`RoleRepository`'s existing contract: *"role names, already normalized to upper case with any
`ROLE_` prefix stripped"*).

`pos-mcp-server` keys `system_prompts` rows by the **authority** form (`ROLE_SHOP_MANAGER`), because
that is what `resolvePrimaryRole` produces from `Authentication`. The prefix is applied in exactly
one place — the sync service — via a single helper. All `ROLE_*_PROMPT_NAME` constants in
`SystemPromptDefaults` are deleted; `ROLE_USER` survives as the single fallback constant, since it
is an MCP-internal identity with no security-service row.

### D4 — Three-tier sync, fail-soft at every tier

1. **Startup pull.** A `RolePersonaSyncRunner` (`ApplicationRunner`, mirroring
   `SystemPromptSeedRunner` and the `{Module}EventTypeInitializer` pattern) fetches the full role
   persona list and upserts one `system_prompts` row per role. Failures are swallowed; the previous
   snapshot in the database remains serviceable.
2. **Lazy on-miss fetch.** `RolePromptResolverImpl.assemble` currently logs `missing-role-layer` and
   moves on. It instead triggers a single-role fetch, caches the result, and only falls back if that
   fetch fails. This is what makes a role created after boot work without a restart.
3. **Scheduled refresh.** A fixed-delay re-pull (default 15 minutes, configurable) picks up persona
   edits and rank changes. Optionally upgraded later to event-driven refresh on
   `SECURITY_ROLE_CREATE` / `SECURITY_ROLE_UPDATE` through `pos-event-receiver`.

Transport follows the existing `RoleDefaultPermissionsClient` precedent: `@LoadBalanced RestClient`
to `http://security-service`, asserting `security:role:view` via `X-User` / `X-Authorities` on the
internal network, every failure swallowed.

### D5 — Every role gets a usable persona, with no per-role code

When a role has no persona fields, `pos-mcp-server` derives them:

- `persona_title` → role name humanized (`INVENTORY_LEAD` → `inventory lead`)
- `persona_focus` → `role.description` when present, else `general operational questions within the caller's permissions`
- `persona_tone` → the neutral default (`helpful, careful, and neutral`)

So `POST /v1/roles {"name":"WARRANTY_CLERK","description":"Warranty claim intake and settlement"}`
yields a working, distinguishable persona with zero MCP changes. Curated fields upgrade it; they are
never a precondition.

### D6 — Warm-up set follows the data

`PRELOADABLE_ROLE_IDENTIFIERS` becomes a method on the synced snapshot rather than a static list:
the ranked roles plus `ROLE_USER`, capped at a configurable maximum (default covers the current 10
+ fallback) so an operator creating 200 roles cannot blow up agent prebuild.
`MasterAgentRegistry.preloadableRoleIdentifiers()` reads from that snapshot.

### D7 — Resolve the `CUSTOMER` / `SELF_SERVICE_CUSTOMER` contradiction

These two are in the priority list but deliberately unseeded, guaranteeing a fallback on every
external-facing request. Pick one, explicitly, and record it:

- **(a)** Give them curated personas with an explicitly narrowed focus. The ROLE layer is
  persona-only and grants nothing, so this does not widen access; it removes permanent metric noise.
- **(b)** Add an `mcp_persona_eligible BOOLEAN` flag, set it false for both, exclude them from
  resolution so they land on `ROLE_USER` by design, and stop counting that as a fallback.

Recommendation: **(a)**, on the grounds that a self-service customer benefits most from a persona
that sets expectations about what the assistant will and will not do. Gate 1's original reasoning
should be re-read before this is settled — see the open question below.

## Delivery phases

**P1 — `pos-security-service`: persona becomes part of the role contract**
- Flyway `V34__add_role_persona_metadata.sql`: `persona_title`, `persona_focus`, `persona_tone`,
  `mcp_persona_rank` on `roles`.
- Flyway `V35__backfill_role_persona_metadata.sql`: move today's nine `seedRolePersonas` blocks into
  data, and author fields for the seven currently-missing roles (`CONTROLLER`, `GENERAL_MANAGER`,
  `INVENTORY_CONTROLLER`, `INVENTORY_LEAD`, `INVENTORY_MANAGER`, `MANAGER`, `SHOP_MANAGER`) plus the
  D7 outcome. Backfill ranks preserving the existing order.
- Replace `RoleController.createRole`'s untyped `Map<String, String>` body with a validated
  `RoleCreateRequest` record carrying name, description, and optional persona fields; same for a new
  `PUT /v1/roles/{id}` used to edit persona metadata (no such endpoint exists today).
- `GET /v1/roles/personas` — a cheap projection (`name`, `description`, persona fields, rank) under
  `security:role:view`, so `pos-mcp-server` never pulls full `RoleDto` graphs with eager permissions.
- Extend `RoleDto` with the persona fields; regenerate OpenAPI and the SDKs.

**P2 — `pos-mcp-server`: stop hardcoding**
- `RolePersonaClient` + `RolePersonaSnapshot` (immutable, atomically swapped) +
  `RolePersonaSyncRunner`.
- `SystemPromptSeedRunner.seedRolePersonas` deleted; the runner seeds only `master` and the domain
  prompts.
- `SystemPromptDefaults`: delete every `ROLE_*_PROMPT_NAME` constant, `MCP_ROLE_PRIORITY`, and
  `PRELOADABLE_ROLE_IDENTIFIERS`; keep `MASTER_PROMPT_NAME`, the layer texts, and
  `promptNameForRagScope`.
- `McpRoleResolverImpl` and `MasterAgentRegistry.preloadableRoleIdentifiers()` read the snapshot.

**P3 — resilience and observability**
- Lazy on-miss fetch in `RolePromptResolverImpl`; scheduled refresh.
- Split `mcp.prompt.fallback{reason=missing-role-layer}` into `unknown-role` (sync gap — should
  alert) and `persona-ineligible` (by design — should not).
- New gauge for snapshot age and role count; log once per sync at INFO with the delta.

**P4 — reconciliation regression guard**
- An integration test that asserts every role name in `pos-security-service`'s seed migrations
  resolves to a persona, so the drift documented above cannot silently return. This is the piece
  that has been missing: nothing today fails when a role is added to SQL and not to Java.

## Open questions

1. **D1 boundary** — confirm that persona text rendering stays in `pos-mcp-server` and
   `pos-security-service` stores only structured fields. The alternative (full text on the role)
   is simpler to ship and worse to live with.
2. **D7** — re-read the Gate 1 reasoning for excluding `CUSTOMER` / `SELF_SERVICE_CUSTOMER` before
   choosing (a) or (b). The exclusion was deliberate; this document argues it is now costing a
   permanently misleading metric, not that the original call was wrong.
3. **Sync trigger** — scheduled refresh (simple, bounded staleness) versus event-driven on
   `SECURITY_ROLE_CREATE` (immediate, more moving parts). P3 assumes scheduled; the lazy on-miss
   fetch already covers the urgent case.
4. **Cross-module contract** — the persona endpoint is a REST-edge read from `pos-mcp-server` to
   `pos-security-service`, the same shape as the existing `default-permissions` call (#782). Confirm
   whether ADR-0044 requires a new scoped grant entry for it or whether it rides the existing one.
