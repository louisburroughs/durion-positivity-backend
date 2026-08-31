# MCP Role Persona Sourcing Strategy

**Status:** accepted — implemented in #1613
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

Structured fields are the **default**, not the only option. Free-form persona text is permitted, but
only through a reviewed path — see D9. A bare `POST /v1/roles` never accepts free text.

**Rejected alternative:** unreviewed free-form prompt text on the role, writable through the plain
role API. It couples prompt engineering to schema migrations and lets a single API call inject
arbitrary instructions into every assembled prompt for that role.

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

### D8 — Role provisioning moves to bulk load, with a Flyway bootstrap floor

Roles exist to do two things: bundle permission grants conveniently, and key an MCP persona.
Neither is schema. Treating role rows as migrations is what created the drift documented above —
a role added to SQL is invisible to anything that does not also get a Java edit.

`pos-security-service` already participates in the bulk-load pipeline through
`UserBulkIngestController` and `UserPersonLinkBulkIngestController`, and `DomainType` already
carries `SECURITY_USER` and `USER_PERSON_LINK`. Adding roles is the same well-trodden shape: a
`SECURITY_ROLE` `DomainType`, a `RoleLoaderRecord` in `DomainRecordFields`, a `RoleLoaderStrategy`,
a batch job in `SpringBatchBulkLoadLauncher`, and a `RoleBulkIngestController` extending
`AbstractBulkIngestController`. Persona fields ride the same record as the role definition.

Four constraints:

1. **The bootstrap floor stays in Flyway.** Creating a role through the API requires
   `security:role:create`, which requires a role that holds it, which requires an admin user.
   `R__seed_reference_security.sql` plus `SECURITY_SEED_ADMIN_PASSWORD_HASH` are that floor. Keep
   `ADMIN` and `SYSTEM_ADMINISTRATOR` and the seed admin account in SQL; the remaining roles move.

2. **Grants are the real payload.** A role with no permissions is inert, and
   `R__seed_role_permissions.sql` is ~124KB of role-to-permission grants. Either the role record
   carries its grants inline or a second `SECURITY_ROLE_PERMISSION` load follows it. Decide before
   P1; the record shape depends on it.

3. **Ordering improves.** Permissions are registered code-first by each module's
   `{Module}PermissionRegistry` at startup. Flyway runs during `pos-security-service` boot — before
   the other modules have registered — so the SQL grant seed is a snapshot that drifts from the
   registries it is supposed to mirror. A bulk load runs after the platform is up and can grant
   against what actually registered. This is a correctness gain, not just a relocation.

4. **Flyway's environment guarantee is lost and must be replaced.** Today every environment
   provably receives the same roles. Under bulk load the role set becomes a per-environment
   operator action — the same failure class as the alpha `.env` and never-registered-permissions
   defects. Mitigation is mandatory, not optional: a baseline roles file versioned in this repo,
   applied by a deploy step, with a drift check. Without it, "works on alpha" role bugs are the
   expected outcome.

### D9 — Vetting persona text

The relevant risk is not a malicious administrator. It is (a) an ordinary mistake, (b) a
compromised admin account, and (c) text that is unobjectionable in isolation but fights the layer
contract. `RolePromptResolverImpl.assemble` appends ROLE before TOOL_USE and WRITE_GATE, so a
persona reading "move fast, skip ceremony on routine updates" sits adjacent to the write-gate
contract, contradicting it, for every user of that role, silently, with no test covering it. That
is a layering problem rather than a trust problem, and trusting the author does not solve it.

Four controls, cheapest first. The first two are unconditional.

1. **Structural containment (always).** Three interpolated slots in a fixed template, never free
   text on the default path. Length caps, single line, no newlines, and rejection of
   imperative-mood control verbs. The persona can never *be* the prompt — it can only fill slots
   in one. This is the strongest control available and it costs nothing at runtime.

2. **Explicit precedence (always).** Add a line to the TOOL_USE and WRITE_GATE layer texts stating
   that those rules override any persona instruction above them. One line, no model, closes the
   ordering hole in (c).

3. **Model evaluation at write time (defense in depth).** `pos-mcp-server` already has the
   machinery (`LlmApiConfigService`, the agent stack). Shape: a submitted persona is stored
   `PENDING_REVIEW`; a classifier is asked one bounded question — does this text grant capability,
   override a rule, or instruct rather than describe? — and returns a structured verdict; only
   `APPROVED` assembles, and anything else falls back to the derived default from D5.

   Three caveats, because this control is easy to over-trust:
   - The evaluator is an LLM reading operator-supplied text. Pass the candidate as clearly
     delimited **data**, never in instruction position; demand a structured verdict; treat a
     malformed or ambiguous verdict as REJECT. Fail closed, consistent with the tool-gating
     precedent in #1606.
   - It must never block role creation. Evaluation is asynchronous; a role whose persona is not yet
     approved still works, on the derived default.
   - It is non-deterministic — the same text can flip verdicts between runs. So it is an advisory
     signal backed by a golden-set eval, with an audited `security:role:persona:override` for an
     administrator who disagrees. It is not the sole gate.

4. **Human review queue.** `pos-bulk-loader` already has a `ReviewQueueController`. If D8 lands,
   persona review lands in existing infrastructure at close to zero cost, and free-form persona
   text becomes reviewable before it ever exists in the database — a materially different risk
   profile from text typed into an admin form and applied immediately.

Net position: structured fields by default; free-form persona text permitted only through a
reviewed path (a bulk-load file under PR review, or the review queue), never through a bare API
call. Controls 1 and 2 apply to both paths.

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
- Apply D9 control 1 (field validation) and control 2 (precedence line in the TOOL_USE and
  WRITE_GATE layer texts). Both are prerequisites for accepting persona input at all.

**P1b — role provisioning as bulk load (D8), forkable from P1**
- `SECURITY_ROLE` `DomainType`, `RoleLoaderRecord`, `RoleLoaderStrategy`, batch job, and
  `RoleBulkIngestController`; persona fields carried on the record.
- Decide grants-inline versus a separate `SECURITY_ROLE_PERMISSION` load (D8 constraint 2) before
  fixing the record shape.
- Reduce the Flyway seed to the bootstrap floor (`ADMIN`, `SYSTEM_ADMINISTRATOR`, seed admin user)
  and move the remaining roles into the versioned baseline roles file.
- Add the deploy-step application of that file plus a drift check (D8 constraint 4).

P1b is independent of P2–P4: the sync mechanism does not care whether a role row arrived from
Flyway, the API, or a bulk load. Sequencing it first, though, means the backfill in P1 is authored
once, as load data, rather than written as SQL and migrated again later.

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

## Decisions taken

The open questions this document raised were settled on the issue. Recorded here so the reasoning
is not only in a comment thread.

1. **D1 boundary — structured fields, reviewed free text deferred.** Slots are structured and
   rendered in `pos-mcp-server`. The reviewed escape hatch for free-form persona text is specified
   (D9) but not built; nothing needs it yet, and building an unused write path is how it rots.

2. **D7 — (b), flag and exclude.** `CUSTOMER` and `SELF_SERVICE_CUSTOMER` have no MCP access in the
   near term, so `mcp_persona_eligible` is false for both and they are excluded from resolution by
   design. This is what lets the fallback metric separate a deliberate exclusion from a sync gap;
   under (a) the metric noise would have gone away but the roles would have carried personas nobody
   uses.

3. **Sync trigger — event-driven, on top of the pull tiers.** `security.events.v1` carries a
   `security.role.persona.changed` fact, consumed by `pos-mcp-server`. The pull tiers stay and are
   what the service is correct without: the broker closes the staleness window on a persona edit to
   a role already in the snapshot, which never triggers the on-miss fetch. `pos.mcp.kafka.enabled`
   defaults to false, so the broker is optional for this service.

4. **Cross-module contract — per ADR-0044.** `GET /v1/roles/personas` is a REST-edge read following
   the `default-permissions` precedent (#782).

5. **Rank ownership — settable through the API.** `mcp_persona_rank` is a field on the role
   create/update DTO; any caller holding the role permissions can set or change it. `PUT
   /v1/roles/{id}` is new — persona metadata previously could not be corrected without a database
   edit.

6. **D8 scope — moved now.** Role provisioning went to bulk load in the same change, so the persona
   backfill was authored once rather than written as SQL and migrated again later.

7. **D8 grant shape — two loads.** Roles in one, grants in another. Permissions are registered
   code-first at startup, so the set a role can hold is not knowable when the role is created.

8. **D9 control 3 — specified, not built.** Controls 1 (structural containment), 2 (explicit
   precedence) and 4 (review queue) ship. Model evaluation waits for a free-text path to justify it,
   consistent with decision 1.

## Delivery status

P1, P1b, P2, P3 and P4 are implemented, including the D8 move itself.

`R__seed_reference_security.sql` is reduced to the bootstrap floor: `ADMIN` and
`SYSTEM_ADMINISTRATOR` plus the seed admin account. `R__seed_role_permissions.sql` is reduced with
it — not optional, because that file ends in a guard that raises on a role it cannot resolve, so a
grant left behind for a moved role fails the migration rather than quietly doing nothing.

A fresh database still gets `DISPATCHER` and `SHOP_MANAGER` (`V3`), `SELF_SERVICE_CUSTOMER` (`V8`)
and `CONTROLLER` (`V24`) from Flyway. Those are versioned migrations, already applied everywhere;
editing them would change their checksum and fail validation. The baseline file lists them too,
which is harmless because provisioning treats an existing role as success.

Everything else is provisioned from the versioned baseline files, applied by the loaders in
dependency order (roles, then grants, then users):

- `scripts/fixtures/seed/alpha/security/roles.csv` — 15 roles with persona metadata
- `scripts/fixtures/seed/alpha/security/role-permissions.csv` — 17 roles, 1078 grants

`RoleBaselineDriftTest` is what replaces Flyway's environment guarantee: the floor and the baseline
together must still cover every expected role, the baseline must carry every grant Flyway still
applies, and Flyway must grant only to roles it creates. `RolePermissionBaselineTest` reads both
sources, so its policy invariants — least privilege, the customer-facing roles holding only the
assistant entrypoints, no capability lost against the legacy expansion — still police the complete
baseline rather than only the half that stayed in SQL.

One consequence worth knowing: `R__seed_security_operational_data.sql` assigns its 25 demo users to
roles by name, and on a fresh database those joins resolve only for the floor. That is covered
rather than broken — `security/users.csv` provisions the same 25 accounts with the same roles
through the `SECURITY_USER` loader, which runs after the role load.

## Open questions (resolved — see "Decisions taken")

1. **D1 boundary** — confirm the revised position: structured fields are the default and rendering
   stays in `pos-mcp-server`, with free-form persona text permitted only through a reviewed path
   (D9). The original either/or framing — structured fields versus full text on the role — is
   superseded; the question now is whether the reviewed escape hatch is worth building in P1 or
   deferred until someone actually needs it.
2. **D7** — re-read the Gate 1 reasoning for excluding `CUSTOMER` / `SELF_SERVICE_CUSTOMER` before
   choosing (a) or (b). The exclusion was deliberate; this document argues it is now costing a
   permanently misleading metric, not that the original call was wrong.
3. **Sync trigger** — scheduled refresh (simple, bounded staleness) versus event-driven on
   `SECURITY_ROLE_CREATE` (immediate, more moving parts). P3 assumes scheduled; the lazy on-miss
   fetch already covers the urgent case.
4. **Cross-module contract** — the persona endpoint is a REST-edge read from `pos-mcp-server` to
   `pos-security-service`, the same shape as the existing `default-permissions` call (#782). Confirm
   whether ADR-0044 requires a new scoped grant entry for it or whether it rides the existing one.

5. **Rank ownership** — backfill preserves today's ten-entry order at ranks 10, 20, 30, … Who sets
   the rank for a role created at runtime: the role author through the API, or is null-and-unranked
   the expected default in practice?

6. **D8 scope** — does role provisioning move to bulk load now (P1b), or does P1 ship against the
   existing Flyway seed with the move deferred? Moving first avoids authoring the persona backfill
   twice; deferring keeps P1 smaller and postpones the environment-drift mitigation.

7. **D8 grant shape** — do role records carry their permission grants inline, or does a separate
   `SECURITY_ROLE_PERMISSION` load follow? This determines the loader record shape and cannot be
   deferred past the start of P1b.

8. **D9 control 3** — is model evaluation of persona text worth building, given it is advisory
   rather than a gate, non-deterministic, and requires its own golden-set eval to stay honest?
   Controls 1, 2 and 4 deliver most of the protection without an LLM in the write path. A
   reasonable outcome is to specify control 3 here and not build it until a free-text path exists
   to justify it.
