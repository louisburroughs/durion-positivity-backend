package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.dto.InvoiceRevenueReconcileRequest;
import com.positivity.accounting.internal.dto.InvoiceRevenueReconcileResponse;
import com.positivity.accounting.internal.dto.InvoiceRevenueReconcileResponse.Kind;
import com.positivity.accounting.internal.entity.ExtInvoice;
import com.positivity.accounting.internal.entity.InvoiceGlPosting;
import com.positivity.accounting.internal.repository.ExtInvoiceRepository;
import com.positivity.accounting.internal.repository.InvoiceGlPostingRepository;
import com.positivity.domainevents.invoice.InvoiceUpdatedV1;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** #1851: reconciliation posts from the replica through the live posting service, idempotently. */
class InvoiceRevenueReconciliationServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-06T18:00:00Z");
    private static final Instant JULY = Instant.parse("2026-07-15T10:00:00Z");
    private static final Instant AUGUST = Instant.parse("2026-08-20T10:00:00Z");

    private ExtInvoiceRepository extInvoiceRepository;
    private InvoiceGlPostingRepository postingRepository;
    private InvoiceRevenuePostingService postingService;
    private InvoiceRevenueReconciliationService service;

    @BeforeEach
    void setUp() {
        extInvoiceRepository = mock(ExtInvoiceRepository.class);
        postingRepository = mock(InvoiceGlPostingRepository.class);
        postingService = mock(InvoiceRevenuePostingService.class);
        service = new InvoiceRevenueReconciliationService(
                extInvoiceRepository, postingRepository, postingService, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static ExtInvoice finalized(UUID id, String number, Instant finalizedAt, String total, String tax) {
        return ExtInvoice.builder()
                .invoiceId(id)
                .invoiceNumber(number)
                .status("FINALIZED")
                .subtotal(new BigDecimal(total).subtract(new BigDecimal(tax)))
                .tax(new BigDecimal(tax))
                .total(new BigDecimal(total))
                .finalizedAt(finalizedAt)
                .aggregateVersion(3L)
                .updatedAt(finalizedAt)
                .build();
    }

    private void windowReturns(ExtInvoice... rows) {
        when(extInvoiceRepository
                        .findByStatusInAndFinalizedAtGreaterThanEqualAndFinalizedAtLessThanOrderByFinalizedAtAsc(
                                any(), any(), any()))
                .thenReturn(List.of(rows));
    }

    @Test
    @DisplayName("a finalized invoice with no open posting is posted from the replica, with the live payload shape")
    void unpostedInvoice_isPostedThroughTheLivePath() {
        UUID id = UUID.randomUUID();
        ExtInvoice invoice = finalized(id, "INV-1", AUGUST, "1200.00", "200.00");
        windowReturns(invoice);
        UUID journalEntryId = UUID.randomUUID();
        when(postingRepository.findByInvoiceIdAndReversalJournalEntryIdIsNull(id))
                .thenReturn(
                        Optional.empty(),
                        Optional.of(InvoiceGlPosting.builder()
                                .invoiceId(id)
                                .finalizedAt(AUGUST)
                                .journalEntryId(journalEntryId)
                                .build()));

        InvoiceRevenueReconcileResponse response = service.reconcile(InvoiceRevenueReconcileRequest.everything());

        ArgumentCaptor<InvoiceUpdatedV1> payload = ArgumentCaptor.forClass(InvoiceUpdatedV1.class);
        verify(postingService).postRevenue(payload.capture());
        assertThat(payload.getValue().invoiceId()).isEqualTo(id);
        assertThat(payload.getValue().status()).isEqualTo("FINALIZED");
        assertThat(payload.getValue().finalizedAt()).isEqualTo(AUGUST);
        assertThat(payload.getValue().total()).isEqualByComparingTo("1200.00");
        assertThat(payload.getValue().tax()).isEqualByComparingTo("200.00");
        assertThat(payload.getValue().depositSourceType()).isNull();
        assertThat(response.posted()).isEqualTo(1);
        assertThat(response.outcomes()).singleElement().satisfies(outcome -> {
            assertThat(outcome.kind()).isEqualTo(Kind.POSTED);
            assertThat(outcome.detail()).contains(journalEntryId.toString());
        });
    }

    @Test
    @DisplayName("a dry run reports WOULD_POST and touches nothing")
    void dryRun_postsNothing() {
        ExtInvoice invoice = finalized(UUID.randomUUID(), "INV-2", JULY, "500.00", "0.00");
        windowReturns(invoice);
        when(postingRepository.findByInvoiceIdAndReversalJournalEntryIdIsNull(any()))
                .thenReturn(Optional.empty());

        InvoiceRevenueReconcileResponse response =
                service.reconcile(new InvoiceRevenueReconcileRequest(null, null, null, Boolean.TRUE, null));

        verify(postingService, never()).postRevenue(any());
        assertThat(response.dryRun()).isTrue();
        assertThat(response.posted()).isEqualTo(1);
        assertThat(response.outcomes().getFirst().kind()).isEqualTo(Kind.WOULD_POST);
    }

    @Test
    @DisplayName("an invoice already on the ledger is reported, not re-posted — a second run is a no-op")
    void alreadyPosted_isLeftAlone() {
        UUID id = UUID.randomUUID();
        windowReturns(finalized(id, "INV-3", JULY, "500.00", "0.00"));
        when(postingRepository.findByInvoiceIdAndReversalJournalEntryIdIsNull(id))
                .thenReturn(Optional.of(InvoiceGlPosting.builder()
                        .invoiceId(id)
                        .finalizedAt(JULY)
                        .journalEntryId(UUID.randomUUID())
                        .build()));

        InvoiceRevenueReconcileResponse response = service.reconcile(InvoiceRevenueReconcileRequest.everything());

        verify(postingService, never()).postRevenue(any());
        assertThat(response.alreadyPosted()).isEqualTo(1);
        assertThat(response.outcomes().getFirst().kind()).isEqualTo(Kind.ALREADY_POSTED);
    }

    @Test
    @DisplayName("skips mirror the live rules: deposit-take, zero total, no finalizedAt, reversed cycle")
    void skips_mirrorTheLiveRules() {
        UUID reversed = UUID.randomUUID();
        ExtInvoice deposit = finalized(UUID.randomUUID(), "INV-D", JULY, "300.00", "0.00");
        deposit.setDepositSourceType("ESTIMATE");
        ExtInvoice zero = finalized(UUID.randomUUID(), "INV-Z", JULY, "0.00", "0.00");
        ExtInvoice noDate = finalized(UUID.randomUUID(), "INV-N", null, "100.00", "0.00");
        ExtInvoice cycle = finalized(reversed, "INV-R", AUGUST, "100.00", "0.00");
        windowReturns(deposit, zero, noDate, cycle);
        when(postingRepository.findByInvoiceIdAndReversalJournalEntryIdIsNull(any()))
                .thenReturn(Optional.empty());
        when(postingRepository.existsByInvoiceIdAndFinalizedAt(reversed, AUGUST))
                .thenReturn(true);

        InvoiceRevenueReconcileResponse response = service.reconcile(InvoiceRevenueReconcileRequest.everything());

        verify(postingService, never()).postRevenue(any());
        assertThat(response.skipped()).isEqualTo(4);
        assertThat(response.outcomes())
                .extracting(InvoiceRevenueReconcileResponse.Outcome::detail)
                .allSatisfy(detail -> assertThat(detail).isNotBlank());
    }

    @Test
    @DisplayName("one failing invoice is reported as FAILED and the run continues")
    void failure_isReportedAndTheRunContinues() {
        UUID bad = UUID.randomUUID();
        UUID good = UUID.randomUUID();
        windowReturns(
                finalized(bad, "INV-B", JULY, "100.00", "0.00"), finalized(good, "INV-G", AUGUST, "200.00", "0.00"));
        when(postingRepository.findByInvoiceIdAndReversalJournalEntryIdIsNull(bad))
                .thenReturn(Optional.empty());
        when(postingRepository.findByInvoiceIdAndReversalJournalEntryIdIsNull(good))
                .thenReturn(
                        Optional.empty(),
                        Optional.of(InvoiceGlPosting.builder()
                                .invoiceId(good)
                                .finalizedAt(AUGUST)
                                .journalEntryId(UUID.randomUUID())
                                .build()));
        doThrow(new IllegalStateException("GL mapping missing for INVOICE_REVENUE"))
                .when(postingService)
                .postRevenue(any(InvoiceUpdatedV1.class));
        // Second call (the good invoice) succeeds: reset the stub to a no-op for that payload.
        org.mockito.Mockito.doAnswer(invocation -> null)
                .when(postingService)
                .postRevenue(org.mockito.ArgumentMatchers.argThat(
                        p -> p != null && p.invoiceId().equals(good)));

        InvoiceRevenueReconcileResponse response = service.reconcile(InvoiceRevenueReconcileRequest.everything());

        assertThat(response.failed()).isEqualTo(1);
        assertThat(response.posted()).isEqualTo(1);
        assertThat(response.outcomes().get(0).kind()).isEqualTo(Kind.FAILED);
        assertThat(response.outcomes().get(0).detail()).contains("GL mapping missing");
        assertThat(response.outcomes().get(1).kind()).isEqualTo(Kind.POSTED);
    }

    @Test
    @DisplayName("explicit invoice ids override the window and drop non-finalized rows; limit keeps the oldest")
    void invoiceIds_andLimit_boundTheRun() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID draft = UUID.randomUUID();
        ExtInvoice draftRow = finalized(draft, "INV-DR", null, "50.00", "0.00");
        draftRow.setStatus("DRAFT");
        when(extInvoiceRepository.findAllById(List.of(a, b, draft)))
                .thenReturn(List.of(
                        finalized(b, "INV-b", AUGUST, "10.00", "0.00"),
                        finalized(a, "INV-a", JULY, "10.00", "0.00"),
                        draftRow));
        when(postingRepository.findByInvoiceIdAndReversalJournalEntryIdIsNull(any()))
                .thenReturn(Optional.empty());

        InvoiceRevenueReconcileResponse response = service.reconcile(
                new InvoiceRevenueReconcileRequest(null, null, List.of(a, b, draft), Boolean.TRUE, 1));

        verify(extInvoiceRepository, never())
                .findByStatusInAndFinalizedAtGreaterThanEqualAndFinalizedAtLessThanOrderByFinalizedAtAsc(
                        any(), any(), any());
        assertThat(response.scanned()).isEqualTo(1);
        assertThat(response.outcomes().getFirst().invoiceId())
                .as("oldest first")
                .isEqualTo(a);
    }

    @Test
    @DisplayName("the window defaults to everything up to now plus a day, statuses FINALIZED and POSTED")
    void window_defaults() {
        windowReturns();

        service.reconcile(InvoiceRevenueReconcileRequest.everything());

        verify(extInvoiceRepository)
                .findByStatusInAndFinalizedAtGreaterThanEqualAndFinalizedAtLessThanOrderByFinalizedAtAsc(
                        eq(InvoiceRevenuePostingService.POSTING_STATUSES),
                        eq(InvoiceRevenueReconciliationService.EARLIEST),
                        eq(NOW.plus(java.time.Duration.ofDays(1))));
    }
}
