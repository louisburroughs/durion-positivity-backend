# Location Scope and Effective Dating in the JWT — Spike Findings

**Issue:** [#1375](https://github.com/louisburroughs/durion-positivity-backend/issues/1375)
**Date:** 2026-09-07
**Status:** Findings complete; recommendation made; see ADR-0061 in `durion/docs/adr/`.
**Related:** #1372 (made `role_permissions` authoritative, documented the gap), #1373, #1374,
#1499/#1512 (RBAC audit), `docs/rbac-permission-role-audit-2026-08.md`

This is the deliverable for a spike, not an implementation. No token format changed, no
`CATALOG_VERSION` bump, no change to `role_permissions`.

---

## Summary

The spike asked whether location scope and effective dating should be carried in the JWT.
The answer is yes for scope, in a **specific and much cheaper shape than the issue assumed**,
and yes for effective dating, using machinery that already exists.

Three findings changed the shape of the answer:

1. **Demand is broad, not narrow.** 77 endpoints across 15 modules accept a caller-supplied
   `locationId` and authorize it with a flat permission that has no location dimension. The
   issue's hypothesis that this might be "three endpoints" is not supported.

2. **The platform already has a working, event-published, effective-dated user→location
   model — and it is not the one in pos-security-service.** `pos-people`'s
   `EmployeeLocationAssignment` is populated, queried, effective-dated, and already emits
   change events. `role_assignments.scope_type` is unenforced, unseeded and unexercised.
   The correct answer to "which model owns scope" is the people one.

3. **The claim does not have to touch `perm_bits` at all, and hierarchy keeps it small.** The
   issue frames the cost around `PermissionCode` / `GatewayPermissionCatalog` /
   `PermissionBitsetCodec` / `CATALOG_VERSION` lockstep. That cost is only incurred by
   encodings that put location *inside* the permission bitset. Two additive claims —
   `loc_fin_bits` / `loc_oth_bits` (which permissions are location-limited, and on which
   hierarchy dimension, reusing the same bit indexes) and `loc_scope` (the assigned nodes) —
   leave the bitset semantics, the catalog and the catalog version untouched. Because a node
   covers its descendants and is evaluated at check time, a Region manager carries **one** node
   id whether the region has 3 shops or 300. Worst case **+348 bytes**, 1.53% of the header
   budget.

**Recommendation:** retire the pos-security-service scope model; put `location_scope`
(`ALL` | `LOCATION`) on the role; assign scope to a location node that covers its descendants,
sourced from pos-people and evaluated against a replicated ancestor set; carry it as additive
claims; enforce in the owning service. Clamp token lifetime to assignment expiry and revoke on
assignment change.

---

## 1. Demand — which endpoints are wrong today

Method: rather than grepping `@PreAuthorize` (which the #1375 comment correctly warns
under-reports, because scope-like checks are too conditional to express in an annotation),
the inventory was built by parsing every `*Controller.java` mapping method's **parameter
list** for a `locationId` parameter, then reading back for the governing `@PreAuthorize`.

**Result: 77 endpoints, 15 modules, 0 with any location-ownership check.**

| Module | Endpoints | Module | Endpoints |
| --- | ---: | --- | ---: |
| pos-inventory | 24 | pos-invoice | 2 |
| pos-location | 15 | pos-order | 2 |
| pos-workorder | 9 | pos-accounting | 1 |
| pos-people | 7 | pos-bulk-loader | 1 |
| pos-catalog | 5 | pos-people-contact | 1 |
| pos-shop-manager | 5 | pos-price | 1 |
| pos-customer | 2 | pos-security-service | 1 |
| | | pos-warranty | 1 |

By binding: 24 path variable, 53 query or request parameter. By verb: 43 GET, 34 mutating
(27 POST, 3 PUT, 2 PATCH, 2 DELETE). Every one carries a `@PreAuthorize`; none of those
annotations can express "…and only for a location this caller covers".

Reproduce with the parser in §7.

### 1a. The `view_all_locations` pattern does not enforce what it appears to

`workorder:wip:view_all_locations` is cited as the existing catalog-encoding answer, and the
#1375 comment is right that it is genuinely implemented. But it gates only the **widening**
flag, not the narrow case:

```java
// WipController.java:74-88
public ResponseEntity<Page<WorkorderStatusView>> listWip(
        @Parameter(...) @RequestParam String locationId,
        @RequestParam(defaultValue = "false") boolean multiLocation, ...) {
    if (multiLocation && authentication.getAuthorities().stream()
            .noneMatch(a -> WIP_VIEW_ALL_LOCATIONS.equals(a.getAuthority()))) {
        throw new AccessDeniedException(...);
    }
    ... wipService.getWipWorkorders(locationId, multiLocation, pageable);
```

`locationId` is supplied by the client and never checked against anything the caller holds.
A technician at shop A with `workorder:wip:view` reads shop B's WIP board by changing one
query parameter. The permission that exists to protect cross-location reads is bypassed by
not asking for cross-location reads.

**This matters for Q2.** The catalog-encoding alternative is not merely a sprawl problem —
as currently practised it does not close the hole. Making it work would require every one of
the 77 endpoints to independently validate the inbound `locationId` against a scope source,
which is the same work as the token approach plus 357 new permission codes (§3).

### 1b. The role pair that cannot be told apart

Confirmed as reported in #1375: INVENTORY_MANAGER and INVENTORY_CONTROLLER hold identical
grants by design (`R__seed_role_permissions.sql:105,174`), on the stated understanding that
`role_assignments.scope_type` distinguishes them. Since scope reaches no enforcement point,
the two roles are indistinguishable in every code path. This is a named, non-speculative
demand case, and it is a correctness defect in the role model, not a nice-to-have.

---

## 2. The ownership fork — two models, one of which works

The issue assumes `role_assignments` is the scope model. It is one of two, and it is the
weaker one.

| | `pos-security-service` `role_assignments` | `pos-people` `EmployeeLocationAssignment` |
| --- | --- | --- |
| Key | `user_id` | `employee.personId` |
| Location column | `role_assignment_scope_locations.location_id` `VARCHAR(255)`, no FK | `location_id UUID NOT NULL` |
| Effective dating | `effective_start_date` / `effective_end_date` / `revoked_at` | `effective_from` / `effective_to` / `status` |
| Primary-location concept | none | `is_primary` |
| Rows in any seed | **zero LOCATION-scoped rows; child table never populated** | populated |
| Queried by production code | `RoleManagementService.userHasPermission` only | `PeopleAvailabilityServiceImpl`, staffing, availability, reports |
| Change events | none | `PeopleEventPublisher.publishStaffingAssignmentUpdated`; `PEOPLE_STAFFING_ASSIGNMENT_CREATE`/`_UPDATE`/`_END` |
| Can resolve a location id | no — pos-security-service has no location replica | yes — `ExtLocationReplica` fed by `LocationEventsListener` |

`EmployeeLocationAssignmentRepository` already exposes exactly the query a scope resolver
needs: `findActiveByPersonIdAndDate(personId, date)` and
`findFirstByEmployee_PersonIdAndIsPrimaryTrueAndStatus(...)`.

**The bridge already exists.** The access token already carries a `personId` claim
(`JwtServiceImpl.java:362`), which is the join key to the people model.

### 2a. This resolves Q4 (location id validation) rather than answering it as asked

Q4 asks whether anything validates `role_assignment_scope_locations.location_id` against
pos-location. Nothing does, and the #1375 comment is right that a scope claim needing
resolvable ids inherits a bootstrapping problem **in pos-security-service**. That problem is
partly an artefact of choosing the wrong owner: `ExtLocationReplica`, fed from location events
by `LocationEventsListener`, gives some services a locally resolvable, event-consistent view.

**But the coverage is thinner than it first appears**, and this bears directly on §3:

| Module | Location replica | Endpoints (of 77) |
| --- | --- | ---: |
| pos-people, pos-invoice, pos-workorder | `ExtLocationReplica` | 18 |
| pos-inventory | `ExtStorageLocationReplica` — **storage bins/shelves, not the site tree** | 24 |
| the other 11 modules | none | 35 |

pos-inventory is the largest consumer of location-scoped endpoints and does **not** replicate
the location tree; its replica models intra-site storage (`storage_location_id`, `site_id`,
`parent_storage_location_id`), a different hierarchy entirely.

And **no location replica carries a parent link.** All three hold `locationId`, `name`,
`active`, `aggregateVersion` and address fields — nothing hierarchical. So hierarchy resolution
is new replication work in every module, not an extension of something already present (#1878).

### 2b. Cost of each ownership model

**Option 1 — keep scope in pos-security-service.** Requires: a location replica and a
`LocationEventsListener` in pos-security-service (new); seed fixtures that cannot reference
pos-location's UUIDs without invisible cross-service coupling (the reason `felicia.grant`'s
scoped row was deferred); and an admin surface to manage scoped assignments that duplicates
the staffing-assignment surface pos-people already ships. It also leaves two divergent
answers to "which locations does this person cover".

**Option 2 — source scope from pos-people (recommended).** Requires: pos-security-service to
consume the staffing-assignment events pos-people **already publishes**, and to keep a small
`person_id → location_id[]` projection for token issuance. Cost is materially lower because
pos-security-service already runs four `@KafkaListener`s
(`CustomerEventsListener`, `PeopleContactEventsListener`, `PeopleContactManifestListener`,
`CustomerManifestListener`) — this is an established pattern in that module, not new
infrastructure. It also deletes a model rather than growing a second one.

**Recommendation: Option 2.** One source of truth, already populated, already event-driven,
already effective-dated, with the join key already in the token.

---

## 3. Claim shape and measured token size

Three platform rules constrain the design:

1. **`location_scope` is a property of the role** (`ALL` | `LOCATION`), not of the assignment
   and not of the employee.
2. **Scope is assigned to a location *node*, and covers that node and every descendant.** A
   role assigned at HQ or Region level can call location-scoped APIs targeting any child
   location beneath it.
3. **An employee may be assigned more than one node**, for coverage that does not fit a single
   subtree — but hierarchy is expected to carry the normal case, so counts stay small.

Rule 1 is what #1373 was reaching for. INVENTORY_MANAGER and INVENTORY_CONTROLLER hold
identical grants on purpose; the roles differ by *reach*, not by *grants*. One column on `roles`
expresses that once; `*_all_locations` twins would express it up to 357 times.

Rule 2 is what supplies the middle management tier. Without it, reach is one shop or everything,
and a Region manager over some-but-not-all shops has no representation.

### Ownership split

| Question | Owner |
| --- | --- |
| Is this role location-scoped? | pos-security-service — new `roles.location_scope` |
| Which node(s) is this employee assigned to? | pos-people — `employee_location_assignment` |
| What is beneath a node? | pos-location — the tree, replicated as an ancestor set (below) |

This preserves §2's conclusion: pos-people still owns user→location. Only the scope
discriminator sits with the roles, where roles already live.

### Claims

Two claims, because a user may hold both a `LOCATION`-scoped and an `ALL`-scoped role and the
latter must not silently widen the former:

| Claim | Value |
| --- | --- |
| `perm_bits` | **unchanged** — every permission the caller holds |
| `loc_fin_bits` | permissions location-scoped along the `FINANCIAL` dimension |
| `loc_oth_bits` | permissions location-scoped along the `OTHER` dimension |
| `loc_scope` | discriminated: `"ALL"`, or the list of assigned node ids; omitted when both bitsets are empty |

A permission absent from **both** bitsets is global. A permission may appear in both — if one
role grants it along `FINANCIAL` and another along `OTHER`, either reach satisfies the check.
Two independent bitsets rather than one bitset plus a flag, because that "both" case is real
and a single dimension flag per permission cannot express it.

The assigned nodes are **not** partitioned by dimension. `EmployeeLocationAssignment.role` is
free-text staffing metadata (`"TECHNICIAN"`), not a security role, so a person's assigned nodes
are the same regardless of which security role is being exercised — only the *traversal* differs.

Both bitsets reuse `PermissionBitsetCodec` and the same bit indexes as `perm_bits`, so they are
covered by the existing `perm_ver` and **require no `CATALOG_VERSION` bump**.

**Enforcement rule.** At an endpoint checking permission `P` for location `L`:

```
if P ∉ loc_fin_bits ∪ loc_oth_bits          → allow (the grant is global)
if loc_scope == ALL                          → allow
if P ∈ loc_fin_bits
   and loc_scope ∩ ancestors(L, FINANCIAL) ≠ ∅  → allow
if P ∈ loc_oth_bits
   and loc_scope ∩ ancestors(L, OTHER) ≠ ∅      → allow
                                              → deny
```

`ancestors(L, dim)` is the materialised ancestor set of `L` on that dimension, **inclusive of
`L` itself**, so a node assigned directly matches without a special case.

**Union semantics.** A permission granted by both a `LOCATION` role and an `ALL` role is
global — its bit is set in *neither* bitset. The broader grant wins, which is standard RBAC
union behaviour and keeps the claim consistent with how `perm_bits` already composes.

**Fail closed.** A caller holding `LOCATION`-scoped roles with no resolvable assigned node gets
scope bits set and `loc_scope` absent, which denies. Absence must never widen to unrestricted
reach. `V3__backfill_primary_location_assignments.sql` records that employees with several
active assignments and no primary exist and are "genuinely ambiguous" — that population is
exactly this case.

**Runtime access only.** Assignment at a parent node confers the right to *call* location-scoped
APIs against descendants. It confers no administrative right to grant scope to others; scoped
delegation is a separate concern on the role-assignment surface and is out of scope here.

### The hierarchy is multi-dimensional — the dimension is a role property

`pos-location` does not model one tree. `Location` holds `Set<LocationParent> parents`, and
`LocationParent` is unique on **`(child_id, parent_type)`** — so a location has at most one
parent *per dimension*, giving several overlapping trees rather than one tree or a free DAG.

`ParentType` has seven values: `HOME_OFFICE`, `HEADQUARTERS`, `REGION`, `DISTRICT`, `PHYSICAL`,
`ORGANIZATIONAL`, `FINANCIAL`. Both traversal APIs take one —
`LocationServiceImpl.getAllChildrenDto(parentId, parentType)` and
`getDescendantsDto(locationId, parentType)` — and `getDescendantsDto` defaults to `PHYSICAL`.

**Decision: the dimension is a property of the role**, alongside `location_scope`:

| `roles.location_hierarchy` | Traverses | Roles |
| --- | --- | --- |
| `FINANCIAL` | the `FINANCIAL` parent chain | accounting and general-manager roles — `ACCOUNT_MANAGER`, `ACCOUNTANT`, `CONTROLLER`, `GENERAL_MANAGER` |
| `OTHER` | the union of the six non-financial types (`HOME_OFFICE`, `HEADQUARTERS`, `REGION`, `DISTRICT`, `PHYSICAL`, `ORGANIZATIONAL`) | every other role |

A financial rollup and an operational rollup are genuinely different questions — who owns the
numbers for a site is not who runs it — so the two must not be conflated, and traversing all
seven types indiscriminately would be the union of every rollup the business has.

Two cautions for seeding:

- **`INVENTORY_CONTROLLER` is not an accounting role.** Any name-based sweep for "CONTROLLER"
  will pick it up incorrectly; it belongs to `OTHER`.
- **`OTHER` branches.** It is the union of six dimensions, so a location may have up to six
  distinct non-financial parents and the ancestor closure is a DAG, not a chain. `FINANCIAL`
  alone is a chain. Materialisation (#1878) must handle both shapes.

There is also no cycle guard at the `Location` level. `StorageLocationServiceImpl` has
`wouldCreateCycle` / `existsCycleForParent`; `LocationServiceImpl` has no equivalent, so
ancestor materialisation cannot currently assume termination.

### Hierarchy is evaluated at check time, not expanded at issuance

The token carries the **assigned node**, never its expansion. `ExtLocationReplica` gains a
materialised ancestor set per location (ordered root→self, inclusive), fed by the existing
`LocationEventsListener` from pos-location's tree. `covers` becomes a set intersection — no
traversal, no extra service hop.

Two consequences, both deliberate:

- **Token stays small.** A Region manager holds one node id whether the region has 3 shops or
  300. Expansion at issuance would put the shop list in the token and reintroduce the bloat
  this design avoids (measured in `scripts/measure-scope-claim-size.py`).
- **Hierarchy edits take effect immediately.** Moving a shop under a different Region changes
  who can see it on the next request, with no token re-issue. This is the right behaviour — it
  is an org-chart change — but it makes hierarchy edits *security-relevant operations* that need
  tight permissions and an audit trail. pos-location must also guarantee the tree is acyclic,
  or ancestor materialisation does not terminate.

### Measured sizes

**Corrections to the issue's premises:** `CATALOG_VERSION` is **76**, not 56
(`PermissionCode.java:953`); the catalog holds **510** codes with max bit index 509, so a
full bitset is 64 bytes → **86** Base64URL characters. Baseline access token ≈ **650 bytes**.

Measured with `scripts/measure-scope-claim-size.py`, which reproduces
`PermissionBitsetCodec.encode` exactly and builds real JWS compact serialisations with the
claim set from `JwtServiceImpl.generateTokenPair`. One assigned node; *realistic* is
near-disjoint dimensions (~40% of permissions scoped along `OTHER`, ~10% along `FINANCIAL`),
*worst case* is every permission scoped along both at once:

| Permission profile | unscoped | realistic | worst case | worst delta |
| --- | ---: | ---: | ---: | ---: |
| DISPATCHER-like (11) | 643 | 849 | 969 | +326 |
| SHOP_MANAGER-like (17) | 648 | 865 | 985 | +337 |
| CONTROLLER-like (52) | 651 | 972 | 993 | +342 |
| ADMIN-like (387) | 653 | 999 | 1 001 | +348 |
| whole catalog (510) | 653 | 999 | 1 001 | +348 |

Cost of additional assigned nodes (ADMIN-like, worst case): 1 → 1 001 B, 2 → 1 053 B,
4 → 1 157 B, 8 → 1 365 B, 16 → 1 781 B.

**Worst case 1 001 B against a 65 514 B header budget — 1.53%.** Each bitset is bounded by the
catalog at 86 characters; `loc_scope` grows only with assigned-node count, which hierarchy keeps
at 1–2.

A cap of ~8 assigned nodes is worth having as an **assertion that the hierarchy was modelled
correctly**, not as a size limit — 16 nodes still costs under 1.8 KB. Exceeding it should
surface as a configuration error, not degrade to a runtime lookup.

### Why locations are not encoded as a bitset

Encoding locations the way `PermissionCode` encodes permissions — an index per location, carried
as a bitset — was considered and rejected. Measured claim payload, Base64URL characters:

| Deployment | Covers | Bitset | Id list |
| ---: | ---: | ---: | ---: |
| 500 | 1 | 84 | **22** |
| 500 | 3 | 84 | **64** |
| 500 | 25 | **84** | 534 |
| 10 000 | 1 | 1 667 | **22** |
| 10 000 | 25 | 1 667 | **534** |
| 10 000 | 2 000 | **1 667** | 42 667 |

The bitset costs `maxIndex/6` characters **regardless of how many locations are covered**, so it
couples every user's token size to the total number of locations on the platform: opening the
5 000th shop makes a single-shop technician's token ~834 characters heavier. An id list costs
~22 characters per node and scales with that user's actual assignment instead.

Hierarchy already keeps assigned-node counts at 1–2, which is precisely the region where the id
list wins. The two mechanisms are alternative compressions of the same problem, and hierarchy is
the better one here.

There is also a structural cost the `perm_bits` analogy hides. `PermissionCode` can be a bitset
because it is a compile-time enum with `CATALOG_VERSION` locked between pos-security-service and
pos-api-gateway — permissions change at deploy time. Locations are runtime business data, so a
location bitset needs an append-only index assigned by pos-location, replicated everywhere and
never reused: a permanent distributed invariant, in exchange for compression hierarchy already
provides.

**It is deferred, not refused.** `loc_scope` is discriminated, so a bitset encoding can be added
later without a `CATALOG_VERSION` bump if measured assigned-node cardinality ever justifies it.
(Mitigation worth recording: a service never needs to decode the whole bitset — it resolves the
one inbound `locationId` to its index and tests that bit — so the registry burden is on issuance
and replication, not on the check.)

### Q7 — the cost framing does not apply

Q7 estimates blast radius as `PermissionCode` + `GatewayPermissionCatalog` (lockstep
`CATALOG_VERSION`) + `PermissionBitsetCodec` + gateway decode + **1 086 `@PreAuthorize`
annotations across 308 files in 23 gateway-secured modules**. That is correct only for
encodings that redefine what a permission bit means.

This design incurs none of it:

- `perm_bits` semantics unchanged, and both scope bitsets reuse the same indexes → **no
  `CATALOG_VERSION` bump** (also an explicit non-goal).
- `PermissionBitsetCodec` reused as-is; `PermissionCode` and `GatewayPermissionCatalog`
  untouched.
- Existing `@PreAuthorize` annotations keep working unchanged; they answer "may this caller
  do X", which stays true. Scope answers "…here", a separate check applied only at the 77
  endpoints that take a `locationId`.
- A service that does not yet read the claims behaves exactly as today — so rollout is
  per-endpoint and reversible.

**This answers Q8: there is no flag-day.** The path is additive claims → gateway passthrough →
per-module adoption, each step independently deployable.

### Q2 — cost of the catalog-encoding alternative

357 of the 445 parseable permission codes belong to location-touching domains. Encoding scope
as `*_all_locations` twins implies up to that many new codes against a 510-code catalog — more
than doubling it — and per §1a still would not enforce the narrow case. It is also strictly
worse than `roles.location_scope`, which expresses the same distinction once per role, and it
cannot express hierarchy at all.

## 4. Effective dating (Q5, Q6)

Access tokens live 3 600 s (`JwtServiceImpl.ACCESS_TOKEN_EXPIRATION_SECONDS`).
`findEffectiveAssignmentsByUser` filters at issuance only, so an assignment expiring ten
minutes after issuance keeps travelling for another fifty.

**Not acceptable once scope is enforced.** Today the window is harmless because nothing reads
scope; the moment `loc_scope` gates access, a stale window is a real over-grant.

Recommended, in preference order — all three, they compose:

1. **Clamp `exp`.** Issue `exp = min(now + 3600, earliest effective_to among the assignments
   contributing to this token)`. Cheap, purely issuer-side, no new infrastructure, and it
   bounds the window to zero by construction. Shortening the global TTL instead is a worse
   trade: it taxes every user to fix a case that affects assignment holders.
2. **Revoke on assignment change.** `TokenRevocationManager` + the `jwt_token` table already
   support this, and pos-people already emits `PEOPLE_STAFFING_ASSIGNMENT_UPDATE`/`_END`.
   Blast radius (Q6): revocation is per-`jti` against Redis, so revoking on change affects
   only the tokens of the person whose assignment changed. The failure mode to design for is
   Redis unavailability — `TokenRevocationManager.isRedisAvailable()` already degrades, and
   that degradation becomes security-relevant once revocation is load-bearing, so it needs an
   explicit fail-open/fail-closed decision. Called out as a follow-up.
3. **Do not** add a separate "not valid after" claim. It duplicates `exp` and every validator
   would need teaching. Clamping `exp` gets the same result for free.

---

## 5. Recommendation

1. **Retire** `role_assignments.scope_type` and `role_assignment_scope_locations`. Keep
   `role_assignments` for effective-dated user→role assignment; scope leaves it. This ends
   the situation #1375 correctly identifies as the thing worth ending — a scope model in the
   schema enforced nowhere — by deleting it rather than by building a second enforcement path
   for it.
2. **Add `location_scope` (`ALL` | `LOCATION`) to the role.** This is where the distinction
   belongs: #1373's identical-grant role pair differs by reach, not by grants, and one column
   on `roles` expresses that once instead of once per permission code.
3. **Make pos-people the source of the caller's assigned node(s)** — `employee_location_assignment`,
   joined via the `personId` claim the token already carries.
4. **Scope assigned at a node covers that node and every descendant**, evaluated at check time
   against a materialised ancestor set replicated onto `ExtLocationReplica` — never expanded
   into the token. This is what supplies the middle management tier, and it is what keeps the
   claim small. **The dimension traversed is a role property**: `FINANCIAL` for accounting and
   general-manager roles, `OTHER` (the six non-financial `ParentType`s) for everything else.
5. **Carry three additive claims**, `loc_fin_bits`, `loc_oth_bits` and `loc_scope` (§3). No `CATALOG_VERSION` bump,
   no change to `perm_bits` semantics. `loc_scope` is discriminated so a denser encoding can be
   adopted later without a version bump.
6. **Enforce in the owning service** at the 77 endpoints, starting with the demand cases:
   workorder WIP, inventory adjustment approval, people time-entry approval. The gateway
   stays a coarse gate; it has no domain knowledge of which parameter is a location.
7. **Clamp `exp` to assignment expiry** and revoke on assignment change (§4).
8. **Retire `GET /v1/roles/check-permission`.** Because hierarchy keeps assigned-node counts
   at 1–2, the claim always fits and there is no `DEFERRED` case or size-driven fallback, so
   nothing in this design calls it — and nothing outside pos-security-service calls it today
   either. It should be removed rather than left as a third way to ask an authorization
   question.

### What this does not decide

- Whether `is_primary` should imply a default location for endpoints that currently require
  an explicit `locationId`. UX question, deliberately out of scope.
- **Scoped administration.** Assignment at a parent node confers the right to *call*
  location-scoped APIs against descendants. Whether it should also confer the right to *grant*
  scope within that subtree is a separate concern, on the role-assignment surface rather than
  the 77 endpoints, and is where privilege escalation would live. Not addressed here.
- **Node granularity for irregular coverage.** Multi-node assignment covers a set that does not
  fit one subtree. Whether such cases should instead get a dedicated group node — keeping
  assignment at one node — is a modelling question for #1876. Note the multi-dimensional model
  already offers a third option: a second `LocationParent` row on a different `parent_type`.
- **Per-role dimension seeding.** Every seeded role needs a recorded `location_hierarchy` value.
  `INVENTORY_CONTROLLER` is an inventory role, not an accounting one, and must not be swept into
  `FINANCIAL` by a name match on "CONTROLLER".

---

## 6. Follow-up issues

| Issue | Work | Size | Depends on |
| --- | --- | --- | --- |
| [#1867](https://github.com/louisburroughs/durion-positivity-backend/issues/1867) | Consume staffing-assignment events in pos-security-service; maintain `person_id → assigned node ids` projection | M | — |
| [#1868](https://github.com/louisburroughs/durion-positivity-backend/issues/1868) | Add `roles.location_scope` + `location_hierarchy`; issue additive `loc_fin_bits` / `loc_oth_bits` + discriminated `loc_scope`; no `CATALOG_VERSION` bump | M | #1867 |
| [#1869](https://github.com/louisburroughs/durion-positivity-backend/issues/1869) | Gateway passthrough of the scope bitsets and `loc_scope`; strip inbound copies | S | #1868 |
| [#1870](https://github.com/louisburroughs/durion-positivity-backend/issues/1870) | Shared `LocationScope.covers(permission, locationId)` helper in `pos-security-common`, ancestor-set aware | S | #1869, #1878 |
| [#1871](https://github.com/louisburroughs/durion-positivity-backend/issues/1871) | Enforce at the demand cases: workorder WIP, inventory adjustment approval, people time-entry approval | M | #1870 |
| [#1872](https://github.com/louisburroughs/durion-positivity-backend/issues/1872) | Roll enforcement across the remaining location-parameterised endpoints | L | #1871, #1876 |
| [#1873](https://github.com/louisburroughs/durion-positivity-backend/issues/1873) | Clamp access-token `exp` to earliest contributing assignment expiry | S | ships with #1868 |
| [#1874](https://github.com/louisburroughs/durion-positivity-backend/issues/1874) | Revoke live tokens on staffing-assignment change; decide Redis-unavailable policy | M | #1867, #1873 |
| [#1875](https://github.com/louisburroughs/durion-positivity-backend/issues/1875) | Remove `role_assignments.scope_type`, `role_assignment_scope_locations` and `GET /v1/roles/check-permission` | M | #1872 |
| [#1876](https://github.com/louisburroughs/durion-positivity-backend/issues/1876) | Decide node granularity for irregular coverage: multi-node assignment vs. group nodes | S | — |
| [#1878](https://github.com/louisburroughs/durion-positivity-backend/issues/1878) | Materialise a location ancestor set onto `ExtLocationReplica` via location events | M | — |

All eleven are sub-issues of #1375. Sizes: S ≤ 1 day, M 2–4 days, L 1–2 weeks.

#1878 is on the critical path: without a replicated ancestor set there is no way to evaluate
"L is beneath an assigned node" at check time, and hierarchy is what supplies the middle tier.

---

## 7. Reproducing the evidence

Token sizes:

```bash
python3 scripts/measure-scope-claim-size.py
```

Endpoint inventory (77 endpoints) — parses each mapping method's parameter list rather than
grepping annotations, per the under-reporting caution in #1375:

```bash
python3 - <<'PY'
import re, glob, collections

def params_of(src, i):
    d = 0
    for j in range(i, len(src)):
        if src[j] == '(':
            d += 1
        elif src[j] == ')':
            d -= 1
            if d == 0:
                return src[i + 1:j]
    return ''

rows = []
for f in sorted(glob.glob('pos-*/src/main/java/**/*Controller.java', recursive=True)):
    src = open(f, encoding='utf-8', errors='replace').read()
    for m in re.finditer(r'@(Get|Post|Put|Patch|Delete)Mapping', src):
        md = re.search(r'\n\s+public\s+[^;{]*?\b(\w+)\s*\(', src[m.start():m.start() + 4000])
        if not md:
            continue
        ptext = params_of(src, m.start() + md.end() - 1)
        if not re.search(r'\blocationId\b', ptext):
            continue
        back = src[max(0, m.start() - 3000):m.start() + md.end()]
        pas = re.findall(r'@PreAuthorize\(\s*"([^"]*)"', back)
        rows.append((f.split('/')[0], m.group(1).upper(), md.group(1), pas[-1] if pas else None))

print('endpoints:', len(rows), ' without @PreAuthorize:', sum(1 for r in rows if not r[3]))
for k, v in collections.Counter(r[0] for r in rows).most_common():
    print(f'  {v:3d}  {k}')
PY
```
