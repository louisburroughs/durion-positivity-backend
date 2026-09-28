package com.positivity.accounting.internal.bankfeed.file.service;

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
 * Writes the file adapter's {@code AccountingAuditLog} rows (SPEC-manual-bank-reconciliation §3.3
 * "Every transition also writes", §6.4; story S3, #2302) in the caller's transaction: entity type
 * {@code BANK_IMPORT}, one operation per transition, never the file bytes.
 */
@Component
@RequiredArgsConstructor
public class BankImportAuditRecorder {

    public static final String BANK_IMPORT = "BANK_IMPORT";

    public static final String BANK_IMPORT_CREATE = "BANK_IMPORT_CREATE";
    public static final String BANK_IMPORT_MAPPING_SET = "BANK_IMPORT_MAPPING_SET";
    public static final String BANK_IMPORT_ROW_CORRECT = "BANK_IMPORT_ROW_CORRECT";
    public static final String BANK_IMPORT_COMMIT = "BANK_IMPORT_COMMIT";
    public static final String BANK_IMPORT_DISCARD = "BANK_IMPORT_DISCARD";
    public static final String BANK_IMPORT_FILE_READ = "BANK_IMPORT_FILE_READ";
    public static final String BANK_IMPORT_FILE_PURGE = "BANK_IMPORT_FILE_PURGE";

    private final AccountingAuditLogRepository auditLogs;

    /** Records one audit row for an import in the caller's transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(
            @NonNull UUID importId,
            @NonNull String operation,
            @NonNull String actor,
            @Nullable String justification,
            @Nullable String oldValue,
            @Nullable String newValue) {
        AccountingAuditLog row = new AccountingAuditLog();
        row.setEntityType(BANK_IMPORT);
        row.setEntityId(importId);
        row.setOperation(operation);
        row.setUserId(actor);
        row.setJustification(justification);
        row.setOldValue(oldValue);
        row.setNewValue(newValue);
        row.setTraceId(MDC.get("traceId"));
        auditLogs.save(row);
    }
}
