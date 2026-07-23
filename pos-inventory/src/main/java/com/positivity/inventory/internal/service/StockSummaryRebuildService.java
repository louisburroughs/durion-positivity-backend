package com.positivity.inventory.internal.service;

/**
 * Rebuilds the {@code inventory_stock_summary} read model from the ledger
 * (issue #1024, A1). The ledger is the source of truth; the summary is
 * disposable and fully reconstructible.
 */
public interface StockSummaryRebuildService {

    /**
     * Truncates the summary table and reconstructs every row from the ledger
     * in one transaction. Intended for backfill and disaster recovery; run it
     * in a quiet window — a posting that commits between truncate and
     * reinsert of the same key aborts the rebuild on the unique key (safe to
     * retry).
     *
     * @return number of summary rows written
     */
    int rebuildFromLedger();
}
