package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.config.OutboxEventWriter;
import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.accounting.internal.entity.InvoiceGlPosting;
import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.internal.entity.JournalEntryLine;
import com.positivity.accounting.internal.repository.InvoiceGlPostingRepository;
import com.positivity.accounting.internal.repository.JournalEntryRepository;
import com.positivity.domainevents.invoice.InvoiceUpdatedV1;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

/**
 * #2664 review A3: an invoice revenue reversal follows how the recognition posted ({@code tax_posted_by_type}), never
 * the tenant's typed keys at the time of the reversal (ADR-0047). Fixture data only.
 */
@DisplayName("S32d invoice revenue reversal follows the posting")
class InvoiceRevenueReversalByPostingTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-20T12:00:00Z"), ZoneOffset.UTC);
    private static final UUID INVOICE_ID = UUID.fromString("0199b000-0000-7000-8000-0000000000a3");
    private static final UUID JOURNAL_ENTRY_ID = UUID.fromString("0199b000-0000-7000-8000-0000000000e3");
    private static final UUID REVERSAL_ID = UUID.fromString("0199b000-0000-7000-8000-0000000000f3");
    private static final UUID AR = UUID.fromString("0199b000-0000-7000-8000-000000001200");
    private static final UUID REVENUE = UUID.fromString("0199b000-0000-7000-8000-000000004000");
    private static final UUID A2200 = UUID.fromString("0199b000-0000-7000-8000-000000002200");
    private static final UUID A2210 = UUID.fromString("0199b000-0000-7000-8000-000000002210");
    private static final UUID A2230 = UUID.fromString("0199b000-0000-7000-8000-000000002230");
    private static final Instant FINALIZED_AT = Instant.parse("2026-10-01T15:00:00Z");
    private static final Instant CANCELLED_AT = Instant.parse("2026-10-20T10:00:00Z");

    private final GLMappingResolver resolver = mock(GLMappingResolver.class);
    private final GLPostingService glPosting = mock(GLPostingService.class);
    private final InvoiceGlPostingRepository postings = mock(InvoiceGlPostingRepository.class);
    private final TypedOutputTax typedOutputTax = mock(TypedOutputTax.class);
    private final JournalEntryRepository entries = mock(JournalEntryRepository.class);
    private InvoiceRevenuePostingService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ObjectProvider<OutboxEventWriter> writers = mock(ObjectProvider.class);
        when(writers.getIfAvailable()).thenReturn(mock(OutboxEventWriter.class));
        service = new InvoiceRevenuePostingService(
                CLOCK, resolver, glPosting, postings, writers, TestZoneResolvers.utc(CLOCK), typedOutputTax, entries);
        LocalDateTime at = LocalDateTime.ofInstant(CANCELLED_AT, ZoneOffset.UTC);
        lenient()
                .when(resolver.resolveGLAccount("INVOICE_REVENUE", "ACCOUNTS_RECEIVABLE", at))
                .thenReturn(AR);
        lenient()
                .when(resolver.resolveGLAccount("INVOICE_REVENUE", "SERVICE_REVENUE", at))
                .thenReturn(REVENUE);
        lenient()
                .when(resolver.resolveGLAccount("INVOICE_REVENUE", "SALES_TAX_PAYABLE", at))
                .thenReturn(A2200);
    }

    private static InvoiceUpdatedV1 cancelled() {
        return new InvoiceUpdatedV1(
                INVOICE_ID,
                "INV-A3",
                UUID.fromString("0199b000-0000-7000-8000-0000000000c3"),
                null,
                null,
                "party-1",
                "CANCELLED",
                new BigDecimal("1000.00"),
                new BigDecimal("120.00"),
                new BigDecimal("1120.00"),
                BigDecimal.ZERO,
                FINALIZED_AT,
                FINALIZED_AT,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    private void posted(boolean byType) {
        when(postings.findByInvoiceIdAndReversalJournalEntryIdIsNull(INVOICE_ID))
                .thenReturn(Optional.of(InvoiceGlPosting.builder()
                        .invoiceId(INVOICE_ID)
                        .finalizedAt(FINALIZED_AT)
                        .journalEntryId(JOURNAL_ENTRY_ID)
                        .postedAt(FINALIZED_AT)
                        .revenueAmount(new BigDecimal("1000.00"))
                        .taxAmount(new BigDecimal("120.00"))
                        .taxPostedByType(byType)
                        .build()));
    }

    private static JournalEntryLine line(UUID account, String debit, String credit) {
        GLAccount gl = new GLAccount();
        gl.setGlAccountId(account);
        JournalEntryLine line = new JournalEntryLine();
        line.setGlAccount(gl);
        line.setDebitAmount(new BigDecimal(debit));
        line.setCreditAmount(new BigDecimal(credit));
        return line;
    }

    @Test
    @DisplayName("A3: a typed posting whose keys were unmapped since is reversed by mirroring its typed lines")
    void typedPostingReversedAfterKeysUnmapped() {
        posted(true);
        lenient().when(typedOutputTax.typedAccounts(any())).thenReturn(Map.of());
        JournalEntry original = new JournalEntry();
        original.setJournalEntryId(JOURNAL_ENTRY_ID);
        original.getLines().add(line(AR, "1120.00", "0"));
        original.getLines().add(line(REVENUE, "0", "1000.00"));
        original.getLines().add(line(A2210, "0", "50.00"));
        original.getLines().add(line(A2230, "0", "70.00"));
        when(entries.findById(JOURNAL_ENTRY_ID)).thenReturn(Optional.of(original));
        when(glPosting.postMirror(any(), any(), any(), any(), anyString())).thenReturn(REVERSAL_ID);

        assertThat(service.reverseRevenue(cancelled(), CANCELLED_AT)).isEqualTo(FactPostingOutcome.posted(REVERSAL_ID));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<GLPostingService.PostedLine>> lines = ArgumentCaptor.forClass(List.class);
        verify(glPosting)
                .postMirror(
                        eq(JournalEntrySourceTypes.INVOICE_REVENUE_REVERSAL),
                        eq(InvoiceRevenuePostingService.toReversalSourceEventId(INVOICE_ID, FINALIZED_AT)),
                        lines.capture(),
                        any(),
                        anyString());
        assertThat(lines.getValue())
                .extracting(
                        GLPostingService.PostedLine::accountId,
                        l -> l.debit().stripTrailingZeros().toPlainString(),
                        l -> l.credit().stripTrailingZeros().toPlainString())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(AR, "1120", "0"),
                        org.assertj.core.groups.Tuple.tuple(REVENUE, "0", "1000"),
                        org.assertj.core.groups.Tuple.tuple(A2210, "0", "50"),
                        org.assertj.core.groups.Tuple.tuple(A2230, "0", "70"));
        verify(glPosting, never())
                .postInvoiceRevenueReversal(any(), any(), any(), any(), any(), any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("A3: an untyped posting is reversed untyped, even when the tenant has typed keys now")
    void untypedPostingReversedUntyped() {
        posted(false);
        lenient().when(typedOutputTax.typedAccounts(any())).thenReturn(Map.of("GST", A2210));
        when(glPosting.postInvoiceRevenueReversal(any(), any(), any(), any(), any(), any(), any(), any(), anyString()))
                .thenReturn(REVERSAL_ID);

        assertThat(service.reverseRevenue(cancelled(), CANCELLED_AT)).isEqualTo(FactPostingOutcome.posted(REVERSAL_ID));

        verify(glPosting, never()).postMirror(any(), any(), any(), any(), anyString());
    }
}
