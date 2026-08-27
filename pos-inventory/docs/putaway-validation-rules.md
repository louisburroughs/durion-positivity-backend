# Putaway Validation Business Rules

## Overview

This document defines the business rules for routing a received line to a destination bin and for
executing the putaway move from staging to storage.

It was originally written from the clarification in issue #229 (for story #31). Its capacity and
source-on-hand rules described real behaviour, but its **compatibility** section was aspirational:
"SKU not allowed in that zone", "temperature class mismatch", "hazardous/non-hazardous rules
violation" were never implemented. What the code actually did was gate putaway on the existence of a
`(itemSKU, locationId)` replenishment-policy row. Issue #1514 replaced that with a real
storage-compatibility model and changed the capacity semantics, and this document now describes the
implemented behaviour.

Temperature class is still **not** consulted: `storage_location.temperature` exists and is published
by pos-location, but no putaway check reads it. It is not planned.

## Context

- **Origin Story:** [Issue #31 - Putaway: Execute Put-away Move (Staging → Storage)](https://github.com/louisburroughs/durion-positivity-backend/issues/31)
- **Clarification Issue:** [Issue #229 - Clarification Origin #31](https://github.com/louisburroughs/durion-positivity-backend/issues/229)
- **Category-based putaway:** [Issue #1514 - Putaway rules flexibility](https://github.com/louisburroughs/durion-positivity-backend/issues/1514)
- **Resolution Date:** 2026-01-12 (original), 2026-08-27 (#1514 rewrite)
- **Resolved By:** @louisburroughs

## What changed in #1514 — read this first

**A replenishment policy is no longer required for putaway.** Before #1514, both destination
eligibility and destination capacity were derived from `replenishment_policy`:

- `validateLocationCompatibility` refused the destination unless a `(itemSKU, locationId)`
  replenishment-policy row existed. Receiving a genuinely new SKU is an ordinary event, and it could
  not be put away anywhere: generation resolved a destination and validation then refused it for
  having no policy row.
- `validateLocationCapacity` fell back to `SUM(replenishment_policy.maximum_quantity)` for the
  location and treated a still-zero result as "full", so a bin that had simply never declared a
  capacity computed max = 0 and hard-failed every putaway into it.

Both gates are gone. `ReplenishmentPolicy` is untouched and keeps doing its documented job — a
min/max slotting target for the restock scan — but it no longer says anything about whether an item
*may* be stored somewhere. `sumMaximumQuantityByLocationId` had no other caller and was removed with
the gate.

Two further behavioural changes worth knowing before reading the rules below:

- Rule selection is now **per line item**. It used to be
  `findAllByIsEnabledTrueOrderByPriorityAscRuleIdAsc().get(0)` — one rule for every line of every
  receipt, whatever the item was, so tires, oil and spark plugs all resolved to the same destination.
- The hardcoded `00000000-0000-0000-0000-000000000001` "default location" fallback is gone. An
  enabled `ANY` rule is the terminal fallback instead.

## Business Rules

### 0. Rule Matching: which destination is suggested

`PutawayRuleMatcher` resolves the governing `putaway_rule` for each received line. Tiers are tried in
strict precedence, and `priority` only breaks ties **within** a tier (lowest wins, with `ruleId` as a
deterministic final key):

| Tier | `match_type` | `match_value` | Beats |
|---|---|---|---|
| 1 | `SKU` | catalog product id | everything below |
| 2 | `SUBCATEGORY` | catalog subcategory id | `CATEGORY`, `ANY` |
| 3 | `CATEGORY` | catalog category id | `ANY` |
| 4 | `ANY` | must be `NULL` | nothing — terminal fallback |

`SUBCATEGORY` has to outrank `CATEGORY` because `Batteries` is a *subcategory* of
`Electrical System`: a category-only key cannot express the hazard containment the narrower level
carries, so the narrower level must be able to override the broader one.

Rules:

- **Match is on ids, never on names.** pos-inventory only receives category *names* as un-refreshed
  snapshots on product facts (pos-catalog publishes product facts, not category facts, so a rename
  is only visible after a product replay). Ids survive a rename; names do not.
- **A tier with nothing to match on is skipped, not treated as a wildcard.** An unclassified SKU, or
  a product whose subcategory has never been published, falls through to the next tier.
- **An unparseable `match_value` matches nothing.** The stored text is parsed as a UUID, so a rule
  authored with differently-cased or differently-spaced text still matches; a value that is not a
  UUID at all matches no line.
- **Exactly one enabled `ANY` rule may exist.** `ANY` matches every line, so a second enabled one
  would be unreachable configuration; the CRUD layer refuses it with `409`. This is enforced in the
  service rather than by a partial unique index, because H2 cannot express one and the `dev` profile
  runs H2.
- **No match at all is an error, loudly.** `NO_PUTAWAY_RULE_MATCH` (422) means the terminal `ANY`
  rule is missing — a configuration gap, not a data problem with the receipt. The remedy is named in
  the message: create an enabled `ANY` rule. The pre-#1514 code silently routed this case at a bin
  that no environment has, so the failure only surfaced later, at execution, as a location error
  against a fabricated id.

One enabled-rule query and one batched category lookup serve a whole receipt regardless of how many
lines it has. `PutawayDestinationResolver` and the `FIXED` / `LAST_USED` / `CLOSEST_AVAILABLE`
destination strategies are unchanged by #1514.

### 1. Destination Location: Storage Compatibility

**Default Behavior (Mandatory):**

- **Block the putaway transaction** if the destination is not physically fit for the item
- Display clear error: `LOCATION_NOT_VALID_FOR_SKU` — the error code is unchanged; only the reason
  text is new, and it now names the class/capability mismatch
- Clerk **must select a different location**

Two independent checks make up compatibility, in this order:

**1a. The destination must be the target of an enabled putaway rule.** A bin no rule points at is not
part of the putaway topology; the reason is `Destination location is not enabled for putaway`.

**1b. The destination's storage class must accept the item's catalog class.** This is the
`storage_compatibility` matrix (Flyway table, seeded by `V43__storage_compatibility.sql`), keyed on
catalog **ids**:

| Column | Meaning |
|---|---|
| `match_level` | `CATEGORY` or `SUBCATEGORY` |
| `catalog_ref_id` | the catalog category or subcategory id |
| `storage_category_code` | a storage class this catalog class may be stored in |
| `requires_containment` | the destination must also declare `hazard_containment` |

Storage classes are owned by pos-location (`StorageCategory`) and replicated onto
`ext_storage_location.storage_category_code`:

`TIRE_RACK`, `OIL_STORAGE`, `BATTERY_RACK`, `SMALL_PARTS_BIN`, `BULK_FLOOR`, `STAGING`,
`QUARANTINE`, `GENERAL`.

`StorageCompatibilityEvaluator` judges in this order, and the order is the rule:

1. **`STAGING` and `QUARANTINE` refuse everything.** They are putaway *sources*, not destinations.
   Checked first so the operator gets that reason rather than a bare "no matching rule". The matrix
   `CHECK` constraint also forbids any row naming them, so this is structural rather than
   conventional.
2. **Containment is enforced wherever the item's own class demands it, whatever the destination is
   coded as.** When *every* storage class the matrix accepts for an item requires containment,
   containment is a property of the **item**: a battery needs a contained rack no matter which bin it
   is offered, so this gate runs before the `GENERAL` short-circuit below. Items whose accepted set
   mixes contained and uncontained classes are unaffected — `Fluids & Chemicals` accepts
   `OIL_STORAGE` (contained) *or* `BULK_FLOOR` (not), so oil on a bulk floor stays legal.

   This is defence in depth, not a workaround. Seeded storage locations *do* declare capabilities, so
   on a seeded environment a battery routes to a `BATTERY_RACK` by rule and never reaches this gate.
   What the gate covers is every path that yields `GENERAL` without anyone having judged the location
   fit for acid: a storage location created through the API without a capability, and a replica row
   not yet rehydrated after #1514. Silent acceptance is the wrong failure mode for hazardous goods.
3. **`GENERAL` accepts every catalog category.** It is the permissive default, and it is also where a
   null or blank replica code lands. pos-location resolves an undeclared capability to `GENERAL`
   *before publishing* (`StorageCategory.orDefault`), so null on the replica means "no post-#1514
   fact seen yet", not "undeclared" — and resolving it permissively keeps a cold replica behaving as
   it did pre-#1514 instead of dead-ending every receipt. There is deliberately **no** third
   "unknown" branch; it would be dead code. A destination absent from the replica altogether lands
   here too, which is why check 2 is not conditioned on the destination's code.
4. **An item with no resolvable catalog class is accepted only by `GENERAL`.** Having passed check 2,
   the destination declares a specific class and there is nothing to match it against.
5. **Subcategory rows override category rows entirely.** When any `SUBCATEGORY` row exists for the
   item's subcategory, that set is authoritative and the parent's `CATEGORY` rows are ignored — they
   replace, they do not supplement. A battery must not inherit `Electrical System`'s
   `SMALL_PARTS_BIN` permission.
6. **Containment is required where the matched row says so.** `BATTERY_RACK` and `OIL_STORAGE` are
   the containment-bearing classes. An explicit `hazard_containment = TRUE` is required, so both null
   and false refuse.

Note that a **subcategory's physical nature can diverge from its parent category's**. `ATF & Gear
Oil` is a bulk fluid filed under `Drivetrain & Transmission`, whose accepted set is entirely
uncontained and has no `OIL_STORAGE`; without a subcategory override a gallon of ATF would be
accepted into a small-parts bin and refused from oil storage. Re-check this whenever a subcategory is
added to the catalog taxonomy.

**Override Policy (unchanged):**

- **No override by default**
- Optional, tightly controlled override:
  - Requires permission: `OVERRIDE_LOCATION_COMPATIBILITY`
  - Requires mandatory reason code and free-text justification
  - Emits audit event: `PutawayOverrideLocationRule`
- Overrides should be disabled at launch unless business explicitly requires them

**Rationale:** Allowing overrides here easily leads to unsafe storage, regulatory violations, and
downstream picking errors.

**Deliberately not checked here:** the destination's existence in the replica.
`validateLocationCompatibility` does not gate on `isExists()`. V41 ships the replica's capability
columns empty with no backfill, so on an upgraded environment every destination looks like a missing
or capability-less row until pos-location's facts are republished; refusing here would dead-end every
receipt in exactly the window #1514 exists to fix. The dangerous half is still closed — an item whose
catalog class demands containment is refused by a destination that does not declare it, and an
unknown destination declares nothing. Existence and active status are enforced at execution by
`validateLocationCapacity`.

### 2. Destination Location: Capacity Validation

**Capacity semantics changed in #1514:**

| Declared `maxUnitCapacity` | Behaviour |
|---|---|
| **absent (null)** | **Uncapped.** Nothing to compare against, so nothing to refuse and no near-limit warning. |
| **zero** | **Refuse.** An operator saying "hold nothing here" is the strongest possible refusal. |
| **positive** | Compared against ledger on-hand at the location, as below. |

The undeclared and declared-zero cases are deliberately *not* folded together. Mapping a declared
zero to uncapped would invert the strongest possible refusal into unlimited acceptance. Nothing falls
back to summed replenishment maximums any more.

**Default Behavior (where a positive limit is declared).** Projected on-hand is the location's ledger
on-hand plus the quantity being put away:

| Projected vs declared limit | Behaviour |
|---|---|
| below the limit | success; `CAPACITY_NEAR_LIMIT` warning at ≥ 90% utilisation |
| exactly at the limit | **block** — `LOCATION_AT_CAPACITY` |
| over the limit by ≤ 10% | success with a `CAPACITY_NEAR_LIMIT` warning naming the overfill |
| over the limit by > 10% | **block** — `LOCATION_AT_CAPACITY` |

When blocked, prompt the clerk to choose another valid location or split the quantity across
multiple locations.

Note the discontinuity in that table: landing *exactly* on the limit blocks, while overshooting it by
up to 10% passes with a warning. That is pre-existing behaviour, unchanged by #1514, and it is
documented here rather than smoothed over because it is surprising enough to be mistaken for a bug in
the new capacity handling.

Capacity is a bin's declared unit limit and stays an `int`; the on-hand it is compared against comes
from the ledger and is decimal (ADR-0055, #1414). The comparison widens the limit rather than
narrowing the measurement, so a bin holding 10.5 units is not reported as holding 10.

**Optional Override (More Permissive Than Compatibility):**

- Allowed **only if:**
  - Permission `OVERRIDE_LOCATION_CAPACITY` is present
  - Overfill is within configured tolerance (10%)
  - Justification and `approvedBy` are captured
- A destination declaring a capacity of **zero** cannot have its tolerance evaluated at all (the
  ratio would divide by zero) and returns `CAPACITY_OVERRIDE_TOLERANCE_UNCHECKABLE`. Since #1514 an
  *undeclared* capacity is uncapped and never throws, so this no longer fires for the "nobody
  configured a limit" case it used to dominate.

**Audit Requirements (If Overridden):**

- `previousCapacity`
- `newCapacity`
- `overrideReasonCode = CAPACITY_OVERRIDE`
- `approvedBy`

**Rationale:** Capacity violations are sometimes operationally tolerable short-term, but must be
visible and auditable.

### 3. Source Location: On-Hand Validation

Unchanged by #1514.

**Default Behavior (Mandatory):**

- **Block the putaway transaction** if source location shows zero quantity
- Display error: `NO_ON_HAND_AT_SOURCE_LOCATION`
- System must **NOT** silently create inventory

**Recovery / Reconciliation Flow:**

Provide a **guided reconciliation path**, not a blind override.

**Allowed Recovery Actions (Permission-Gated):**

1. **Trigger a cycle count / recount**
   - Permission: `INITIATE_CYCLE_COUNT`
   - Creates a reconciliation task for the source location

2. **Inventory adjustment (exceptional)**
   - Permission: `ADJUST_INVENTORY`
   - Requires:
     - Explicit reason code (`MISPLACED_STOCK`, `UNRECORDED_RECEIPT`, etc.)
     - Manager approval if above threshold
   - Adjustment must complete **before** putaway proceeds

**Explicitly Disallowed:**

- Proceeding with putaway without correcting inventory records
- "Assume quantity exists" behavior

**Rationale:** This condition indicates shrink, mis-scan, or missed receipt. Letting it pass corrupts
inventory accuracy system-wide.

## Rule Configuration API

Putaway rules are managed over `/v1/inventory/putaway/rules` so fixtures and operators create them
through the application layer, with an `@EmitEvent` audit trail, rather than by hand-inserting rows.

| Operation | Method | Path | Permission | Event |
|---|---|---|---|---|
| `listPutawayRules` | GET | `/v1/inventory/putaway/rules` | `inventory:putaway_rule:view` | — |
| `getPutawayRule` | GET | `/v1/inventory/putaway/rules/{ruleId}` | `inventory:putaway_rule:view` | — |
| `createPutawayRule` | POST | `/v1/inventory/putaway/rules` | `inventory:putaway_rule:manage` | `INVENTORY_PUTAWAY_RULE_CREATE` |
| `updatePutawayRule` | PUT | `/v1/inventory/putaway/rules/{ruleId}` | `inventory:putaway_rule:manage` | `INVENTORY_PUTAWAY_RULE_UPDATE` |
| `deletePutawayRule` | DELETE | `/v1/inventory/putaway/rules/{ruleId}` | `inventory:putaway_rule:manage` | `INVENTORY_PUTAWAY_RULE_DELETE` |

`updatePutawayRule` is a full replacement, not a patch, with one exception: omitting `isEnabled`
keeps the rule's current enabled state, so a PUT that only retunes a priority cannot silently
re-enable a rule somebody deliberately disabled.

Only the terminal `ANY` rule is Flyway-seeded (`R__seed_reference_inventory.sql`). Category- and
SKU-specific rules are Tier 2 per `docs/DATA_SEED_STRATEGY.md` §2 and enter through this API or the
CSV fixture pack. The compatibility matrix, by contrast, is Tier 1 Flyway: it is service-private,
environment-invariant, crosses no domain wall and has no audited lifecycle.

## Rollout: the replica ships empty

`V41__ext_replica_category_and_capability.sql` adds the category and capability columns and does
**not** backfill them. Until each aggregate is republished:

- `ext_product.category_id` / `subcategory_id` are null, so `SUBCATEGORY` and `CATEGORY` rules match
  nothing and every line falls through to the `ANY` rule.
- `ext_storage_location.storage_category_code` is null, which reads as `GENERAL`, so the matrix
  accepts every destination that a rule points at.

The containment gate still protects hazardous goods in that window (see rule 1, check 2), because it
keys on the item's class rather than the destination's. See `docs/OPERATIONS_RUNBOOK.md` →
"Replica seeding and drift repair (replay)" → "Issue #1514: rehydrating the putaway replica columns"
for the operator procedure.

## Permission Model

| Permission | Description | Typical Use Case |
|-----------|-------------|------------------|
| `inventory:putaway_rule:view` | Read putaway rule configuration | Inspect which rule will govern an item |
| `inventory:putaway_rule:manage` | Create, update, delete putaway rules | Configure where an item class is stored |
| `OVERRIDE_LOCATION_COMPATIBILITY` | Override location/SKU compatibility rules | Exceptional placement when no alternative exists |
| `OVERRIDE_LOCATION_CAPACITY` | Override location capacity limits | Temporary overfill within tolerance |
| `INITIATE_CYCLE_COUNT` | Trigger cycle count for reconciliation | Resolve data consistency issues |
| `ADJUST_INVENTORY` | Make inventory adjustments | Correct system records to match physical reality |

## Error Codes

| Error Code | Status | Description | Resolution |
|-----------|--------|-------------|------------|
| `NO_PUTAWAY_RULE_MATCH` | 422 | No enabled rule matches the line, including `ANY` | Create an enabled `ANY` rule |
| `LOCATION_NOT_VALID_FOR_SKU` | 422 | Destination is not enabled for putaway, or its storage class does not accept the item's catalog class | Select a different location or request an override |
| `LOCATION_AT_CAPACITY` | 422 | Destination's declared limit is reached, or it declares a capacity of zero | Select a different location or request a capacity override |
| `NO_ON_HAND_AT_SOURCE_LOCATION` | 422 | Source location has zero on-hand inventory | Initiate reconciliation (cycle count or adjustment) |
| `DUPLICATE_ENABLED_ANY_PUTAWAY_RULE` | 409 | An enabled `ANY` rule already exists | Retarget the existing rule instead of adding a second |

## Implementation

### Exception Classes

- `PutawayValidationException` - Base exception for validation errors
- `NoPutawayRuleMatchException` - No rule matches the received line
- `LocationNotValidForSkuException` - Storage compatibility violation
- `LocationAtCapacityException` - Capacity limit violation
- `NoOnHandAtSourceLocationException` - Source inventory data consistency error
- `DuplicateEnabledAnyPutawayRuleException` - Second enabled `ANY` rule refused

### Services

- `PutawayRuleMatcher` - Per-line rule resolution and tier precedence
- `StorageCompatibilityEvaluator` - Storage-class / catalog-class fitness decision
- `SkuCategoryLookup` / `ReplicaSkuCategoryLookup` - the item's category and subcategory ids from the
  catalog replica; registered unconditionally
- `PutawayValidationService` / `PutawayValidationServiceImpl` - validation entry points
- `PutawayRuleService` / `PutawayRuleServiceImpl` - rule CRUD

### Entities and Enums

- `PutawayRule` - `priority`, `matchType`, `matchValue`, `destinationLocationId`,
  `destinationStrategy`, `isEnabled`
- `StorageCompatibility` - the matrix row
- `PutawayRuleMatchType` - `SKU`, `SUBCATEGORY`, `CATEGORY`, `ANY`, and the explicit
  `precedence()` order
- `StorageCompatibilityMatchLevel` - `CATEGORY`, `SUBCATEGORY`
- `OverrideReasonCode` - Enum for override justifications
- `ReconciliationReasonCode` - Enum for inventory adjustment reasons

### DTOs

- `PutawayRuleRequest` / `PutawayRuleResponse` - rule CRUD payloads
- `PutawayExecutionRequest` - Request with override flags and justification
- `ValidationResult` - Validation outcome with errors and warnings

### Security

- `InventoryPermissionRegistry` - `PUTAWAY_RULE_VIEW`, `PUTAWAY_RULE_MANAGE`
- `PutawayPermissions` - override permission constants

### Migrations

- `V41__ext_replica_category_and_capability.sql` - replica category and capability columns
- `V42__putaway_rule_match_criteria.sql` - `match_type` / `match_value` replacing `criteria`
- `V43__storage_compatibility.sql` - the compatibility matrix and its seed

## Testing Considerations

### Unit Tests Required

- Tier precedence: a `SKU` rule beating a `SUBCATEGORY` rule beating a `CATEGORY` rule beating `ANY`
- Priority breaking ties only *within* a tier
- A tier with no resolvable target falling through rather than matching everything
- An unparseable `match_value` matching nothing
- `NO_PUTAWAY_RULE_MATCH` when no enabled `ANY` rule exists
- Compatibility: accepted class, refused class, `STAGING`/`QUARANTINE` as destination
- Containment: required by the matched row; required by the item's class even against `GENERAL`;
  *not* required where the accepted set mixes contained and uncontained classes
- Subcategory rows replacing rather than supplementing parent category rows
- Null / blank replica storage class resolving to `GENERAL`
- Capacity: undeclared uncapped, declared zero refusing, tolerance and near-limit warnings
- Source on-hand validation with zero quantity
- Override logic with and without permissions

### Integration Tests Required

- End-to-end putaway execution with valid data
- Putaway blocked by compatibility rules
- Putaway blocked by capacity rules
- Putaway blocked by missing source inventory
- Override flows with proper permissions
- Migration tests against H2: `PutawayRuleMatchCriteriaMigrationTest`,
  `StorageCompatibilityMigrationTest`

### Edge Cases

- A brand-new SKU with no catalog classification at all
- A product whose subcategory has never been published
- A destination absent from the replica
- Negative quantities
- Null location IDs
- Concurrent putaways to same location
- Override without justification
- Override with insufficient tolerance

## References

- [GitHub Issue #31 - Putaway: Execute Put-away Move](https://github.com/louisburroughs/durion-positivity-backend/issues/31)
- [GitHub Issue #229 - Clarification for Issue #31](https://github.com/louisburroughs/durion-positivity-backend/issues/229)
- [GitHub Issue #1514 - Putaway rules flexibility](https://github.com/louisburroughs/durion-positivity-backend/issues/1514)
- ADR-0044 - event-only domain walls (the capability and category ride existing facts additively)
- ADR-0055 / #1414 - decimal quantities
- `docs/DATA_SEED_STRATEGY.md` §2 - seed tier classification
- `docs/OPERATIONS_RUNBOOK.md` - "Replica seeding and drift repair (replay)"
- Inventory Ledger Event Type Definitions: `InventoryLedgerEventType.java`

## Change Log

| Date | Author | Change |
|------|--------|--------|
| 2026-01-12 | System | Initial business rules documentation based on clarification #229 |
| 2026-08-27 | #1514 | Rewritten to describe implemented behaviour: `SKU > SUBCATEGORY > CATEGORY > ANY` rule precedence, the `storage_compatibility` matrix, the containment rule, `STAGING`/`QUARANTINE` refusing as destinations, new capacity semantics, and the removal of the replenishment-policy gates |
