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
 * drift (caught by {@code StockSummaryDriftVerifier}, but still a bug).
 *
 * <p>This service only appends and maintains the summary — validation,
 * {@code quantityAfter} computation, and fact publishing remain the caller's
 * responsibility, exactly as before the funnel refactor.
 */
public interface LedgerPostingService {

    /** Appends one ledger entry and upserts its summary row transactionally. */
    @NonNull
    InventoryLedgerEntry post(@NonNull InventoryLedgerEntry entry);

    /**
     * Appends the entries in order and upserts the affected summary rows
     * transactionally. Returns the saved entries in input order.
     */
    @NonNull
    List<InventoryLedgerEntry> postAll(@NonNull List<InventoryLedgerEntry> entries);
}
