package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.CashPositionResponse;
import org.jspecify.annotations.NonNull;

/**
 * Your cash (CAP:550 S19, #2515; SPEC-accounting-workspace §4.1; AW9): in the bank, waiting to be deposited and kept in
 * drawers, with what needs attention. Read-only: the tenant comes from the security context and row-level security
 * (ADR-0062), and every figure is computed on request from the ledger and the replicas.
 */
public interface CashPositionService {

    /**
     * The cash position now. The bank rows carry the latest statement's closing balance, the reconciled-through date,
     * the open outstanding items and the unmatched bank lines only for a caller holding {@code
     * accounting:reconciliation:view}; for any other caller those fields are absent.
     */
    @NonNull
    CashPositionResponse read();
}
