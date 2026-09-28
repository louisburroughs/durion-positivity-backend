package com.positivity.accounting.internal.bankrec.service;

import com.positivity.accounting.internal.bankrec.dto.BankReconciliationImportRequest;
import com.positivity.accounting.internal.bankrec.dto.BankReconciliationListResponse;
import com.positivity.accounting.internal.bankrec.dto.BankReconciliationResponse;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationAuditResponse;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationCreateRequest;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationReportResponse;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Pageable;

/**
 * The bank reconciliation header lifecycle (Story F2, issue #965; S4, #2303 — SPEC §3.7, §4.1, §6.1):
 * create from a COMMITTED statement, read with the live equation, list, finalize, report, audit.
 */
public interface BankReconciliationService {

    /** Start a reconciliation from a COMMITTED statement (status IN_PROGRESS); a replayed requestId returns it. */
    @NonNull
    BankReconciliationResponse create(@NonNull ReconciliationCreateRequest request);

    /** Import a statement CSV and start a reconciliation (F2; retired by story S3). */
    @NonNull
    BankReconciliationResponse importStatement(@NonNull BankReconciliationImportRequest request);

    /** One reconciliation header with its live terms. */
    @NonNull
    BankReconciliationResponse get(@NonNull UUID reconciliationId);

    /** Reconciliations matching the filter, paginated. */
    @NonNull
    BankReconciliationListResponse list(@NonNull ReconciliationListFilter filter, @NonNull Pageable pageable);

    /** Finalize a reconciliation whose live difference is within ±0.01 (IN_PROGRESS to FINALIZED). */
    @NonNull
    BankReconciliationResponse finalizeReconciliation(@NonNull UUID reconciliationId);

    /** Reconciliation report: E3 and opening terms, splits, adjustments, difference. */
    @NonNull
    ReconciliationReportResponse report(@NonNull UUID reconciliationId);

    /** Audit trail of a reconciliation's actions (derived until S5 stores it). */
    @NonNull
    ReconciliationAuditResponse audit(@NonNull UUID reconciliationId);
}
