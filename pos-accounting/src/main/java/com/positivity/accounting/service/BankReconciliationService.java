package com.positivity.accounting.service;

import com.positivity.accounting.internal.dto.BankReconciliationImportRequest;
import com.positivity.accounting.internal.dto.BankReconciliationListResponse;
import com.positivity.accounting.internal.dto.BankReconciliationResponse;
import com.positivity.accounting.internal.dto.ReconciliationAdjustmentRequest;
import com.positivity.accounting.internal.dto.ReconciliationAuditResponse;
import com.positivity.accounting.internal.dto.ReconciliationMatchRequest;
import com.positivity.accounting.internal.dto.ReconciliationReportResponse;
import com.positivity.accounting.internal.dto.ReconciliationUnmatchRequest;
import com.positivity.accounting.internal.enums.ReconciliationStatus;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Pageable;

/**
 * Manual CSV bank reconciliation (Story F2, issue #965, decisions D-5/D-6).
 *
 * <p>Import a bank statement CSV for a reconcilable GL cash account, match statement
 * lines to posted GL journal-entry lines, record adjustments (which post real JEs
 * through the accounting-period gate), and finalize only when the statement and GL
 * ending balances agree (difference within ±0.01).
 */
public interface BankReconciliationService {

    /** Import a statement CSV and start a reconciliation (status IN_PROGRESS). */
    BankReconciliationResponse importStatement(@NonNull BankReconciliationImportRequest request);

    /** Get one reconciliation with its lines and adjustments. */
    BankReconciliationResponse get(@NonNull UUID reconciliationId);

    /** List reconciliations with optional glAccountId/status filters, paginated. */
    BankReconciliationListResponse list(
            @Nullable UUID glAccountId, @Nullable ReconciliationStatus status, @NonNull Pageable pageable);

    /** Match statement lines to posted GL journal-entry lines (1-to-1 or N-to-1). */
    BankReconciliationResponse match(@NonNull UUID reconciliationId, @NonNull ReconciliationMatchRequest request);

    /** Reverse a match, returning its lines to UNMATCHED. */
    BankReconciliationResponse unmatch(@NonNull UUID reconciliationId, @NonNull ReconciliationUnmatchRequest request);

    /** Record an adjustment and post its real balanced JE. */
    BankReconciliationResponse addAdjustment(
            @NonNull UUID reconciliationId, @NonNull ReconciliationAdjustmentRequest request);

    /** Finalize a balanced reconciliation (IN_PROGRESS to FINALIZED). */
    BankReconciliationResponse finalizeReconciliation(@NonNull UUID reconciliationId);

    /** Reconciliation report: balances, matched vs outstanding, adjustments, difference. */
    ReconciliationReportResponse report(@NonNull UUID reconciliationId);

    /** Audit trail of a reconciliation's actions. */
    ReconciliationAuditResponse audit(@NonNull UUID reconciliationId);
}
