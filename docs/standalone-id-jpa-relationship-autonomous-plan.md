# Standalone Id to JPA Relationship Migration - Autonomous Agent Plan

## Objective

Convert approved same-module scalar FK-style fields (for example `orderId`, `invoiceId`) into explicit JPA relationships across **Core Entries** only, while preserving API contracts and module boundaries.

This plan is written so an autonomous coding agent can execute it end-to-end with minimal human intervention.

## Source Inputs

- Candidate inventory: `docs/entity-fk-candidates.md`
- Prior migration history and guardrails: `docs/standalone-id-jpa-relationship-plan.md`
- Architecture constraints: `pos-archunit` tests and module `ArchitectureTest` classes

## Scope

- In scope: Core entities from `docs/entity-fk-candidates.md`
- Out of scope: Audit and Event entries from `docs/entity-fk-candidates.md`
- Out of scope: Cross-service JPA relationships (must remain scalar/reference-only)

## Non-Negotiable Constraints

1. Never introduce JPA relationships across module/service boundaries.
2. Keep existing DB column names stable via `@JoinColumn(name = "...")`.
3. Prefer `@ManyToOne`; use `@OneToOne` only when uniqueness is enforced.
4. Keep external API request/response scalar ID shape unless explicitly approved to change.
5. Keep all internal implementation code under `com.positivity.{domain}.internal...` packages.
6. Preserve/add `@NonNull` (`org.jspecify.annotations.NonNull`) on non-null service/repository method params and non-Optional returns.
7. Do not modify Audit/Event entities in this phase.

## Deliverables

1. Entity mappings migrated for all approved same-module Core candidates.
2. Repositories/services/controllers/tests updated for relationship navigation.
3. Architecture tests and module tests passing per migrated module.
4. Updated migration tracker section in this file after each completed batch.

## Autonomous Execution Model

The agent must execute the following loop until no approved candidates remain.

### Step 0 - Build Work Queue

1. Parse `docs/entity-fk-candidates.md` Core section.
2. For each scalar `*Id` field, classify as:
   - `CONVERT_NOW`: same module, target entity exists, clear ownership.
   - `KEEP_SCALAR`: cross-service/external reference.
   - `DEFER`: ambiguous target, circular lifecycle risk, or unclear ownership.
3. Write/update queue artifact: `docs/standalone-id-jpa-relationship-work-queue.md` with statuses.

### Step 1 - Select Next Batch

Select one module at a time with this priority:

1. Highest count of `CONVERT_NOW` candidates.
2. Lowest graph complexity (fewer cyclic references).
3. Existing test coverage available.

Hard cap per PR/batch:

- Max 5 entities or 12 FK field conversions, whichever comes first.

### Step 2 - Apply Entity Conversion Recipe

For each selected candidate:

1. Add relationship field (or keep existing relationship as source of truth).
2. Map with `@JoinColumn(name = "<existing_fk_column>")`.
3. If legacy scalar still needed short-term:
   - Keep scalar as derived/read-only compatibility accessor.
   - Mark for removal in cleanup phase.
4. Update `equals/hashCode/toString` safety to avoid lazy graph traversal.
5. Ensure nullability consistency (`optional = ...`, DB constraints, annotations).

### Step 3 - Refactor Persistence/Business Access Paths

1. Update repository method names/JPQL to relationship navigation:
   - Example: `findByInvoiceId(...)` -> `findByInvoice_Id(...)`
2. Replace service-layer scalar-ID joins/lookups with relationship access.
3. Keep DTO/API scalar IDs by mapping `entity.getRelated().getId()` in responses.
4. Update create/update flows to assign managed parent entities.

### Step 4 - Update Tests in Same Batch

Required updates:

1. Unit tests for service logic and mapping behavior.
2. Repository tests for derived query names and joins.
3. Integration tests for persistence lifecycle and FK-safe cleanup ordering.
4. Contract/API behavior tests where scalar payload compatibility is expected.

### Step 5 - Validation Gate (Must Pass)

Run per module:

```bash
./mvnw -pl <module> -DskipTests compile
./mvnw -pl <module> -Dtest=<focused_test_list> test
./mvnw -pl <module> test
```

If architecture tests are in module:

```bash
./mvnw -pl <module> -Dtest=ArchitectureTest test
```

If any command fails:

1. Fix regressions in the same batch.
2. Re-run full module tests.
3. If unresolved after 2 repair attempts, mark candidate `DEFER` with reason and continue to next candidate.

### Step 6 - Batch Closeout

After a passing batch:

1. Update `Execution Log` section in this file with:
   - Date
   - Module
   - Entities/fields converted
   - Test commands executed
   - Result
2. Update queue statuses:
   - `DONE`, `DEFER`, `KEEP_SCALAR`
3. Move to next module.

## Decision Table (Autonomous)

| Condition | Action |
|---|---|
| Same module and clear target entity | `CONVERT_NOW` |
| Cross-module or external ownership | `KEEP_SCALAR` |
| Self-reference/cycle causing persistence teardown instability | `DEFER` |
| Target entity missing or unclear canonical owner | `DEFER` |
| API contract would break without approved change | Keep payload scalar shape; convert internal mapping only |

## Required Search/Verification Commands

Use these commands per candidate:

```bash
rg -n "<fieldName>|<RelationName>" <module>/src/main/java
rg -n "findBy.*<FieldName>|@Query" <module>/src/main/java
rg -n "<EntityName>|<fieldName>" <module>/src/test/java
```

Use these commands per module before closing:

```bash
./mvnw -pl <module> -DskipTests compile
./mvnw -pl <module> test
```

## Done Criteria

Migration is complete only when all are true:

1. Every approved same-module Core candidate is `DONE` or explicitly `DEFER` with reason.
2. No cross-service JPA links were introduced.
3. Module tests pass for all changed modules.
4. Architecture tests pass for all changed modules.
5. Audit/Event entities remain untouched.

## Current Deferred Items (Seed)

1. `pos-inventory`: `CycleCountTask.latestCountEntryId` (cyclic persistence/teardown risk; requires explicit lifecycle strategy before conversion).

## Execution Log

- 2026-03-10: Autonomous plan created. No new code changes applied by this file creation.

