# Allocation Consistency Sweep

Report-only scheduled verifier (`AllocationConsistencyVerifier`, odoo-parity K3, issue #1032) asserting that
the allocation tables, the inventory ledger, and the stock-summary read model agree. It is the Odoo
`_clean_reservations` analog **without the cleaning**: findings are logged and counted, never repaired
automatically. Corrections are **new ledger entries** per DECISION-INVENTORY-005 (the ledger is immutable and
append-only) — never edits or deletes.

## Configuration

| Property | Default | Meaning |
| --- | --- | --- |
| `pos.inventory.allocation-verify.interval-ms` | `3600000` | Delay between sweeps |
| `pos.inventory.allocation-verify.initial-delay-ms` | `600000` | Delay after startup before the first sweep |

Metric: `inventory.allocation.consistency.violations.total` (Micrometer counter, incremented by the number of
findings per pass). Per-finding WARN logs are capped at 50 per pass; the totals always appear in a summary
ERROR log.

## Invariant A — per allocation

Allocation ledger events carry the **allocation id** as `sourceTransactionId`
(`ReservationServiceImpl.writeAllocationLedgerEntry`, `ConsumptionServiceImpl.closeAllocations`). Both
`ALLOCATION_CREATED` and `ALLOCATION_RELEASED` are written with **positive** `changeInQuantity`, so
per-allocation outstanding is `Σcreated − Σreleased`. Expected footprint:

| allocationState / locationId / status | created | released | outstanding |
| --- | --- | --- | --- |
| SOFT (any status), or locationId null | 0 | 0 | 0 |
| HARD, located, ALLOCATED / PICKED | allocatedQuantity | 0 ≤ released < created | (0, allocatedQuantity] |
| HARD, located, RELEASED | allocatedQuantity | == created | 0 |
| Orphan ledger key (no allocation row) | — | — | must be 0 |

Only a located HARD promotion writes `ALLOCATION_CREATED` (exactly once, full quantity); cancel releases the
un-consumed remainder and consumption releases oldest-first, flipping the status to RELEASED only when fully
released. Partial consumption legitimately leaves outstanding strictly between 0 and `allocatedQuantity`, so
the spec's shorthand "∈ {0, allocatedQuantity}" is enforced as the per-state table above.

Typical findings and causes:

- **SOFT/unlocated allocation with ledger events** — something wrote allocation events outside the promote
  path, or an allocation was demoted after hardening.
- **HARD located allocation with `created ≠ allocatedQuantity`** — a missing promote event (created = 0), a
  duplicated promote, or a quantity change after hardening.
- **`released ≥ created` while status ≠ RELEASED** (or `released ≠ created` while RELEASED) — release/status
  drift between the allocation row and the ledger.
- **Orphan key with non-zero outstanding** — allocation events pointing at a deleted/foreign id (or written
  with a null `sourceTransactionId`).

## Invariant B — per location

Per location, `SUM(inventory_stock_summary.allocated)` must equal ledger outstanding
(`Σ ALLOCATION_CREATED − Σ ALLOCATION_RELEASED`) — the set-based twin of
`InventoryLedgerEntryRepository.calculateOutstandingAllocationsByLocation`. The summary column is maintained
only by `LedgerPostingServiceImpl` in the same transaction as the ledger append, so drift means a bypassing
write or a partially applied batch. Repair path for summary-side drift is
`StockSummaryRebuildService.rebuildFromLedger()` (see `docs/inventory-ledger-atp.md`); ledger-side gaps need a
compensating ledger entry.

## Investigating a finding

1. Pull the allocation row (`inventory_allocation`) and its ledger footprint:
   `SELECT * FROM inventory_ledger_entry WHERE source_transaction_id = '<allocationId>' AND event_type IN
   ('ALLOCATION_CREATED','ALLOCATION_RELEASED') ORDER BY timestamp`.
2. Cross-check the owning reservation (`inventory_reservation` via `reservation_id`) and any consumption notes
   on the release entries (they name the pick task and workorder).
3. Decide which side is right (the ledger is authoritative for quantity state; the allocation row is
   authoritative for lifecycle intent), then correct by **appending** the missing `ALLOCATION_CREATED` /
   `ALLOCATION_RELEASED` entry through `LedgerPostingService` (which also fixes the summary), or by fixing the
   allocation row's status — never by editing or deleting ledger rows.
4. For invariant-B-only findings with a clean invariant A, rebuild the summary in a quiet window.

Both checks run set-based in SQL and return only violating keys, so the sweep is safe at full catalog ×
location cardinality.
