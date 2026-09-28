package com.positivity.accounting.internal.bankfeed.file.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.bankfeed.file.entity.BankImport;
import com.positivity.accounting.internal.bankfeed.file.repository.BankImportFileRepository;
import com.positivity.accounting.internal.bankfeed.file.repository.BankImportRepository;
import com.positivity.tenancy.TenantIterator;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

/** The raw-file retention job (SPEC §6.4, D13; story S3, #2302). */
@ExtendWith(MockitoExtension.class)
@DisplayName("BankImportRetentionJob (#2302)")
class BankImportRetentionJobTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2033-09-29T03:30:00Z"), ZoneOffset.UTC);

    @Mock
    private BankImportRepository imports;

    @Mock
    private BankImportFileRepository files;

    @Mock
    private BankImportAuditRecorder audit;

    @Mock
    private TenantIterator tenantIterator;

    @Mock
    private PlatformTransactionManager transactionManager;

    private BankImportRetentionJob job;

    @BeforeEach
    void setUp() {
        job = new BankImportRetentionJob(imports, files, audit, tenantIterator, transactionManager, CLOCK);
    }

    @Test
    void anExpiredFileIsDeletedAndTheImportKeptWithItsPurgeTimeAndAnAuditRow() {
        BankImport expired = new BankImport();
        expired.setImportId(UUID.fromString("01990000-0000-7000-8000-000000000001"));
        expired.setRetentionUntil(LocalDate.of(2033, 9, 28));
        when(imports.findByRetentionUntilBeforeAndFilePurgedAtIsNull(LocalDate.of(2033, 9, 29)))
                .thenReturn(List.of(expired));
        when(files.existsById(expired.getImportId())).thenReturn(true);

        assertThat(job.purgeForBoundTenant()).isEqualTo(1);

        verify(files).deleteById(expired.getImportId());
        verify(imports).save(expired);
        verify(imports, never()).delete(any(BankImport.class));
        assertThat(expired.getFilePurgedAt()).isEqualTo(Instant.now(CLOCK));
        verify(audit)
                .record(
                        eq(expired.getImportId()),
                        eq(BankImportAuditRecorder.BANK_IMPORT_FILE_PURGE),
                        eq("SYSTEM"),
                        any(),
                        eq("retentionUntil=2033-09-28"),
                        any());
    }

    @Test
    void theJobVisitsEveryActiveTenant() {
        job.purgeExpiredFiles();
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Consumer<UUID>> work = org.mockito.ArgumentCaptor.forClass(Consumer.class);
        verify(tenantIterator).forEachActiveTenant(work.capture());
        assertThat(work.getValue()).isNotNull();
    }
}
