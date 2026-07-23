package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import java.util.List;
import org.jspecify.annotations.NonNull;

/**
 * Single posting path for the inventory ledger (issue #1024, odoo-parity A1).
 *
 * <p>Every {@link InventoryLedgerEntry} append MUST flow through this service:
 * it persists the entry and maintains the {@code inventory_stock_summary}
 * derived balance row for the entry's (stockItemId, locationId) key in the
 * same transaction. Writing ledger entries directly through
 * {@code InventoryLedgerEntryRepository} bypasses the summary and causes
 * drift (caught by {@code StockSummaryDriftVerifier} and forbidden by the
 * {@code ArchitectureTest} funnel rule).
 *
 * <p>Since odoo-parity K1 (issue #1027) the funnel is also the single
 * enforcement point of the per-event-type negative-stock policy matrix (see
 * {@code NegativeStockPolicy} and {@code docs/negative-stock-policy.md}): a
 * posting that would take a key's on-hand below zero is rejected for blocked
 * and floor-at-zero event types. Other validation, {@code quantityAfter}
 * computation, and fact publishing remain the caller's responsibility.
 *
 * <p>The {@code negativeStockOverride} variants exist for the single
 * overridable row of the matrix (SCRAP_OUT): the caller performs its own
 * {@code inventory:adjustment:override} permission check and passes the
 * explicit flag — the funnel itself never consults the security context.
 */
public interface LedgerPostingService {

    /** Appends one ledger entry and upserts its summary row transactionally. */
    @NonNull
    InventoryLedgerEntry post(@NonNull InventoryLedgerEntry entry);

    /**
     * Same as {@link #post(InventoryLedgerEntry)} but with an explicit
     * negative-stock override flag for {@code BLOCKED_OVERRIDABLE} event
     * types. Callers must only pass {@code true} after verifying the actor
     * holds {@code inventory:adjustment:override}.
     */
    @NonNull
    InventoryLedgerEntry post(@NonNull InventoryLedgerEntry entry, boolean negativeStockOverride);

    /**
     * Appends the entries in order and upserts the affected summary rows
     * transactionally. Returns the saved entries in input order.
     */
    @NonNull
    List<InventoryLedgerEntry> postAll(@NonNull List<InventoryLedgerEntry> entries);

    /**
     * Same as {@link #postAll(List)} but with an explicit negative-stock
     * override flag applying to every {@code BLOCKED_OVERRIDABLE} entry in
     * the batch. Callers must only pass {@code true} after verifying the
     * actor holds {@code inventory:adjustment:override}.
     */
    @NonNull
    List<InventoryLedgerEntry> postAll(@NonNull List<InventoryLedgerEntry> entries, boolean negativeStockOverride);
}
