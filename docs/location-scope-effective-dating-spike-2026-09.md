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

3. **A scope claim does not have to touch `perm_bits` at all.** The issue frames the cost
   around `PermissionCode` / `GatewayPermissionCatalog` / `PermissionBitsetCodec` /
   `CATALOG_VERSION` lockstep. That cost is only incurred by encodings that put location
   *inside* the permission bitset. A separate, additive `loc_scope` claim leaves the bitset,
   the catalog and the catalog version untouched, and is therefore backward compatible by
   construction — no flag-day deploy.

**Recommendation:** retire the pos-security-service scope model; source location reach from
pos-people; carry it as an additive flat `loc_scope` claim; enforce the intersection in the
owning service. Clamp token lifetime to assignment expiry and revoke on assignment change.

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
resolvable ids inherits a bootstrapping problem **in pos-security-service**. But that
problem is an artefact of choosing the wrong owner: `ExtLocationReplica`, fed from location
events by `LocationEventsListener`, already gives pos-people, pos-inventory, pos-invoice and
pos-workorder a locally resolvable, event-consistent view of locations. Location ids are a
solved problem everywhere except the service that was assumed to own scope.

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

## 3. Encoding options and measured token sizes

Measured with `scripts/measure-scope-claim-size.py`, which reproduces
`PermissionBitsetCodec.encode` exactly (Java `BitSet.toByteArray()` little-endian bit order
within bytes, Base64URL unpadded) and builds real JWS compact serialisations with the claim
set from `JwtServiceImpl.generateTokenPair`.

**Corrections to the issue's premises:** `CATALOG_VERSION` is **76**, not 56
(`PermissionCode.java:953`); the catalog holds **510** codes with max bit index 509, so a
full bitset is 64 bytes → **86** Base64URL characters. Baseline access token ≈ **650 bytes**.

Sizes in bytes, ADMIN-like profile (387 permissions), by number of scoped locations:

| Encoding | 0 | 1 | 5 | 25 | 100 | 500 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| A — status quo, no scope | 653 | 653 | 653 | 653 | 653 | 653 |
| B — `loc_scope`, UUID strings | 681 | 724 | 932 | 1 972 | 5 872 | 26 672 |
| **C — `loc_scope`, packed Base64URL** | **681** | **703** | **816** | **1 385** | **3 519** | **14 896** |
| D — per-location bitset map | 653 | 848 | 1 531 | 4 944 | 17 744 | **86 011 ✗** |
| E — role → locations map | 709 | 752 | 960 | 2 000 | 5 900 | 26 700 |

✗ exceeds `server.tomcat.max-http-header-size: 65536`. Token size is near-independent of
permission count (the bitset is ≤ 86 chars) and linear in location count — so **location
cardinality is the only size risk**, and option D is the only one that can breach the cap.

### Why C, and why the cost framing in Q7 does not apply to it

Q7 estimates blast radius as `PermissionCode` + `GatewayPermissionCatalog` (lockstep
`CATALOG_VERSION`) + `PermissionBitsetCodec` + gateway decode + **1 086 `@PreAuthorize`
annotations across 308 files in 23 gateway-secured modules**. That estimate is correct **for
option D**, which redefines what a permission bit means.

Option C incurs none of it. `loc_scope` is a new, additive claim:

- `perm_bits` semantics unchanged → **no `CATALOG_VERSION` bump** (also an explicit non-goal).
- `PermissionBitsetCodec`, `PermissionCode`, `GatewayPermissionCatalog` untouched.
- Existing `@PreAuthorize` annotations keep working unchanged; they answer "may this caller
  do X", which stays true. Scope answers "…here", which is a separate, second check applied
  only at the 77 endpoints that take a `locationId`.
- A service that does not yet read `loc_scope` behaves exactly as today — so rollout is
  per-endpoint and reversible.

**This answers Q8: there is no flag-day.** The migration path is additive claim → gateway
passthrough header → per-module adoption, in that order, each step independently deployable.

### Cardinality cap

At 25 locations option C costs 1 385 bytes — unremarkable. At 500 it costs 14.9 KB, which
fits the raised header cap but is heavy on every request. Recommend a configured cap
(start at 64 locations ≈ 2 KB): above it the issuer emits `"loc_scope":"GLOBAL"` when the
caller genuinely holds an all-locations grant, otherwise `"loc_scope":"DEFERRED"`, and a
service seeing `DEFERRED` falls back to `GET /v1/roles/check-permission`. This keeps the
common case free and bounds the worst case, rather than assuming enterprise cardinality
never happens.

### Q2 — cost of the catalog-encoding alternative

357 of the 445 parseable permission codes belong to location-touching domains. Encoding
scope as `*_all_locations` twins implies up to that many new codes against a 510-code
catalog — more than doubling it — and per §1a still would not enforce the narrow case. Not
recommended.

---

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
2. **Make pos-people `EmployeeLocationAssignment` the single source of truth** for a person's
   location reach.
3. **Carry reach in the token as an additive `loc_scope` claim** (encoding C), sourced by
   pos-security-service from the staffing-assignment events pos-people already publishes,
   with the cardinality cap and `GLOBAL`/`DEFERRED` discriminators.
4. **Enforce the intersection in the owning service** at the 77 endpoints, starting with the
   demand cases: workorder WIP, inventory adjustment approval, people time-entry approval.
   The gateway stays a coarse gate; it has no domain knowledge of which parameter is a
   location.
5. **Clamp `exp` to assignment expiry** and revoke on assignment change.
6. **Keep `check-permission`** as the `DEFERRED` fallback only. It is not a general answer:
   nothing calls it today, and putting a synchronous pos-security-service hop on 77 endpoints
   would couple availability and add latency to the common path.

### What this does not decide

- Whether `is_primary` should imply a default location for endpoints that currently require
  an explicit `locationId`. UX question, deliberately out of scope.
- Location hierarchy. `LocationController.getDescendants` / `getAllChildren` imply a tree, so
  "covers location L" may need to mean "covers L or an ancestor of L". Flagged as a
  follow-up; the recommendation is unaffected because the claim carries ids either way.

---

## 6. Follow-up issues

| Issue | Work | Size | Depends on |
| --- | --- | --- | --- |
| [#1867](https://github.com/louisburroughs/durion-positivity-backend/issues/1867) | Consume staffing-assignment events in pos-security-service; maintain `person_id → location_id[]` projection | M | — |
| [#1868](https://github.com/louisburroughs/durion-positivity-backend/issues/1868) | Issue additive `loc_scope` claim with cardinality cap and `GLOBAL`/`DEFERRED` discriminators; no `CATALOG_VERSION` bump | M | #1867 |
| [#1869](https://github.com/louisburroughs/durion-positivity-backend/issues/1869) | Gateway passthrough of `loc_scope` as `X-Loc-Scope`; strip inbound copies | S | #1868 |
| [#1870](https://github.com/louisburroughs/durion-positivity-backend/issues/1870) | Shared `LocationScope.covers(locationId)` helper in `pos-security-common` | S | #1869 |
| [#1871](https://github.com/louisburroughs/durion-positivity-backend/issues/1871) | Enforce at the demand cases: workorder WIP, inventory adjustment approval, people time-entry approval | M | #1870 |
| [#1872](https://github.com/louisburroughs/durion-positivity-backend/issues/1872) | Roll enforcement across the remaining location-parameterised endpoints | L | #1871, #1876 |
| [#1873](https://github.com/louisburroughs/durion-positivity-backend/issues/1873) | Clamp access-token `exp` to earliest contributing assignment expiry | S | ships with #1868 |
| [#1874](https://github.com/louisburroughs/durion-positivity-backend/issues/1874) | Revoke live tokens on staffing-assignment change; decide Redis-unavailable policy | M | #1867, #1873 |
| [#1875](https://github.com/louisburroughs/durion-positivity-backend/issues/1875) | Remove `role_assignments.scope_type` and `role_assignment_scope_locations` | M | #1872 |
| [#1876](https://github.com/louisburroughs/durion-positivity-backend/issues/1876) | Decide location-hierarchy semantics for "covers" | S | — |

All ten are sub-issues of #1375. Sizes: S ≤ 1 day, M 2–4 days, L 1–2 weeks.

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
