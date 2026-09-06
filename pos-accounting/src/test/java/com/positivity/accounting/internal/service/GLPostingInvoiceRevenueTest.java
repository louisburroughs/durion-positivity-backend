package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.dto.JournalEntryCreateRequest;
import com.positivity.accounting.internal.dto.JournalEntryResponse;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for {@link GLPostingServiceImpl#postInvoiceRevenue} and
 * {@link GLPostingServiceImpl#postInvoiceRevenueReversal} (issue #1843): line directions and
 * amounts balance, zero tax legs are omitted, the caller's business transaction date is used
 * verbatim, and the entry is created then posted (so the period gate applies).
 */
@DisplayName("GLPostingService — invoice revenue recognition (#1843)")
class GLPostingInvoiceRevenueTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-07-23T12:00:00Z"), ZoneOffset.UTC);
    private static final LocalDateTime TXN_DATE = LocalDateTime.of(2026, 6, 30, 22, 30);
    private static final UUID SOURCE_EVENT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a0");
    private static final UUID INVOICE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID AR = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID REVENUE = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    private static final UUID TAX_PAYABLE = UUID.fromString("00000000-0000-0000-0000-00000000000d");
    private static final UUID CREATED_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final UUID POSTED_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c2");

    private final JournalEntryService journalEntryService = mock(JournalEntryService.class);
    private final ArgumentCaptor<JournalEntryCreateRequest> request =
            ArgumentCaptor.forClass(JournalEntryCreateRequest.class);

    private GLPostingServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new GLPostingServiceImpl(TEST_CLOCK, journalEntryService);
        when(journalEntryService.createJournalEntry(request.capture()))
                .thenReturn(JournalEntryResponse.builder()
                        .journalEntryId(CREATED_ID)
                        .build());
        when(journalEntryService.postJournalEntry(eq(CREATED_ID), isNull()))
                .thenReturn(
                        JournalEntryResponse.builder().journalEntryId(POSTED_ID).build());
    }

    private static BigDecimal sumDebits(List<JournalEntryCreateRequest.JournalEntryLineRequest> lines) {
        return lines.stream()
                .map(JournalEntryCreateRequest.JournalEntryLineRequest::getDebitAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal sumCredits(List<JournalEntryCreateRequest.JournalEntryLineRequest> lines) {
        return lines.stream()
                .map(JournalEntryCreateRequest.JournalEntryLineRequest::getCreditAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static JournalEntryCreateRequest.JournalEntryLineRequest line(
            List<JournalEntryCreateRequest.JournalEntryLineRequest> lines, UUID accountId) {
        return lines.stream()
                .filter(l -> accountId.equals(l.getGlAccountId()))
                .findFirst()
                .orElseThrow();
    }

    @Test
    @DisplayName("Revenue entry: Dr AR (total) / Cr Revenue / Cr Tax, balanced, dated at the caller's date")
    void postsBalancedRevenueEntry() {
        UUID posted = service.postInvoiceRevenue(
                SOURCE_EVENT_ID,
                INVOICE_ID,
                AR,
                REVENUE,
                TAX_PAYABLE,
                new BigDecimal("200.00"),
                new BigDecimal("16.53"),
                TXN_DATE,
                "Invoice revenue recognition - INV#INV-1");

        assertThat(posted).isEqualTo(POSTED_ID);
        JournalEntryCreateRequest created = request.getValue();
        assertThat(created.getSourceEventId()).isEqualTo(SOURCE_EVENT_ID);
        assertThat(created.getTransactionDate()).isEqualTo(TXN_DATE);
        assertThat(created.getDescription()).isEqualTo("Invoice revenue recognition - INV#INV-1");

        List<JournalEntryCreateRequest.JournalEntryLineRequest> lines = created.getLines();
        assertThat(lines).hasSize(3);
        assertThat(line(lines, AR).getDebitAmount()).isEqualByComparingTo("216.53");
        assertThat(line(lines, AR).getCreditAmount()).isEqualByComparingTo("0");
        assertThat(line(lines, REVENUE).getCreditAmount()).isEqualByComparingTo("200.00");
        assertThat(line(lines, REVENUE).getDebitAmount()).isEqualByComparingTo("0");
        assertThat(line(lines, TAX_PAYABLE).getCreditAmount()).isEqualByComparingTo("16.53");
        assertThat(line(lines, TAX_PAYABLE).getDebitAmount()).isEqualByComparingTo("0");
        assertThat(sumDebits(lines)).isEqualByComparingTo(sumCredits(lines));
        assertThat(line(lines, AR).getDescription()).contains("INV#" + INVOICE_ID);

        verify(journalEntryService).postJournalEntry(CREATED_ID, null);
    }

    @Test
    @DisplayName("Revenue entry with zero tax omits the tax leg and still balances")
    void omitsZeroTaxLeg() {
        service.postInvoiceRevenue(
                SOURCE_EVENT_ID,
                INVOICE_ID,
                AR,
                REVENUE,
                TAX_PAYABLE,
                new BigDecimal("150.00"),
                BigDecimal.ZERO,
                TXN_DATE,
                "desc");

        List<JournalEntryCreateRequest.JournalEntryLineRequest> lines =
                request.getValue().getLines();
        assertThat(lines).hasSize(2);
        assertThat(lines).noneMatch(l -> TAX_PAYABLE.equals(l.getGlAccountId()));
        assertThat(line(lines, AR).getDebitAmount()).isEqualByComparingTo("150.00");
        assertThat(line(lines, REVENUE).getCreditAmount()).isEqualByComparingTo("150.00");
        assertThat(sumDebits(lines)).isEqualByComparingTo(sumCredits(lines));
    }

    @Test
    @DisplayName("Reversal entry: Dr Revenue / Dr Tax / Cr AR (total) — the exact mirror, balanced")
    void postsBalancedReversalEntry() {
        UUID posted = service.postInvoiceRevenueReversal(
                SOURCE_EVENT_ID,
                INVOICE_ID,
                AR,
                REVENUE,
                TAX_PAYABLE,
                new BigDecimal("200.00"),
                new BigDecimal("16.53"),
                TXN_DATE,
                "Invoice revenue reversal (DRAFT) - INV#INV-1");

        assertThat(posted).isEqualTo(POSTED_ID);
        JournalEntryCreateRequest created = request.getValue();
        assertThat(created.getSourceEventId()).isEqualTo(SOURCE_EVENT_ID);
        assertThat(created.getTransactionDate()).isEqualTo(TXN_DATE);

        List<JournalEntryCreateRequest.JournalEntryLineRequest> lines = created.getLines();
        assertThat(lines).hasSize(3);
        assertThat(line(lines, REVENUE).getDebitAmount()).isEqualByComparingTo("200.00");
        assertThat(line(lines, REVENUE).getCreditAmount()).isEqualByComparingTo("0");
        assertThat(line(lines, TAX_PAYABLE).getDebitAmount()).isEqualByComparingTo("16.53");
        assertThat(line(lines, TAX_PAYABLE).getCreditAmount()).isEqualByComparingTo("0");
        assertThat(line(lines, AR).getCreditAmount()).isEqualByComparingTo("216.53");
        assertThat(line(lines, AR).getDebitAmount()).isEqualByComparingTo("0");
        assertThat(sumDebits(lines)).isEqualByComparingTo(sumCredits(lines));

        verify(journalEntryService).postJournalEntry(CREATED_ID, null);
    }

    @Test
    @DisplayName("Reversal entry with zero tax omits the tax leg")
    void reversalOmitsZeroTaxLeg() {
        service.postInvoiceRevenueReversal(
                SOURCE_EVENT_ID,
                INVOICE_ID,
                AR,
                REVENUE,
                TAX_PAYABLE,
                new BigDecimal("150.00"),
                BigDecimal.ZERO,
                TXN_DATE,
                "d");

        List<JournalEntryCreateRequest.JournalEntryLineRequest> lines =
                request.getValue().getLines();
        assertThat(lines).hasSize(2);
        assertThat(lines).noneMatch(l -> TAX_PAYABLE.equals(l.getGlAccountId()));
        assertThat(sumDebits(lines)).isEqualByComparingTo(sumCredits(lines));
    }
}
