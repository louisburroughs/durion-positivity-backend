package com.positivity.accounting.internal.billintake.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.billintake.config.BillIntakeProperties;
import com.positivity.accounting.internal.billintake.entity.BillIntakeItem;
import com.positivity.accounting.internal.billintake.enums.BillExtractionOutcome;
import com.positivity.accounting.internal.billintake.enums.BillIntakeChannel;
import com.positivity.accounting.internal.billintake.enums.BillIntakeStatus;
import com.positivity.accounting.internal.billintake.repository.BillIntakeItemRepository;
import com.positivity.accounting.internal.billintake.service.ExtractionPort.ExtractionOutcome;
import com.positivity.accounting.internal.billintake.service.ExtractionPort.InspectionVerdict;
import com.positivity.accounting.internal.billintake.service.MalwareScanner.ScanVerdict;
import com.positivity.accounting.internal.service.TenantLocaleResolver;
import com.positivity.tenancy.TenantIterator;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.PlatformTransactionManager;

/** The processor's branches with doubles for the scanner and the extraction port (S25 #2518, acceptance 1-5). */
@DisplayName("BillIntakeProcessor (S25 #2518)")
class BillIntakeProcessorTest {

    private static final Instant NOW = Instant.parse("2026-10-17T09:00:00Z");
    private static final UUID FILE = UUID.fromString("019a0000-0000-7000-8000-0000000000f1");
    private static final byte[] PDF = "%PDF-1.7 a bill".getBytes(StandardCharsets.US_ASCII);

    private final BillIntakeItemRepository items = mock(BillIntakeItemRepository.class);
    private final FileStore fileStore = mock(FileStore.class);
    private final MalwareScanner scanner = mock(MalwareScanner.class);
    private final ExtractionPort extraction = mock(ExtractionPort.class);
    private final BillIntakeChecks checks = mock(BillIntakeChecks.class);
    private final BillIntakeAuditRecorder audit = mock(BillIntakeAuditRecorder.class);
    private final TenantLocaleResolver locale = mock(TenantLocaleResolver.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final BillIntakeProperties properties = new BillIntakeProperties();
    private BillIntakeProcessor processor;
    private BillIntakeItem item;

    @BeforeEach
    void setUp() {
        ObjectProvider<MeterRegistry> meters = mock();
        when(meters.getIfAvailable()).thenReturn(registry);
        processor = new BillIntakeProcessor(
                items,
                fileStore,
                scanner,
                extraction,
                checks,
                audit,
                new BillIntakeMetrics(meters),
                properties,
                mock(TenantIterator.class),
                locale,
                mock(PlatformTransactionManager.class),
                Clock.fixed(NOW, ZoneOffset.UTC));
        item = new BillIntakeItem();
        item.setIntakeId(UUID.randomUUID());
        item.setIntakeReference("BI-202610-0000001");
        item.setChannel(BillIntakeChannel.UPLOAD);
        item.setStatus(BillIntakeStatus.QUARANTINED);
        item.setSourceFileId(FILE);
        item.setUploadedAt(NOW.minusSeconds(60));
        when(items.claimDue(eq(BillIntakeStatus.QUARANTINED), eq(BillIntakeChannel.UPLOAD), eq(NOW), any(Pageable.class)))
                .thenReturn(List.of(item));
        when(items.claimDue(eq(BillIntakeStatus.EXTRACTING), eq(BillIntakeChannel.UPLOAD), eq(NOW), any(Pageable.class)))
                .thenReturn(List.of(item));
        when(fileStore.open(FILE))
                .thenReturn(Optional.of(new FileStore.StoredContent(FILE, "sha", "application/pdf", PDF.length, PDF)));
        when(locale.localeOfCurrentTenant()).thenReturn("en-US");
    }

    private void scans(ScanVerdict verdict) {
        when(scanner.scan(PDF)).thenReturn(verdict);
    }

    @Test
    @DisplayName("no due item: nothing to do")
    void idle() {
        when(items.claimDue(any(), any(), any(), any())).thenReturn(List.of());
        assertThat(processor.advanceQuarantined()).isFalse();
        assertThat(processor.advanceExtracting()).isFalse();
    }

    @Test
    @DisplayName("INFECTED: FILE_REJECTED / MALWARE_FOUND, bytes kept 30 days, never inspected; the scan is audited and counted")
    void infected() {
        scans(ScanVerdict.INFECTED);

        assertThat(processor.advanceQuarantined()).isTrue();

        assertThat(item.getStatus()).isEqualTo(BillIntakeStatus.FILE_REJECTED);
        assertThat(item.getRejectionReason()).isEqualTo("MALWARE_FOUND");
        verify(fileStore).reject(FILE, NOW.plus(Duration.ofDays(30)));
        verify(extraction, never()).inspect(any(), any());
        verify(audit).record(eq(item.getIntakeId()), eq("SCAN"), eq("SYSTEM"), any(), any(), eq("{\"sha256\":\"sha\",\"verdict\":\"INFECTED\"}"));
        assertThat(registry.get("accounting.bill_intake.scan").tag("verdict", "infected").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("ERROR: the scanner refused this file, FILE_REJECTED / SCAN_FAILED")
    void scanError() {
        scans(ScanVerdict.ERROR);

        processor.advanceQuarantined();

        assertThat(item.getStatus()).isEqualTo(BillIntakeStatus.FILE_REJECTED);
        assertThat(item.getRejectionReason()).isEqualTo("SCAN_FAILED");
    }

    @Test
    @DisplayName("UNAVAILABLE: an outage never rejects: the item stays QUARANTINED and backs off, however often")
    void scannerOutageStaysQuarantined() {
        scans(ScanVerdict.UNAVAILABLE);

        processor.advanceQuarantined();

        assertThat(item.getStatus()).isEqualTo(BillIntakeStatus.QUARANTINED);
        assertThat(item.getAttempts()).isEqualTo(1);
        assertThat(item.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(30));
        verify(fileStore, never()).reject(any(), any());
        verify(fileStore, never()).promote(any(), any());
        verify(extraction, never()).inspect(any(), any());

        // A tenth failed attempt waits no longer than the cap, and still nothing is rejected.
        item.setAttempts(9);
        processor.advanceQuarantined();
        assertThat(item.getNextAttemptAt()).isEqualTo(NOW.plus(properties.getProcessor().getMaxBackoff()));
        assertThat(item.getStatus()).isEqualTo(BillIntakeStatus.QUARANTINED);
    }

    @Test
    @DisplayName("clean, then the inspection refuses it (an encrypted PDF): FILE_REJECTED with the worker's reason")
    void inspectionRefuses() {
        scans(ScanVerdict.CLEAN);
        when(extraction.inspect(PDF, ExtractionPort.DeclaredType.PDF))
                .thenReturn(new ExtractionPort.InspectionResult(
                        InspectionVerdict.REJECTED, "ENCRYPTED_PDF", ExtractionPort.DeclaredType.PDF, 0, null));

        processor.advanceQuarantined();

        assertThat(item.getStatus()).isEqualTo(BillIntakeStatus.FILE_REJECTED);
        assertThat(item.getRejectionReason()).isEqualTo("ENCRYPTED_PDF");
        verify(fileStore).reject(eq(FILE), any());
    }

    @Test
    @DisplayName("a reason code this build does not know is stored as UNREADABLE, never as the worker's text")
    void unknownReasonIsUnreadable() {
        scans(ScanVerdict.CLEAN);
        when(extraction.inspect(any(), any()))
                .thenReturn(new ExtractionPort.InspectionResult(InspectionVerdict.REJECTED, "NO_SUCH_REASON: secret", null, 0, null));

        processor.advanceQuarantined();

        assertThat(item.getRejectionReason()).isEqualTo("UNREADABLE");
    }

    @Test
    @DisplayName("clean, then the worker cannot be asked: stays QUARANTINED, not promoted, not rejected")
    void workerOutageAtInspection() {
        scans(ScanVerdict.CLEAN);
        when(extraction.inspect(any(), any())).thenReturn(ExtractionPort.InspectionResult.unavailable());

        processor.advanceQuarantined();

        assertThat(item.getStatus()).isEqualTo(BillIntakeStatus.QUARANTINED);
        assertThat(item.getAttempts()).isEqualTo(1);
        verify(fileStore, never()).promote(any(), any());
        verify(fileStore, never()).reject(any(), any());
    }

    @Test
    @DisplayName("clean and accepted: the file is promoted with its page count and the item is EXTRACTING")
    void accepted() {
        scans(ScanVerdict.CLEAN);
        when(extraction.inspect(any(), any()))
                .thenReturn(new ExtractionPort.InspectionResult(
                        InspectionVerdict.ACCEPTED, null, ExtractionPort.DeclaredType.PDF, 3, "sha"));

        processor.advanceQuarantined();

        assertThat(item.getStatus()).isEqualTo(BillIntakeStatus.EXTRACTING);
        assertThat(item.getAttempts()).isZero();
        verify(fileStore).promote(FILE, 3);
    }

    private void extractionAnswers(ExtractionOutcome outcome, String reason) {
        when(extraction.extract(PDF, ExtractionPort.DeclaredType.PDF, ExtractionPort.DocumentKind.BILL, "en-US"))
                .thenReturn(new ExtractionPort.ExtractionResult(outcome, reason, "none", 0, List.of()));
        item.setStatus(BillIntakeStatus.EXTRACTING);
    }

    @Test
    @DisplayName("a reading that failed leaves a blank draft: every field CHECK_THIS, MANUAL, lastExtractionFailed, checked")
    void failedReadingLeavesABlankDraft() {
        extractionAnswers(ExtractionOutcome.FAILED, "PROVIDER_COULD_NOT_READ");

        assertThat(processor.advanceExtracting()).isTrue();

        assertThat(item.getStatus()).isEqualTo(BillIntakeStatus.READY_FOR_REVIEW);
        assertThat(item.getExtractionOutcome()).isEqualTo(BillExtractionOutcome.MANUAL);
        assertThat(item.isLastExtractionFailed()).isTrue();
        assertThat(item.getExtractionReason()).isEqualTo("PROVIDER_COULD_NOT_READ");
        assertThat(item.getReadBack()).containsKeys("vendor", "billNumber", "totalAmount", "lines");
        assertThat(item.getReadBack().get("billNumber").toString()).contains("CHECK_THIS").doesNotContain("READ,");
        verify(checks).apply(item);
    }

    @Test
    @DisplayName("an unavailable reader is retried inside the window, then the draft is left blank as if not read")
    void unavailableReaderIsRetriedThenLeftBlank() {
        extractionAnswers(ExtractionOutcome.UNAVAILABLE, "PROVIDER_UNAVAILABLE");
        item.setUploadedAt(NOW.minus(Duration.ofMinutes(14)));

        processor.advanceExtracting();

        assertThat(item.getStatus()).isEqualTo(BillIntakeStatus.EXTRACTING);
        assertThat(item.getAttempts()).isEqualTo(1);
        verify(checks, never()).apply(any());

        item.setUploadedAt(NOW.minus(Duration.ofMinutes(16)));
        processor.advanceExtracting();

        assertThat(item.getStatus()).isEqualTo(BillIntakeStatus.READY_FOR_REVIEW);
        assertThat(item.getExtractionOutcome()).isEqualTo(BillExtractionOutcome.MANUAL);
        assertThat(item.isLastExtractionFailed()).isTrue();
        verify(checks).apply(item);
    }
}
