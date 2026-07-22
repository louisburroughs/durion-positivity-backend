package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import java.util.EnumSet;
import java.util.Set;

/**
 * Ledger event types that contribute to the {@code inventory_stock_summary}
 * read model (issue #1024, A1). Shared by the posting funnel, rebuild job,
 * and drift verifier so all three stay aligned.
 */
final class StockSummaryEventSets {

    /** Every event type that touches a summary column. */
    static final Set<InventoryLedgerEventType> SUMMARY_TYPES;

    static {
        EnumSet<InventoryLedgerEventType> types = EnumSet.copyOf(InventoryLedgerEventType.onHandAffectingTypes());
        types.add(InventoryLedgerEventType.ALLOCATION_CREATED);
        types.add(InventoryLedgerEventType.ALLOCATION_RELEASED);
        types.add(InventoryLedgerEventType.RESERVATION_CREATED);
        types.add(InventoryLedgerEventType.RESERVATION_RELEASED);
        SUMMARY_TYPES = Set.copyOf(types);
    }

    private StockSummaryEventSets() {}
}
