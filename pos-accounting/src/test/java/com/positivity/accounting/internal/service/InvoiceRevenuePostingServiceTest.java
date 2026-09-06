package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.config.OutboxEventWriter;
import com.positivity.accounting.internal.entity.InvoiceGlPosting;
import com.positivity.accounting.internal.repository.InvoiceGlPostingRepository;
import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.accounting.InvoiceGlPostedV1;
import com.positivity.domainevents.invoice.InvoiceUpdatedV1;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Unit tests for {@link InvoiceRevenuePostingService} (issue #1843): posts once per finalization
 * cycle, skips deposit-take / zero / never-finalized invoices, is idempotent on redelivery, reverses
 * an open posting on revert, re-posts after a reversal, dates the entry at {@code finalizedAt}
 * in the clock's zone, and enqueues the {@code accounting.invoice.gl-posted} fact.
 */
class InvoiceRevenuePostingServiceTest {

    // A non-UTC zone so the finalizedAt -> transactionDate conversion is observable.
    private static final ZoneId ZONE = ZoneId.of("America/Chicago");
    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-07-23T12:00:00Z"), ZONE);
    private static final UUID INVOICE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID AR = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID REVENUE = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    private static final UUID TAX_PAYABLE = UUID.fromString("00000000-0000-0000-0000-00000000000d");
    private static final UUID JOURNAL_ENTRY_ID = UUID.fromString("00000000-0000-0000-0000-00000000000e");
    private static final UUID REVERSAL_ENTRY_ID = UUID.fromString("00000000-0000-0000-0000-00000000000f");
    // 2026-07-01T03:30Z is still June 30 in Chicago (UTC-5): the entry must land in June.
    private static final Instant FINALIZED_AT = Instant.parse("2026-07-01T03:30:00Z");
    private static final Instant REVERTED_AT = Instant.parse("2026-07-10T15:00:00Z");

    private final GLMappingResolver glMappingResolver = mock(GLMappingResolver.class);
    private final GLPostingService glPostingService = mock(GLPostingService.class);
    private final InvoiceGlPostingRepository repository = mock(InvoiceGlPostingRepository.class);
    private final OutboxEventWriter outboxEventWriter = mock(OutboxEventWriter.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<OutboxEventWriter> writerProvider = mock(ObjectProvider.class);

    private InvoiceRevenuePostingService service;

    @BeforeEach
    void setUp() {
        when(writerProvider.getIfAvailable()).thenReturn(outboxEventWriter);
        when(repository.save(any(InvoiceGlPosting.class))).thenAnswer(inv -> inv.getArgument(0));
        service = new InvoiceRevenuePostingService(
                TEST_CLOCK, glMappingResolver, glPostingService, repository, writerProvider);
    }

    private static InvoiceUpdatedV1 fact(
            String status, BigDecimal total, BigDecimal tax, Instant finalizedAt, String depositSourceType) {
        return new InvoiceUpdatedV1(
                INVOICE_ID,
                "INV-2026-000123",
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                null,
                null,
                "party-1",
                status,
                total == null ? null : total.subtract(tax == null ? BigDecimal.ZERO : tax),
                tax,
                total,
                BigDecimal.ZERO,
                Instant.parse("2026-06-30T20:00:00Z"),
                finalizedAt,
                null,
                null,
                null,
                null,
                depositSourceType,
                null);
    }

    private static InvoiceUpdatedV1 finalized() {
        return fact("FINALIZED", new BigDecimal("216.53"), new BigDecimal("16.53"), FINALIZED_AT, null);
    }

    private static LocalDateTime expectedDate(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZONE);
    }

    private void stubAccounts(LocalDateTime date) {
        when(glMappingResolver.resolveGLAccount("INVOICE_REVENUE", "ACCOUNTS_RECEIVABLE", date))
                .thenReturn(AR);
        when(glMappingResolver.resolveGLAccount("INVOICE_REVENUE", "SERVICE_REVENUE", date))
                .thenReturn(REVENUE);
        when(glMappingResolver.resolveGLAccount("INVOICE_REVENUE", "SALES_TAX_PAYABLE", date))
                .thenReturn(TAX_PAYABLE);
    }

    private static InvoiceGlPosting openPosting() {
        return InvoiceGlPosting.builder()
                .id(UUID.fromString("00000000-0000-0000-0000-0000000000a1"))
                .invoiceId(INVOICE_ID)
                .finalizedAt(FINALIZED_AT)
                .journalEntryId(JOURNAL_ENTRY_ID)
                .postedAt(FINALIZED_AT)
                .revenueAmount(new BigDecimal("200.00"))
                .taxAmount(new BigDecimal("16.53"))
                .createdAt(FINALIZED_AT)
                .updatedAt(FINALIZED_AT)
                .build();
    }

    @SuppressWarnings("unchecked")
    private DomainEventEnvelope<InvoiceGlPostedV1> capturedFact() {
        ArgumentCaptor<DomainEventEnvelope<?>> envelope = ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(outboxEventWriter).publish(eq("accounting.events.v1"), envelope.capture());
        return (DomainEventEnvelope<InvoiceGlPostedV1>) envelope.getValue();
    }

    @Test
    @DisplayName("FINALIZED posts Dr AR (total) / Cr Revenue (total - tax) / Cr Tax (tax) dated at finalizedAt")
    void finalizedPostsRevenue() {
        LocalDateTime date = expectedDate(FINALIZED_AT);
        stubAccounts(date);
        when(repository.findByInvoiceIdAndReversalJournalEntryIdIsNull(INVOICE_ID))
                .thenReturn(Optional.empty());
        when(repository.existsByInvoiceIdAndFinalizedAt(INVOICE_ID, FINALIZED_AT))
                .thenReturn(false);
        when(glPostingService.postInvoiceRevenue(any(), any(), any(), any(), any(), any(), any(), any(), anyString()))
                .thenReturn(JOURNAL_ENTRY_ID);

        service.postRevenue(finalized());

        verify(glPostingService)
                .postInvoiceRevenue(
                        eq(InvoiceRevenuePostingService.toSourceEventId(INVOICE_ID, FINALIZED_AT)),
                        eq(INVOICE_ID),
                        eq(AR),
                        eq(REVENUE),
                        eq(TAX_PAYABLE),
                        eq(new BigDecimal("200.00")),
                        eq(new BigDecimal("16.53")),
                        eq(date),
                        eq("Invoice revenue recognition - INV#INV-2026-000123"));
        // Business time in the clock's zone: 2026-07-01T03:30Z is June 30 in Chicago.
        assertThat(date).isEqualTo(LocalDateTime.of(2026, 6, 30, 22, 30));

        ArgumentCaptor<InvoiceGlPosting> row = ArgumentCaptor.forClass(InvoiceGlPosting.class);
        verify(repository).save(row.capture());
        assertThat(row.getValue().getInvoiceId()).isEqualTo(INVOICE_ID);
        assertThat(row.getValue().getFinalizedAt()).isEqualTo(FINALIZED_AT);
        assertThat(row.getValue().getJournalEntryId()).isEqualTo(JOURNAL_ENTRY_ID);
        assertThat(row.getValue().getPostedAt()).isEqualTo(FINALIZED_AT);
        assertThat(row.getValue().getRevenueAmount()).isEqualByComparingTo("200.00");
        assertThat(row.getValue().getTaxAmount()).isEqualByComparingTo("16.53");
        assertThat(row.getValue().getReversalJournalEntryId()).isNull();
        // createdAt/updatedAt are stamped by JPA auditing (ADR-0024), not by the service.
        assertThat(row.getValue().getCreatedAt()).isNull();
        assertThat(row.getValue().getUpdatedAt()).isNull();
    }

    @Test
    @DisplayName("Enqueues an accounting.invoice.gl-posted POSTED fact through the outbox")
    void finalizedEnqueuesPostedFact() {
        stubAccounts(expectedDate(FINALIZED_AT));
        when(repository.findByInvoiceIdAndReversalJournalEntryIdIsNull(INVOICE_ID))
                .thenReturn(Optional.empty());
        when(glPostingService.postInvoiceRevenue(any(), any(), any(), any(), any(), any(), any(), any(), anyString()))
                .thenReturn(JOURNAL_ENTRY_ID);

        service.postRevenue(finalized());

        DomainEventEnvelope<InvoiceGlPostedV1> envelope = capturedFact();
        assertThat(envelope.eventType()).isEqualTo("accounting.invoice.gl-posted");
        assertThat(envelope.schemaVersion()).isEqualTo(1);
        assertThat(envelope.aggregateId()).isEqualTo(INVOICE_ID);
        assertThat(envelope.sourceService()).isEqualTo("pos-accounting");
        assertThat(envelope.occurredAtUtc()).isEqualTo(Instant.now(TEST_CLOCK));
        InvoiceGlPostedV1 payload = envelope.payload();
        assertThat(payload.invoiceId()).isEqualTo(INVOICE_ID);
        assertThat(payload.journalEntryId()).isEqualTo(JOURNAL_ENTRY_ID);
        assertThat(payload.postingKind()).isEqualTo(InvoiceGlPostedV1.PostingKind.POSTED);
        assertThat(payload.finalizedAt()).isEqualTo(FINALIZED_AT);
        assertThat(payload.postedAt()).isEqualTo(FINALIZED_AT);
        assertThat(payload.reversedJournalEntryId()).isNull();
    }

    @Test
    @DisplayName("Tax-free invoice posts a zero tax leg (omitted downstream) and revenue == total")
    void taxFreeInvoicePostsRevenueEqualToTotal() {
        stubAccounts(expectedDate(FINALIZED_AT));
        when(repository.findByInvoiceIdAndReversalJournalEntryIdIsNull(INVOICE_ID))
                .thenReturn(Optional.empty());
        when(glPostingService.postInvoiceRevenue(any(), any(), any(), any(), any(), any(), any(), any(), anyString()))
                .thenReturn(JOURNAL_ENTRY_ID);

        service.postRevenue(fact("FINALIZED", new BigDecimal("150.00"), null, FINALIZED_AT, null));

        verify(glPostingService)
                .postInvoiceRevenue(
                        any(),
                        eq(INVOICE_ID),
                        eq(AR),
                        eq(REVENUE),
                        eq(TAX_PAYABLE),
                        eq(new BigDecimal("150.00")),
                        eq(BigDecimal.ZERO),
                        any(),
                        anyString());
    }

    @Test
    @DisplayName("POSTED status posts too (backfill of invoices the old simulated path marked posted)")
    void postedStatusAlsoPosts() {
        stubAccounts(expectedDate(FINALIZED_AT));
        when(repository.findByInvoiceIdAndReversalJournalEntryIdIsNull(INVOICE_ID))
                .thenReturn(Optional.empty());
        when(glPostingService.postInvoiceRevenue(any(), any(), any(), any(), any(), any(), any(), any(), anyString()))
                .thenReturn(JOURNAL_ENTRY_ID);

        service.postRevenue(fact("POSTED", new BigDecimal("216.53"), new BigDecimal("16.53"), FINALIZED_AT, null));

        verify(glPostingService)
                .postInvoiceRevenue(any(), any(), any(), any(), any(), any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("Redelivery with an open posting is a no-op — exactly once per finalization cycle")
    void redeliveryWithOpenPostingIsNoOp() {
        when(repository.findByInvoiceIdAndReversalJournalEntryIdIsNull(INVOICE_ID))
                .thenReturn(Optional.of(openPosting()));

        service.postRevenue(finalized());

        verify(glPostingService, never())
                .postInvoiceRevenue(any(), any(), any(), any(), any(), any(), any(), any(), anyString());
        verify(repository, never()).save(any());
        verify(outboxEventWriter, never()).publish(anyString(), any());
    }

    @Test
    @DisplayName("Replay of an already-reversed cycle (same finalizedAt) posts nothing")
    void replayOfReversedCycleIsNoOp() {
        when(repository.findByInvoiceIdAndReversalJournalEntryIdIsNull(INVOICE_ID))
                .thenReturn(Optional.empty());
        when(repository.existsByInvoiceIdAndFinalizedAt(INVOICE_ID, FINALIZED_AT))
                .thenReturn(true);

        service.postRevenue(finalized());

        verify(glPostingService, never())
                .postInvoiceRevenue(any(), any(), any(), any(), any(), any(), any(), any(), anyString());
        verify(outboxEventWriter, never()).publish(anyString(), any());
    }

    @Test
    @DisplayName("Deposit-take invoice (depositSourceType set) funds a contract liability, not revenue — skipped")
    void depositTakeInvoiceIsSkipped() {
        service.postRevenue(fact("FINALIZED", new BigDecimal("108.00"), BigDecimal.ZERO, FINALIZED_AT, "WORKORDER"));

        verify(glPostingService, never())
                .postInvoiceRevenue(any(), any(), any(), any(), any(), any(), any(), any(), anyString());
        verify(repository, never()).findByInvoiceIdAndReversalJournalEntryIdIsNull(any());
    }

    @Test
    @DisplayName("Zero or null total posts nothing")
    void zeroTotalIsSkipped() {
        service.postRevenue(fact("FINALIZED", BigDecimal.ZERO, BigDecimal.ZERO, FINALIZED_AT, null));
        service.postRevenue(fact("FINALIZED", null, null, FINALIZED_AT, null));

        verify(glPostingService, never())
                .postInvoiceRevenue(any(), any(), any(), any(), any(), any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("A FINALIZED fact without finalizedAt posts nothing")
    void missingFinalizedAtIsSkipped() {
        service.postRevenue(fact("FINALIZED", new BigDecimal("216.53"), new BigDecimal("16.53"), null, null));

        verify(glPostingService, never())
                .postInvoiceRevenue(any(), any(), any(), any(), any(), any(), any(), any(), anyString());
        verify(glMappingResolver, never()).resolveGLAccount(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("Posting failures (missing mapping, closed period) propagate unwrapped and persist nothing")
    void postingFailurePropagates() {
        when(repository.findByInvoiceIdAndReversalJournalEntryIdIsNull(INVOICE_ID))
                .thenReturn(Optional.empty());
        when(glMappingResolver.resolveGLAccount(anyString(), anyString(), any()))
                .thenThrow(new IllegalArgumentException("No GL mapping configured"));

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> service.postRevenue(finalized()))
                .withMessageContaining("No GL mapping");

        verify(repository, never()).save(any());
        verify(outboxEventWriter, never()).publish(anyString(), any());
    }

    @Test
    @DisplayName("Publishing degrades to a no-op when the outbox writer bean is absent (Kafka off)")
    void noOpPublishWithoutWriter() {
        when(writerProvider.getIfAvailable()).thenReturn(null);
        stubAccounts(expectedDate(FINALIZED_AT));
        when(repository.findByInvoiceIdAndReversalJournalEntryIdIsNull(INVOICE_ID))
                .thenReturn(Optional.empty());
        when(glPostingService.postInvoiceRevenue(any(), any(), any(), any(), any(), any(), any(), any(), anyString()))
                .thenReturn(JOURNAL_ENTRY_ID);

        service.postRevenue(finalized());

        verify(repository).save(any(InvoiceGlPosting.class));
        verify(outboxEventWriter, never()).publish(anyString(), any());
    }

    @Test
    @DisplayName("DRAFT with an open posting posts the mirror at the revert's occurredAt and closes the row")
    void draftReversesOpenPosting() {
        LocalDateTime date = expectedDate(REVERTED_AT);
        stubAccounts(date);
        InvoiceGlPosting open = openPosting();
        when(repository.findByInvoiceIdAndReversalJournalEntryIdIsNull(INVOICE_ID))
                .thenReturn(Optional.of(open));
        when(glPostingService.postInvoiceRevenueReversal(
                        any(), any(), any(), any(), any(), any(), any(), any(), anyString()))
                .thenReturn(REVERSAL_ENTRY_ID);

        // The revert fact carries recomputed (different) totals: the reversal must mirror the
        // amounts actually posted, not these.
        service.reverseRevenue(
                fact("DRAFT", new BigDecimal("999.00"), new BigDecimal("1.00"), FINALIZED_AT, null), REVERTED_AT);

        verify(glPostingService)
                .postInvoiceRevenueReversal(
                        eq(InvoiceRevenuePostingService.toReversalSourceEventId(INVOICE_ID, FINALIZED_AT)),
                        eq(INVOICE_ID),
                        eq(AR),
                        eq(REVENUE),
                        eq(TAX_PAYABLE),
                        eq(new BigDecimal("200.00")),
                        eq(new BigDecimal("16.53")),
                        eq(date),
                        eq("Invoice revenue reversal (DRAFT) - INV#INV-2026-000123"));
        assertThat(open.getReversalJournalEntryId()).isEqualTo(REVERSAL_ENTRY_ID);
        assertThat(open.getReversedAt()).isEqualTo(REVERTED_AT);
        assertThat(open.isOpen()).isFalse();
        verify(repository).save(open);

        DomainEventEnvelope<InvoiceGlPostedV1> envelope = capturedFact();
        InvoiceGlPostedV1 payload = envelope.payload();
        assertThat(payload.postingKind()).isEqualTo(InvoiceGlPostedV1.PostingKind.REVERSED);
        assertThat(payload.journalEntryId()).isEqualTo(REVERSAL_ENTRY_ID);
        assertThat(payload.reversedJournalEntryId()).isEqualTo(JOURNAL_ENTRY_ID);
        assertThat(payload.finalizedAt()).isEqualTo(FINALIZED_AT);
        assertThat(payload.postedAt()).isEqualTo(REVERTED_AT);
    }

    @Test
    @DisplayName("Reversal and revenue entries derive distinct sourceEventIds from the same cycle")
    void sourceEventIdsAreDistinctAndDeterministic() {
        UUID revenue = InvoiceRevenuePostingService.toSourceEventId(INVOICE_ID, FINALIZED_AT);
        UUID reversal = InvoiceRevenuePostingService.toReversalSourceEventId(INVOICE_ID, FINALIZED_AT);
        assertThat(revenue).isNotEqualTo(reversal);
        assertThat(revenue).isEqualTo(InvoiceRevenuePostingService.toSourceEventId(INVOICE_ID, FINALIZED_AT));
        assertThat(revenue).isNotEqualTo(InvoiceRevenuePostingService.toSourceEventId(INVOICE_ID, REVERTED_AT));
    }

    @Test
    @DisplayName("DRAFT/CANCELLED without an open posting is a no-op")
    void revertWithoutOpenPostingIsNoOp() {
        when(repository.findByInvoiceIdAndReversalJournalEntryIdIsNull(INVOICE_ID))
                .thenReturn(Optional.empty());

        service.reverseRevenue(
                fact("DRAFT", new BigDecimal("216.53"), new BigDecimal("16.53"), null, null), REVERTED_AT);
        service.reverseRevenue(
                fact("CANCELLED", new BigDecimal("216.53"), new BigDecimal("16.53"), null, null), REVERTED_AT);

        verify(glPostingService, never())
                .postInvoiceRevenueReversal(any(), any(), any(), any(), any(), any(), any(), any(), anyString());
        verify(repository, never()).save(any());
        verify(outboxEventWriter, never()).publish(anyString(), any());
    }

    @Test
    @DisplayName("Re-finalization after a reversal (new finalizedAt) posts a second cycle")
    void refinalizeAfterReversalPostsAgain() {
        Instant secondFinalizedAt = Instant.parse("2026-07-12T16:00:00Z");
        LocalDateTime date = expectedDate(secondFinalizedAt);
        stubAccounts(date);
        // First cycle was reversed: no open row, and the OLD finalizedAt is known but the new one is not.
        when(repository.findByInvoiceIdAndReversalJournalEntryIdIsNull(INVOICE_ID))
                .thenReturn(Optional.empty());
        when(repository.existsByInvoiceIdAndFinalizedAt(INVOICE_ID, FINALIZED_AT))
                .thenReturn(true);
        when(repository.existsByInvoiceIdAndFinalizedAt(INVOICE_ID, secondFinalizedAt))
                .thenReturn(false);
        when(glPostingService.postInvoiceRevenue(any(), any(), any(), any(), any(), any(), any(), any(), anyString()))
                .thenReturn(REVERSAL_ENTRY_ID);

        service.postRevenue(
                fact("FINALIZED", new BigDecimal("216.53"), new BigDecimal("16.53"), secondFinalizedAt, null));

        verify(glPostingService)
                .postInvoiceRevenue(
                        eq(InvoiceRevenuePostingService.toSourceEventId(INVOICE_ID, secondFinalizedAt)),
                        eq(INVOICE_ID),
                        eq(AR),
                        eq(REVENUE),
                        eq(TAX_PAYABLE),
                        eq(new BigDecimal("200.00")),
                        eq(new BigDecimal("16.53")),
                        eq(date),
                        anyString());
        ArgumentCaptor<InvoiceGlPosting> row = ArgumentCaptor.forClass(InvoiceGlPosting.class);
        verify(repository).save(row.capture());
        assertThat(row.getValue().getFinalizedAt()).isEqualTo(secondFinalizedAt);
    }
}
