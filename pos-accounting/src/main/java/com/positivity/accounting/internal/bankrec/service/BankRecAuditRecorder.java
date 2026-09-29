package com.positivity.accounting.internal.bankrec.service;

import com.positivity.accounting.internal.entity.AccountingAuditLog;
import com.positivity.accounting.internal.repository.AccountingAuditLogRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

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

    /** Entity types of the reconciliation core (§4.9, §6.4; story S4, #2303). */
    public static final String BANK_RECONCILIATION = "BANK_RECONCILIATION";

    public static final String RECONCILIATION_MATCH = "RECONCILIATION_MATCH";
    public static final String OUTSTANDING_ITEM = "OUTSTANDING_ITEM";

    /** Operations written by story S4. */
    public static final String RECONCILIATION_CREATE = "RECONCILIATION_CREATE";

    public static final String RECONCILIATION_MATCH_CREATE = "RECONCILIATION_MATCH";
    public static final String RECONCILIATION_MATCH_ACCEPT = "RECONCILIATION_MATCH_ACCEPT";
    public static final String RECONCILIATION_MATCH_REJECT = "RECONCILIATION_MATCH_REJECT";
    public static final String RECONCILIATION_UNMATCH = "RECONCILIATION_UNMATCH";
    public static final String RECONCILIATION_AUTO_MATCH = "RECONCILIATION_AUTO_MATCH";
    public static final String RECONCILIATION_OUTSTANDING_REGISTER = "RECONCILIATION_OUTSTANDING_REGISTER";
    public static final String RECONCILIATION_OUTSTANDING_RELEASE = "RECONCILIATION_OUTSTANDING_RELEASE";
    public static final String RECONCILIATION_OUTSTANDING_REAFFIRM = "RECONCILIATION_OUTSTANDING_REAFFIRM";
    public static final String RECONCILIATION_OUTSTANDING_CLEAR_IN_GAP = "RECONCILIATION_OUTSTANDING_CLEAR_IN_GAP";
    public static final String RECONCILIATION_ADJUSTMENT = "RECONCILIATION_ADJUSTMENT";
    public static final String RECONCILIATION_ADJUSTMENT_REVERSE = "RECONCILIATION_ADJUSTMENT_REVERSE";

    /** Operations written by story S5 (§4.9, #2304). */
    public static final String RECONCILIATION_SUBMIT = "RECONCILIATION_SUBMIT";

    public static final String RECONCILIATION_APPROVE = "RECONCILIATION_APPROVE";
    public static final String RECONCILIATION_RETURN = "RECONCILIATION_RETURN";
    public static final String RECONCILIATION_CANCEL = "RECONCILIATION_CANCEL";
    public static final String RECONCILIATION_SUPERSEDE = "RECONCILIATION_SUPERSEDE";
    public static final String RECONCILIATION_INVALIDATE = "RECONCILIATION_INVALIDATE";
    public static final String BANK_STATEMENT_SUPERSEDE = "BANK_STATEMENT_SUPERSEDE";

    private final AccountingAuditLogRepository auditLogs;
    private final PlatformTransactionManager transactionManager;

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

    /**
     * Records one audit row in a transaction of its own, committed whatever the caller's transaction does
     * next: the refusal of a self-approval is audited although the request answers 403 and rolls back
     * (§4.9, D3; S5, #2304).
     */
    public void recordIndependently(
            @NonNull String entityType,
            @NonNull UUID entityId,
            @NonNull String operation,
            @NonNull String actor,
            @Nullable String justification,
            @Nullable String oldValue,
            @Nullable String newValue) {
        TransactionTemplate independent = new TransactionTemplate(transactionManager);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        independent.executeWithoutResult(
                status -> record(entityType, entityId, operation, actor, justification, oldValue, newValue));
    }
}
