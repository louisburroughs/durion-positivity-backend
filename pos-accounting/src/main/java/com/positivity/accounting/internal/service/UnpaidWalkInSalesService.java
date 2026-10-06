package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.UnpaidWalkInSalesResponse;
import org.jspecify.annotations.NonNull;

/**
 * The unpaid walk-in sales read (CAP:550 S11, #2508; SPEC-accounting-workspace §4.1, §4.4 item 2,
 * §9.5a; AW12): what is still owed on the CASH house account, and which walk-in invoices outlived their
 * business day. Read-only: it never posts and never changes an invoice; the tenant comes from the
 * security context and row-level security (ADR-0062).
 */
public interface UnpaidWalkInSalesService {

    /**
     * The CASH balance now, the open walk-in invoices oldest sale first, the day-end needs-attention item
     * and walk-in payments with money left unapplied. With no CASH party in the replica yet, it answers
     * zero with {@code houseAccountKnown = false}.
     */
    @NonNull
    UnpaidWalkInSalesResponse read();
}
