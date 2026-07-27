# Negative-Stock Policy Matrix

Story: odoo-parity K1 (issue #1027). Spec: `durion/domains/inventory/SPEC-pos-inventory-odoo-parity.md` §11.

## Where it is enforced

All `inventory_ledger_entry` appends funnel through `LedgerPostingService` /
`LedgerPostingServiceImpl` (odoo-parity A1, issue #1024). That funnel is the
**single enforcement point** of this matrix: before the batch's summary deltas
are applied, the entries are replayed in order per `(stockItemId, locationId)`
key against the row-locked `inventory_stock_summary` balances, and every
on-hand-affecting entry is checked against the matrix. A violation aborts the
whole posting transaction — ledger entries and summary rows roll back together.

A DB-level check is impractical against an append-only ledger, so the funnel is
asserted architecturally: the `ArchitectureTest` rule
`ledger_entry_writes_must_go_through_posting_funnel` forbids any class other
than `LedgerPostingServiceImpl` from calling
`InventoryLedgerEntryRepository.save/saveAll`.

## The matrix

| Event type(s) | Policy | Error code (422 `ApiError`) | Rationale |
| --- | --- | --- | --- |
| `GOODS_ISSUE`, `WORKORDER_CONSUMPTION` (PICK/ISSUE flows) | **Blocked** below zero | `INSUFFICIENT_STOCK` (`InsufficientStockException`) | You cannot physically pick, issue, or consume more stock than exists. Pre-K1 behavior for PICK/ISSUE, kept and generalized to the funnel. |
| `TRANSFER_OUT` (transfer dispatch) | **Blocked** below zero | `INSUFFICIENT_STOCK` (`InsufficientStockException`) | A dispatch is a physical outbound movement; shipping stock you do not have would silently fabricate in-transit inventory. |
| `SCRAP_OUT` | **Blocked, overridable** | `NEGATIVE_STOCK_OVERRIDE_REQUIRED` (`NegativeStockPolicyViolationException`) | Scrap records physical loss that the ledger may not know about yet (shrinkage found on the floor). A supervisor holding `inventory:adjustment:override` may force the write; the caller checks the permission and passes an explicit override flag — the funnel never reads the security context. |
| `ADJUSTMENT_OUT`, `COUNT_VARIANCE_OUT`, `ADJUST_CYCLE_COUNT` | **Floor at zero** (never overridable) | `NEGATIVE_STOCK_FLOOR_VIOLATION` (`NegativeStockPolicyViolationException`) | Counts and manual adjustments set reality: they may zero a balance, but a count can never observe *negative* physical stock, so driving the ledger below zero is by definition a data error. |
| `GOODS_RECEIPT`, `TRANSFER_IN`, `PUTAWAY`, `RETURN_TO_STOCK`, `ADJUSTMENT_IN`, `COUNT_VARIANCE_IN` | **Unconstrained** | — | Inbound flows only add stock. `PUTAWAY` writes paired entries (source decrement + destination increment) under one event type; the putaway services already validate source on-hand (`NoOnHandAtSourceLocationException`), so the matrix leaves the paired move unconstrained. |
| `RESERVATION_*`, `ALLOCATION_*`, `BACKORDER_*`, `PICK_TASK_*` | **Untouched** (not part of the matrix) | — | Neutral/ATP-only types never move physical on-hand; over-promising is an ATP concern (`INSUFFICIENT_ATP`), not a negative-stock one. |

Notes:

- "Below zero" is evaluated per `(stockItemId, locationId)` key — the same key
  the funnel uses for `inventory_stock_summary` deltas — with entries in a
  batch replayed sequentially, so a receipt earlier in the batch covers a later
  issue of the same key.
- Exactly-to-zero postings are always allowed, for every row of the matrix.
- `ADJUST_CYCLE_COUNT` is grouped with the count-variance types: it is the
  posted form of an approved cycle-count adjustment, and counts set reality.
- The override flag applies only to `BLOCKED_OVERRIDABLE` rows; passing it does
  not weaken `BLOCKED` or `FLOOR_AT_ZERO` rows.

## Code pointers

- Matrix: `com.positivity.inventory.internal.enums.NegativeStockPolicy`
- Enforcement: `LedgerPostingServiceImpl#enforceNegativeStockPolicy`
- Error mapping: `InventoryGlobalExceptionHandler` (422 with the codes above)
- Funnel assertion: `ArchitectureTest#ledger_entry_writes_must_go_through_posting_funnel`
- Tests: `NegativeStockPolicyEnforcementTest` (per-row coverage),
  `StockMovementContractBehaviorIT` (HTTP `ApiError` surface)
