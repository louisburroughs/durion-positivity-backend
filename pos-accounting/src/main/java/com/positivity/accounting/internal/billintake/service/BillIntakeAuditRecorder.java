package com.positivity.accounting.internal.billintake.service;

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
 * Writes the {@code BILL_INTAKE} audit rows (CAP:550 S25, #2518): who did what to a draft. A row names the draft, the
 * actor and, in {@code newValue}, the file's sha-256 (and the scan verdict): never a file name, bytes or an extracted
 * value (ADR-0072).
 */
@Component
@RequiredArgsConstructor
public class BillIntakeAuditRecorder {

    public static final String BILL_INTAKE = "BILL_INTAKE";
    public static final String UPLOAD = "UPLOAD";
    public static final String SCAN = "SCAN";

    private final AccountingAuditLogRepository auditLogs;

    /** Records one audit row for a draft in the caller's transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(
            @NonNull UUID intakeId,
            @NonNull String operation,
            @NonNull String actor,
            @Nullable String justification,
            @Nullable String oldValue,
            @Nullable String newValue) {
        AccountingAuditLog row = new AccountingAuditLog();
        row.setEntityType(BILL_INTAKE);
        row.setEntityId(intakeId);
        row.setOperation(operation);
        row.setUserId(actor);
        row.setJustification(justification);
        row.setOldValue(oldValue);
        row.setNewValue(newValue);
        row.setTraceId(MDC.get("traceId"));
        auditLogs.save(row);
    }
}
