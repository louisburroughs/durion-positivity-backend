package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.dto.JournalEntryCreateRequest;
import com.positivity.accounting.internal.dto.JournalEntryResponse;
import com.positivity.accounting.internal.dto.SettlementPostingCommand;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Every direct posting path stamps the entry it creates with a non-blank source type and the
 * caller's deterministic source event id (#2434).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GLPostingService source event (#2434)")
class GLPostingSourceEventTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-03T00:00:00Z"), ZoneOffset.UTC);
    private static final LocalDateTime TXN = LocalDateTime.of(2026, 10, 1, 9, 0);
    private static final BigDecimal AMOUNT = new BigDecimal("12.50");
    private static final BigDecimal TAX = new BigDecimal("1.25");

    @Mock
    private JournalEntryService journalEntryService;

    private GLPostingServiceImpl service;
    private final UUID sourceEventId = UUID.randomUUID();
    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();
    private final UUID c = UUID.randomUUID();
    private final UUID d = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new GLPostingServiceImpl(CLOCK, TestZoneResolvers.utc(CLOCK), journalEntryService);
    }

    private void assertSource(Function<GLPostingServiceImpl, UUID> call, String expectedType) {
        UUID entryId = UUID.randomUUID();
        ArgumentCaptor<JournalEntryCreateRequest> captor = ArgumentCaptor.forClass(JournalEntryCreateRequest.class);
        when(journalEntryService.createJournalEntry(captor.capture()))
                .thenReturn(
                        JournalEntryResponse.builder().journalEntryId(entryId).build());
        when(journalEntryService.postJournalEntry(any(UUID.class), any()))
                .thenReturn(
                        JournalEntryResponse.builder().journalEntryId(entryId).build());

        call.apply(service);

        JournalEntryCreateRequest request = captor.getValue();
        assertThat(request.getSourceEventType()).isEqualTo(expectedType);
        assertThat(request.getSourceEventId()).isEqualTo(sourceEventId);
    }

    @Test
    void creditMemoReversal() {
        assertSource(
                s -> s.postCreditMemoReversal(sourceEventId, a, b, c, AMOUNT, TAX, "cm", false, null, null),
                JournalEntrySourceTypes.CREDIT_MEMO_REVERSAL);
    }

    @Test
    @DisplayName("#2558: a credit-memo reversal and void at 2026-01-31T23:30-06:00 are dated"
            + " 2026-01-31 in a Chicago calendar, clock in UTC")
    void creditMemoEntriesAreDatedInTheTenantCalendar() {
        Clock utc = Clock.fixed(TestZoneResolvers.JAN_31_2330_CHICAGO, ZoneOffset.UTC);
        GLPostingServiceImpl chicago = new GLPostingServiceImpl(
                utc, TestZoneResolvers.fixed(TestZoneResolvers.CHICAGO, utc), journalEntryService);
        ArgumentCaptor<JournalEntryCreateRequest> captor = ArgumentCaptor.forClass(JournalEntryCreateRequest.class);
        UUID entryId = UUID.randomUUID();
        when(journalEntryService.createJournalEntry(captor.capture()))
                .thenReturn(
                        JournalEntryResponse.builder().journalEntryId(entryId).build());
        when(journalEntryService.postJournalEntry(any(UUID.class), any()))
                .thenReturn(
                        JournalEntryResponse.builder().journalEntryId(entryId).build());

        chicago.postCreditMemoReversal(sourceEventId, a, b, c, AMOUNT, TAX, "cm", false, null, null);
        chicago.postCreditMemoVoid(sourceEventId, a, b, c, AMOUNT, TAX, "void");

        assertThat(captor.getAllValues())
                .extracting(JournalEntryCreateRequest::getTransactionDate)
                .containsOnly(LocalDateTime.of(2026, 1, 31, 23, 30));
    }

    @Test
    void creditMemoVoid() {
        assertSource(
                s -> s.postCreditMemoVoid(sourceEventId, a, b, c, AMOUNT, TAX, "void"),
                JournalEntrySourceTypes.CREDIT_MEMO_VOID);
    }

    @Test
    void paymentApplication() {
        assertSource(
                s -> s.postPaymentApplication(sourceEventId, a, b, AMOUNT, TXN, "pay", null),
                JournalEntrySourceTypes.PAYMENT_APPLICATION);
    }

    @Test
    void customerCreditIssuance() {
        assertSource(
                s -> s.postCustomerCreditIssuance(sourceEventId, d, a, b, AMOUNT, TXN, "issue", null),
                JournalEntrySourceTypes.CUSTOMER_CREDIT_ISSUANCE);
    }

    @Test
    void customerCreditRelief() {
        assertSource(
                s -> s.postCustomerCreditRelief(sourceEventId, d, a, b, AMOUNT, TXN, "relief", "Contra", null),
                JournalEntrySourceTypes.CUSTOMER_CREDIT_RELIEF);
    }

    @Test
    void inventoryShrinkage() {
        assertSource(
                s -> s.postInventoryShrinkage(sourceEventId, d, a, b, AMOUNT, TXN, "scrap", null),
                JournalEntrySourceTypes.INVENTORY_SHRINKAGE);
    }

    @Test
    void inventoryAdjustment() {
        assertSource(
                s -> s.postInventoryAdjustment(sourceEventId, d, a, b, AMOUNT, TXN, "adjust", null),
                JournalEntrySourceTypes.INVENTORY_ADJUSTMENT);
    }

    @Test
    void inventoryRevaluation() {
        assertSource(
                s -> s.postInventoryRevaluation(sourceEventId, d, a, b, AMOUNT, TXN, "revalue", null),
                JournalEntrySourceTypes.INVENTORY_REVALUATION);
    }

    @Test
    void settlement() {
        assertSource(
                s -> s.postSettlement(new SettlementPostingCommand(
                        sourceEventId,
                        a,
                        b,
                        c,
                        d,
                        AMOUNT,
                        BigDecimal.ZERO,
                        AMOUNT,
                        BigDecimal.ZERO,
                        TXN,
                        "settle",
                        null)),
                JournalEntrySourceTypes.SETTLEMENT);
    }

    @Test
    void settlementWriteOff() {
        assertSource(
                s -> s.postSettlementWriteOff(sourceEventId, a, b, AMOUNT, TXN, "write-off", null),
                JournalEntrySourceTypes.SETTLEMENT_WRITE_OFF);
    }

    @Test
    void settlementReclass() {
        assertSource(
                s -> s.postSettlementReclass(sourceEventId, a, b, AMOUNT, TXN, "reclass", null),
                JournalEntrySourceTypes.SETTLEMENT_RECLASS);
    }

    @Test
    void registerOverShort() {
        assertSource(
                s -> s.postRegisterOverShort(sourceEventId, d, a, b, AMOUNT, TXN, "over/short", null),
                JournalEntrySourceTypes.REGISTER_OVER_SHORT);
    }

    @Test
    void registerCashMovement() {
        assertSource(
                s -> s.postRegisterCashMovement(
                        sourceEventId,
                        a,
                        b,
                        AMOUNT,
                        TXN,
                        "petty expense",
                        "Drawer petty expense",
                        Map.of("registerId", "T-1", "sessionId", d.toString())),
                JournalEntrySourceTypes.REGISTER_CASH_MOVEMENT);
    }

    @Test
    @DisplayName(
            "#2513: a drawer movement is one Dr / Cr pair of its amount, both lines carrying the session's dimensions")
    void registerCashMovementLinesCarryDimensions() {
        UUID entryId = UUID.randomUUID();
        ArgumentCaptor<JournalEntryCreateRequest> captor = ArgumentCaptor.forClass(JournalEntryCreateRequest.class);
        when(journalEntryService.createJournalEntry(captor.capture()))
                .thenReturn(
                        JournalEntryResponse.builder().journalEntryId(entryId).build());
        when(journalEntryService.postJournalEntry(any(UUID.class), any()))
                .thenReturn(
                        JournalEntryResponse.builder().journalEntryId(entryId).build());
        Map<String, String> dimensions =
                Map.of("registerId", "T-1", "sessionId", d.toString(), "locationId", c.toString());

        assertThat(service.postRegisterCashMovement(
                        sourceEventId, a, b, AMOUNT, TXN, "petty expense", "Drawer petty expense", dimensions))
                .isEqualTo(entryId);

        JournalEntryCreateRequest request = captor.getValue();
        assertThat(request.getTransactionDate()).isEqualTo(TXN);
        assertThat(request.getDescription()).isEqualTo("petty expense");
        assertThat(request.getLines()).hasSize(2);
        assertThat(request.getLines().get(0).getGlAccountId()).isEqualTo(a);
        assertThat(request.getLines().get(0).getDebitAmount()).isEqualByComparingTo(AMOUNT);
        assertThat(request.getLines().get(0).getCreditAmount()).isZero();
        assertThat(request.getLines().get(1).getGlAccountId()).isEqualTo(b);
        assertThat(request.getLines().get(1).getCreditAmount()).isEqualByComparingTo(AMOUNT);
        assertThat(request.getLines().get(1).getDebitAmount()).isZero();
        assertThat(request.getLines())
                .allSatisfy(line -> assertThat(line.getDimensions()).isEqualTo(dimensions));
    }

    @Test
    void invoiceRevenue() {
        assertSource(
                s -> s.postInvoiceRevenue(sourceEventId, d, a, b, c, AMOUNT, TAX, TXN, "revenue"),
                JournalEntrySourceTypes.INVOICE_REVENUE);
    }

    @Test
    void invoiceRevenueReversal() {
        assertSource(
                s -> s.postInvoiceRevenueReversal(sourceEventId, d, a, b, c, AMOUNT, TAX, TXN, "reversal"),
                JournalEntrySourceTypes.INVOICE_REVENUE_REVERSAL);
    }
}
