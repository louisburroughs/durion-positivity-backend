package com.positivity.warranty.internal.service;

import com.positivity.warranty.internal.dto.SettlementReconciliationRow;
import java.util.List;
import org.jspecify.annotations.NonNull;

/**
 * Read-only reconciliation of FAILED pos-invoice-writing settlements (issue #922 item 2).
 *
 * <p>A settlement is marked FAILED when the pos-invoice call failed in transport — but
 * pos-invoice may have committed before the timeout (timeout-after-commit). This service checks
 * each FAILED adjustment/refund settlement against pos-invoice by its {@code externalReference}
 * (the settlement id) and reports whether the write actually landed, so staff retry only the
 * truly-absent ones instead of double-paying.
 */
public interface SettlementReconciliationService {

    /** Builds the worklist; never throws on a per-settlement pos-invoice failure (UNKNOWN row). */
    @NonNull
    List<SettlementReconciliationRow> reconcileFailedSettlements();
}
