# ADR-0057: Availability and On-Hand Are Different Questions, With Different Permissions

**Status:** ACCEPTED **Date:** 2026-08-24 **Deciders:** Architecture, Backend Lead, Inventory Domain
**Affected Issues:** durion-positivity-backend#1494

---

## Context

`inventory:availability:read` (permission bit 311) is seeded to ADMIN, LOCATION_MANAGER,
SERVICE_ADVISOR and TECHNICIAN, and is enforced by no endpoint. The availability endpoints instead
require `inventory:on_hand:view` / `inventory:on_hand:search`, which only ADMIN and INVENTORY_LEAD
hold. Issue #1494 found the consequence: a technician holds a permission named for reading
availability, that permission grants nothing, and the technician cannot read availability.

The bug is a symptom. The underlying problem is that the platform never wrote down what
*availability* means as distinct from *on-hand*, so the two permission families drifted into meaning
whatever the endpoint that happened to be written last needed. The computation has been defined since
ADR-0001 (`pos-inventory/docs/inventory-ledger-atp.md`); the *authority* over each has not.

Two facts about the domain force the distinction to be real rather than cosmetic:

1. **Availability is a function of on-hand plus commitments.** ATP subtracts hard allocations, and —
   on the per-location projection — soft reservations, plus expired-but-ACTIVE lot on-hand. The
   forecast fields add open purchase-order and ASN supply and subtract open reservation and pick-task
   demand. A caller reading availability is asking *can I promise this*, not *what is physically on
   the shelf*.
2. **Availability answers can span locations.** An availability read that enumerates each location
   discloses where stock sits across the estate. That is the same disclosure `inventory:on_hand:search`
   exists to gate, and it is a strictly wider disclosure than a single netted number for one scope.

---

## Decision

### 1. The two families name two different questions

**Decision:** ✅ **Resolved**

| Family | Question it answers | What it reads |
| --- | --- | --- |
| `inventory:on_hand:*` | *What is physically there?* | The stock record itself — counted quantity, lot and serial detail, location contents, ledger-derived counts, rollups. Uncommitted-for. |
| `inventory:availability:*` | *What can I promise, and when?* | The derived projection — on-hand **net of prior commitments** (allocations, reservations, expired lots), plus incoming/outgoing forecast. |

Neither family implies the other. On-hand is the raw input; availability is the answer computed from
it. Holding `inventory:on_hand:view` does not confer authority to read availability, and holding
`inventory:availability:read` does not confer authority to read the raw stock record, lot detail, or
location contents.

The practical reading: a technician or service advisor needs to know whether a part can be committed
to the job in front of them. They do not need — and under least privilege should not have — the
inventory record itself. A parts clerk counting and putting away stock needs the record. These are
different jobs, and they now carry different grants.

### 2. Scope, not just subject, decides the permission

**Decision:** ✅ **Resolved** — availability splits on cross-location disclosure the same way on-hand
does.

| Permission | Bit | Grants |
| --- | --- | --- |
| `inventory:availability:read` | 311 | The **scope-limited** availability answer: one aggregated view for a SKU, optionally narrowed to a location or storage location. Returns quantities, never a per-location enumeration. |
| `inventory:availability:search` | 470 | The **cross-location** availability answer: the per-location breakdown that enumerates every location holding the SKU. |

`inventory:availability:read` alone therefore tells a caller *whether* a SKU can be promised, and — if
they name a location — whether it can be promised **there**. It never tells them *where else* it is.
That is what `:search` is for, and it mirrors `inventory:on_hand:view` vs `inventory:on_hand:search`
exactly.

Note the asymmetry this deliberately preserves: an unscoped `read` returns a single number aggregated
over every location, which discloses *that stock exists somewhere* but not *where*. That is the
intended floor for a technician. Enumeration is the disclosure that matters, and enumeration requires
`:search`.

### 3. Endpoint mapping

**Decision:** ✅ **Resolved**

| Operation | Path | Requires |
| --- | --- | --- |
| `getAvailabilityBySku` | `GET /v1/inventory/availability/by-sku` | `inventory:availability:read` |
| `listAvailabilityBySku` | `GET /v1/inventory/availability` | `inventory:availability:read` |
| `getInventoryLeadTime` | `GET /v1/inventory/availability/lead-time` | `inventory:availability:read` |
| `getAvailabilityByProduct` | `GET /v1/inventory/availability/{productId}` | `inventory:availability:search` |
| `updateInventoryAvailability` | `POST /v1/inventory/availability/{productId}` | unchanged (`inventory:adjustment:create` / `:approve`; returns 501 by design) |

Everything that reads the stock record rather than the projection keeps its `inventory:on_hand:*`
gate: `getLocationInventory`, `listLocationInventoryItems`, the site and location rollups, the lot and
serial-unit reads, the replenishment evaluations and the purchase suggestions.

The `asOf` historical variants keep their **additional** `inventory:ledger:view` requirement. As-of
reads expose ledger history and are gated on that history, independently of this ADR.

### 4. Roles are re-granted to match

**Decision:** ✅ **Resolved**

The grants move exactly as far as the gate change forces and no further. Every role that can reach
an availability endpoint today keeps that reach, and no role gains one it did not have.

- `inventory:availability:read` — ADMIN, LOCATION_MANAGER, SERVICE_ADVISOR, TECHNICIAN (unchanged),
  **plus INVENTORY_LEAD**. INVENTORY_LEAD holds `inventory:on_hand:view` and `:search` and reaches the
  aggregated availability reads through them today; without the new grant the gate change would take
  that away.
- `inventory:availability:search` — **ADMIN and INVENTORY_LEAD only**. These are precisely the roles
  holding `inventory:on_hand:search` today, so this is a like-for-like swap on the per-location
  breakdown. Deliberately **not** TECHNICIAN, SERVICE_ADVISOR or LOCATION_MANAGER: none of them can
  enumerate stock across locations today, and enumerating the estate is not a shop-floor need. A site
  manager who should see it can be granted the code without touching the on-hand family — which is
  the point of splitting them.

INVENTORY_CONTROLLER and INVENTORY_MANAGER are untouched. They hold adjustment authority but no
on-hand or availability reads today, and this ADR is not the place to widen them.

Bit 311 is kept rather than retired. It was already seeded and already in every catalog; retiring and
re-minting it would churn every JWT for no gain now that it enforces something.

---

## Consequences

**A granted permission now means something.** Issue #1494's specific hole closes: TECHNICIAN holds
`inventory:availability:read` and can read availability with it.

**On-hand holders no longer reach availability implicitly.** ADMIN and INVENTORY_LEAD lose nothing in
practice because §4 grants them the new codes explicitly, but any *other* caller that reached the
availability endpoints on an `inventory:on_hand:*` grant alone will now receive 403. This is the
intended tightening: the permission was never a statement about availability. Callers outside the
seeded roles must be re-granted.

**The MCP inventory facade widens by one code.** `InventoryFacadeTool`'s `checkStock` routes to
`getAvailabilityBySku`, so `inventory:availability:read` joins its OR-set. Tool visibility is
fail-closed and union-based, so this only makes the tool reachable for roles that could already
legitimately answer the question.

**Catalog version bumps to 59.** Bit 470 is new, so `PermissionCode`, `GatewayPermissionCatalog` and
`DownstreamPermissionCatalog` move together and tokens minted under 58 are re-issued on the next login.

**The audit #1494 asked for is still open.** This ADR fixes the one permission it found. The broader
sweep — every permission granted in the seed and required by no operation — remains worth running, and
the catalogs make it mechanically checkable.

---

## Alternatives Considered

**Retire bit 311 and grant TECHNICIAN `inventory:on_hand:view`.** Rejected. It answers the 403 and
leaves the definition unwritten: the technician would then hold authority over the stock record —
lot detail, location contents, rollups — to answer a question about commitment. That is the wrong
grant, and it would make on-hand mean "availability too" permanently.

**Point every availability endpoint at bit 311 and stop there.** Rejected. It fixes the technician but
hands TECHNICIAN and SERVICE_ADVISOR the per-location breakdown for every site, which is exactly the
cross-location visibility `inventory:on_hand:search` exists to withhold.

**Accept `inventory:on_hand:*` as an alternative on the availability endpoints (OR-set).** Rejected.
It is the smallest diff and preserves every existing caller, but "on-hand implies availability" is the
conflation this ADR exists to end. §4 re-grants the affected roles explicitly instead, which costs a
seed change and leaves the definition clean.

---

## References

- `pos-inventory/docs/inventory-ledger-atp.md` — the ATP and forecast computation this ADR gates
- ADR-0001 — inventory ledger ATP computation (soft reservations are not subtracted by `by-sku`)
- `docs/OPERATIONS_RUNBOOK.md` — permission registration
- Issue #1494 — `inventory:availability:read` granted but enforced nowhere
