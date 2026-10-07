package com.positivity.accounting.internal.bankfeed.file.service;

import com.positivity.accounting.internal.bankfeed.file.entity.BankImport;
import com.positivity.accounting.internal.bankfeed.file.repository.BankImportFileRepository;
import com.positivity.accounting.internal.bankfeed.file.repository.BankImportRepository;
import com.positivity.tenancy.TenantIterator;
import java.time.ZoneOffset;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The raw-file retention job (SPEC-manual-bank-reconciliation §6.4, D13; ADR-0062 §8; story S3,
 * #2302): nightly, once per active tenant with that tenant bound, it hard-deletes the {@code
 * bank_import_file} bytes of every import whose {@code retentionUntil} has passed. The {@code
 * bank_import} metadata row and its {@code bank_import_row} rows are accounting records and stay; the
 * import records when its file was purged, and each purge is audited.
 */
@Slf4j
@Component
public class BankImportRetentionJob {

    static final String SYSTEM = "SYSTEM";

    private final BankImportRepository imports;
    private final BankImportFileRepository files;
    private final BankImportAuditRecorder audit;
    private final TenantIterator tenantIterator;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public BankImportRetentionJob(
            BankImportRepository imports,
            BankImportFileRepository files,
            BankImportAuditRecorder audit,
            TenantIterator tenantIterator,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.imports = imports;
        this.files = files;
        this.audit = audit;
        this.tenantIterator = tenantIterator;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** Runs the purge for every active tenant; one tenant's failure does not stop the others. */
    @Scheduled(cron = "${pos.accounting.bankrec.import.retention-cron:0 30 3 * * *}")
    public void purgeExpiredFiles() {
        tenantIterator.forEachActiveTenant(tenantId -> {
            Integer purged = transaction.execute(status -> purgeForBoundTenant());
            log.info("Bank import retention: tenant={} purged={} file(s)", tenantId, purged);
        });
    }

    /**
     * One tenant's pass, inside the caller's transaction with the tenant bound: every import past
     * retention whose file is still retained.
     *
     * @return how many files were deleted
     */
    public int purgeForBoundTenant() {
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        Instant now = Instant.now(clock);
        List<BankImport> expired = imports.findByRetentionUntilBeforeAndFilePurgedAtIsNull(today);
        int purged = 0;
        for (BankImport found : expired) {
            if (files.existsById(found.getImportId())) {
                files.deleteById(found.getImportId());
                purged++;
            }
            found.setFilePurgedAt(now);
            imports.save(found);
            audit.record(
                    found.getImportId(),
                    BankImportAuditRecorder.BANK_IMPORT_FILE_PURGE,
                    SYSTEM,
                    null,
                    "retentionUntil=" + found.getRetentionUntil(),
                    "purgedAt=" + now);
        }
        return purged;
    }
}
