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

3. **The claim does not have to touch `perm_bits` at all, and is constant-size.** The issue
   frames the cost around `PermissionCode` / `GatewayPermissionCatalog` /
   `PermissionBitsetCodec` / `CATALOG_VERSION` lockstep. That cost is only incurred by
   encodings that put location *inside* the permission bitset. Two additive claims —
   `loc_bits` (which permissions are location-limited, reusing the same bit indexes) and
   `loc_scope` (the one location the caller occupies) — leave the bitset semantics, the
   catalog and the catalog version untouched. Worst case **+202 bytes**, 1.31% of the header
   budget, and it never grows.

**Recommendation:** retire the pos-security-service scope model; put `location_scope`
(`ALL` | `LOCATION`) on the role; source the caller's single location from pos-people; carry
both as additive claims; enforce in the owning service. Clamp token lifetime to assignment
expiry and revoke on assignment change.

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

## 3. Claim shape and measured token size

Two platform rules constrain the design, and together they collapse most of the space Q3
anticipates:

1. **`location_scope` is a property of the role** (`ALL` | `LOCATION`), not of the assignment
   and not of the employee.
2. **A location-scoped employee occupies exactly one location.** Reach is never an enumerated
   set.

Rule 1 is what #1373 was reaching for. INVENTORY_MANAGER and INVENTORY_CONTROLLER hold
identical grants on purpose; the difference between them is that one role is `LOCATION`-scoped
and the other is `ALL`. Putting the discriminator on the role expresses that directly.

### Ownership split

| Question | Owner |
| --- | --- |
| Is this role location-scoped? | pos-security-service — new `roles.location_scope` |
| Which single location does this employee occupy? | pos-people — primary `employee_location_assignment` |

This preserves §2's conclusion: pos-people still owns user→location. Only the scope
discriminator sits with the roles, where roles already live.

### Claims

Two claims, because a user may hold both a `LOCATION`-scoped and an `ALL`-scoped role and the
latter must not silently widen the former:

| Claim | Value |
| --- | --- |
| `perm_bits` | **unchanged** — every permission the caller holds |
| `loc_bits` | the subset of `perm_bits` granted *only* by `LOCATION`-scoped roles |
| `loc_scope` | the single location UUID the caller occupies; omitted when `loc_bits` is empty |

`loc_bits` reuses `PermissionBitsetCodec` and the same bit indexes as `perm_bits`, so it is
covered by the existing `perm_ver` and **requires no `CATALOG_VERSION` bump**.

**Enforcement rule.** At an endpoint checking permission `P` for location `L`:

```
if P ∉ loc_bits        → allow (the grant is global)
else if L == loc_scope → allow
else                   → deny
```

**Union semantics.** A permission granted by both a `LOCATION` role and an `ALL` role is
global — its bit is *not* set in `loc_bits`. The broader grant wins, which is standard RBAC
union behaviour and keeps the claim consistent with how `perm_bits` already composes.

**Fail closed.** A caller holding `LOCATION`-scoped roles but with no resolvable primary
location gets `loc_bits` set and `loc_scope` absent, which denies. Absence must never widen to
unrestricted reach. `V3__backfill_primary_location_assignments.sql` records that employees
with several active assignments and no primary exist and are "genuinely ambiguous" — that
population is exactly this case.

### Measured sizes

**Corrections to the issue's premises:** `CATALOG_VERSION` is **76**, not 56
(`PermissionCode.java:953`); the catalog holds **510** codes with max bit index 509, so a
full bitset is 64 bytes → **86** Base64URL characters. Baseline access token ≈ **650 bytes**.

Measured with `scripts/measure-scope-claim-size.py`, which reproduces
`PermissionBitsetCodec.encode` exactly (Java `BitSet.toByteArray()` little-endian bit order
within bytes, Base64URL unpadded) and builds real JWS compact serialisations with the claim
set from `JwtServiceImpl.generateTokenPair`:

| Permission profile | unscoped | half scoped | all scoped | worst delta |
| --- | ---: | ---: | ---: | ---: |
| DISPATCHER-like (11) | 643 | 823 | 833 | +190 |
| SHOP_MANAGER-like (17) | 648 | 835 | 844 | +196 |
| CONTROLLER-like (52) | 651 | 848 | 849 | +198 |
| ADMIN-like (387) | 653 | 855 | 855 | +202 |
| whole catalog (510) | 653 | 855 | 855 | +202 |

**Worst case 855 B against a 65 514 B header budget — 1.31%.** Both claims are constant-size:
`loc_bits` is bounded by the catalog at 86 characters and `loc_scope` is one UUID. Token size
never varies with how many locations exist.

### What single-valued reach removes from the design

Q3 asks for a worst-case measurement on the assumption that "a user scoped to many locations
is the bad case". That case does not exist. Consequently:

- **No cardinality cap** — nothing to configure or tune.
- **No `DEFERRED` state**, and therefore no per-request fallback to
  `GET /v1/roles/check-permission`. Nothing in this design calls it, so `check-permission`
  has no remaining role at all — see §5.
- **No packed or compact location encoding.** One UUID is 36 characters as a plain string
  versus 22 packed; 14 bytes does not justify an opaque claim.
- **The per-location bitset map is moot**, and with it the entire `PermissionCode` /
  `GatewayPermissionCatalog` / `PermissionBitsetCodec` / `CATALOG_VERSION` lockstep cost.

`scripts/measure-scope-claim-size.py` retains the set-valued measurements as recorded
rationale — a set-valued claim would have cost 1 385 B at 25 locations, and the per-location
bitset map would have breached `max-http-header-size` at 500 — but they are not implemented.

### Q7 — the cost framing does not apply

Q7 estimates blast radius as `PermissionCode` + `GatewayPermissionCatalog` (lockstep
`CATALOG_VERSION`) + `PermissionBitsetCodec` + gateway decode + **1 086 `@PreAuthorize`
annotations across 308 files in 23 gateway-secured modules**. That is correct only for
encodings that redefine what a permission bit means.

This design incurs none of it:

- `perm_bits` semantics unchanged, and `loc_bits` reuses the same indexes → **no
  `CATALOG_VERSION` bump** (also an explicit non-goal).
- `PermissionBitsetCodec` is reused as-is; `PermissionCode` and `GatewayPermissionCatalog`
  are untouched.
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
worse than `roles.location_scope`, which expresses the same distinction once per role instead
of once per permission. Not recommended.

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
3. **Make pos-people the source of the caller's single location** — the primary
   `employee_location_assignment`, joined via the `personId` claim the token already carries.
4. **Carry two additive claims**, `loc_bits` and `loc_scope` (§3). No `CATALOG_VERSION` bump,
   no change to `perm_bits` semantics, constant size.
5. **Enforce in the owning service** at the 77 endpoints, starting with the demand cases:
   workorder WIP, inventory adjustment approval, people time-entry approval. The gateway
   stays a coarse gate; it has no domain knowledge of which parameter is a location.
6. **Clamp `exp` to assignment expiry** and revoke on assignment change (§4).
7. **Retire `GET /v1/roles/check-permission`.** With reach single-valued there is no
   `DEFERRED` case and no fallback, so nothing in this design calls it — and nothing outside
   pos-security-service calls it today either. It should be removed rather than left as a
   third way to ask an authorization question.

### What this does not decide

- Whether `is_primary` should imply a default location for endpoints that currently require
  an explicit `locationId`. UX question, deliberately out of scope.
- **Location hierarchy — now load-bearing, not optional.** With reach limited to one location,
  there is no middle tier: a caller is confined to a single shop or is `ALL`. A manager over
  three of ten shops has no representation unless "covers L" means "covers L or a descendant
  of L" and they are pointed at a parent node. `LocationController.getDescendants` /
  `getAllChildren` show the tree exists. Either transitive semantics supply the middle tier or
  the model deliberately has none (defensible if the business is shop staff vs. head office).
  This must be decided before enforcement rolls out widely — #1876.

---

## 6. Follow-up issues

| Issue | Work | Size | Depends on |
| --- | --- | --- | --- |
| [#1867](https://github.com/louisburroughs/durion-positivity-backend/issues/1867) | Consume staffing-assignment events in pos-security-service; maintain `person_id → primary location_id` projection | M | — |
| [#1868](https://github.com/louisburroughs/durion-positivity-backend/issues/1868) | Add `roles.location_scope`; issue additive `loc_bits` + `loc_scope` claims; no `CATALOG_VERSION` bump | M | #1867 |
| [#1869](https://github.com/louisburroughs/durion-positivity-backend/issues/1869) | Gateway passthrough of `loc_bits` / `loc_scope`; strip inbound copies | S | #1868 |
| [#1870](https://github.com/louisburroughs/durion-positivity-backend/issues/1870) | Shared `LocationScope.covers(locationId)` helper in `pos-security-common` | S | #1869 |
| [#1871](https://github.com/louisburroughs/durion-positivity-backend/issues/1871) | Enforce at the demand cases: workorder WIP, inventory adjustment approval, people time-entry approval | M | #1870 |
| [#1872](https://github.com/louisburroughs/durion-positivity-backend/issues/1872) | Roll enforcement across the remaining location-parameterised endpoints | L | #1871, #1876 |
| [#1873](https://github.com/louisburroughs/durion-positivity-backend/issues/1873) | Clamp access-token `exp` to earliest contributing assignment expiry | S | ships with #1868 |
| [#1874](https://github.com/louisburroughs/durion-positivity-backend/issues/1874) | Revoke live tokens on staffing-assignment change; decide Redis-unavailable policy | M | #1867, #1873 |
| [#1875](https://github.com/louisburroughs/durion-positivity-backend/issues/1875) | Remove `role_assignments.scope_type`, `role_assignment_scope_locations` and `GET /v1/roles/check-permission` | M | #1872 |
| [#1876](https://github.com/louisburroughs/durion-positivity-backend/issues/1876) | Decide location-hierarchy semantics for "covers" — **prerequisite**, supplies the middle tier | S | — |

All ten are sub-issues of #1375. Sizes: S ≤ 1 day, M 2–4 days, L 1–2 weeks.

#1876 is a prerequisite rather than a tail-end cleanup: with reach limited to one location it
is what decides whether a middle management tier can exist at all.

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
