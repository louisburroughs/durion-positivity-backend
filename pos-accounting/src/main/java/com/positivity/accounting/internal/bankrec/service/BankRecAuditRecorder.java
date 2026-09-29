package com.positivity.accounting.internal.bankrec.service;

import com.positivity.accounting.internal.entity.AccountingAuditLog;
import com.positivity.accounting.internal.repository.AccountingAuditLogRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the bank reconciliation core's {@code AccountingAuditLog} rows (SPEC §4 preamble, §3.1;
 * story S2, #2301) in the caller's transaction, following the {@code AccountingPeriodServiceImpl}
 * writer pattern. The operation is the endpoint's {@code @EmitEvent} id without the {@code
 * ACCOUNTING_} prefix, except {@code BANK_ACCOUNT_BASELINE_SET}, which §3.1 names.
 */
@Component
@RequiredArgsConstructor
public class BankRecAuditRecorder {

    /** Entity types of the bank reconciliation core (§6.4 {@code accounting_audit_log} row). */
    public static final String BANK_STATEMENT = "BANK_STATEMENT";

    public static final String BANK_TRANSACTION = "BANK_TRANSACTION";
    public static final String BANK_ACCOUNT_PROFILE = "BANK_ACCOUNT_PROFILE";

    /** Operations written by this story. */
    public static final String BANK_STATEMENT_CREATE = "BANK_STATEMENT_CREATE";

    public static final String BANK_TRANSACTION_DUPLICATE_REVIEW = "BANK_TRANSACTION_DUPLICATE_REVIEW";
    public static final String BANK_TRANSACTION_EXCLUDE = "BANK_TRANSACTION_EXCLUDE";
    public static final String BANK_TRANSACTION_RESTORE = "BANK_TRANSACTION_RESTORE";
    public static final String BANK_ACCOUNT_PROFILE_SET = "BANK_ACCOUNT_PROFILE_SET";
    public static final String BANK_ACCOUNT_BASELINE_SET = "BANK_ACCOUNT_BASELINE_SET";

    private final AccountingAuditLogRepository auditLogs;

    /** Records one audit row in the caller's transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(
            @NonNull String entityType,
            @NonNull UUID entityId,
            @NonNull String operation,
            @NonNull String actor,
            @Nullable String justification,
            @Nullable String oldValue,
            @Nullable String newValue) {
        AccountingAuditLog row = new AccountingAuditLog();
        row.setEntityType(entityType);
        row.setEntityId(entityId);
        row.setOperation(operation);
        row.setUserId(actor);
        row.setJustification(justification);
        row.setOldValue(oldValue);
        row.setNewValue(newValue);
        row.setTraceId(MDC.get("traceId"));
        auditLogs.save(row);
    }
}
