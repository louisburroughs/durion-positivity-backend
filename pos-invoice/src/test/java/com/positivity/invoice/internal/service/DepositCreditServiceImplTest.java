package com.positivity.invoice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.invoice.internal.entity.DepositCredit;
import com.positivity.invoice.internal.enums.DepositCreditStatus;
import com.positivity.invoice.internal.enums.DepositSourceType;
import com.positivity.invoice.internal.exception.DepositCreditNotFoundException;
import com.positivity.invoice.internal.repository.DepositCreditRepository;
import com.positivity.invoice.service.model.CreateDepositCommand;
import com.positivity.invoice.service.model.DepositCreditSummary;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for deposit / down-payment credits (odoo-parity story E4, #1085): idempotent take,
 * FIFO partial draw-down against a settlement invoice with an audit trail, and refund of the
 * remaining balance.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DepositCreditServiceImpl — parity story E4")
class DepositCreditServiceImplTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-23T12:00:00Z"), ZoneOffset.UTC);
    private static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID WORKORDER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID INVOICE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");

    @Mock
    private DepositCreditRepository repository;

    @Mock
    private com.positivity.invoice.internal.repository.DepositCreditApplicationRepository applicationRepository;

    private DepositCreditServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new DepositCreditServiceImpl(repository, applicationRepository, CLOCK);
    }

    private static DepositCredit credit(UUID id, String remaining, DepositCreditStatus status) {
        return DepositCredit.builder()
                .depositCreditId(id)
                .version(0)
                .sourceType(DepositSourceType.WORKORDER)
                .sourceId(WORKORDER_ID)
                .orderId(UUID.randomUUID())
                .currencyCode("USD")
                .originalAmount(new BigDecimal("200.0000"))
                .remainingBalance(new BigDecimal(remaining))
                .status(status)
                .applications(new ArrayList<>())
                .createdAt(Instant.parse("2026-07-23T08:00:00Z"))
                .build();
    }

    @Test
    @DisplayName("DCS-001: creating a deposit is idempotent on the taking orderId")
    void create_idempotentOnOrderId() {
        DepositCredit existing = credit(UUID.randomUUID(), "200.0000", DepositCreditStatus.AVAILABLE);
        existing.setOrderId(ORDER_ID);
        when(repository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(existing));

        DepositCreditSummary summary = service.createDeposit(
                new CreateDepositCommand(ORDER_ID, "WORKORDER", WORKORDER_ID, null, new BigDecimal("200.00"), "USD"));

        assertThat(summary.remainingBalance()).isEqualByComparingTo("200.00");
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("DCS-002: a fresh deposit is AVAILABLE with the full remaining balance")
    void create_freshDeposit() {
        when(repository.findByOrderId(ORDER_ID)).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        DepositCreditSummary summary = service.createDeposit(new CreateDepositCommand(
                ORDER_ID, "workorder", WORKORDER_ID, "party-1", new BigDecimal("200.00"), null));

        assertThat(summary.status()).isEqualTo("AVAILABLE");
        assertThat(summary.originalAmount()).isEqualByComparingTo("200.00");
        assertThat(summary.remainingBalance()).isEqualByComparingTo("200.00");
        assertThat(summary.currencyCode()).isEqualTo("USD");
    }

    @Test
    @DisplayName("DCS-003: applying a smaller invoice draws the credit down and records an audit row")
    void apply_partialDrawDown() {
        DepositCredit c = credit(UUID.randomUUID(), "200.0000", DepositCreditStatus.AVAILABLE);
        when(repository.findBySourceTypeAndSourceIdAndStatusInOrderByCreatedAtAsc(
                        eq(DepositSourceType.WORKORDER), eq(WORKORDER_ID), any()))
                .thenReturn(List.of(c));
        when(applicationRepository.findDepositCreditIdsByInvoiceId(INVOICE_ID)).thenReturn(java.util.Set.of());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        BigDecimal applied = service.applyAvailableCredits(
                DepositSourceType.WORKORDER, WORKORDER_ID, INVOICE_ID, new BigDecimal("120.00"));

        assertThat(applied).isEqualByComparingTo("120.00");
        assertThat(c.getRemainingBalance()).isEqualByComparingTo("80.00");
        assertThat(c.getStatus()).isEqualTo(DepositCreditStatus.PARTIALLY_APPLIED);
        assertThat(c.getApplications()).hasSize(1);
        assertThat(c.getApplications().get(0).getInvoiceId()).isEqualTo(INVOICE_ID);
        assertThat(c.getApplications().get(0).getAmountApplied()).isEqualByComparingTo("120.00");
    }

    @Test
    @DisplayName("DCS-004: a larger invoice fully consumes FIFO credits and caps at the invoice total")
    void apply_fifoCapsAtInvoiceTotal() {
        DepositCredit older = credit(
                UUID.fromString("00000000-0000-0000-0000-0000000000d1"), "100.0000", DepositCreditStatus.AVAILABLE);
        DepositCredit newer = credit(
                UUID.fromString("00000000-0000-0000-0000-0000000000d2"), "100.0000", DepositCreditStatus.AVAILABLE);
        when(repository.findBySourceTypeAndSourceIdAndStatusInOrderByCreatedAtAsc(
                        eq(DepositSourceType.WORKORDER), eq(WORKORDER_ID), any()))
                .thenReturn(List.of(older, newer));
        when(applicationRepository.findDepositCreditIdsByInvoiceId(INVOICE_ID)).thenReturn(java.util.Set.of());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // invoice 150: older fully consumed (100), newer partially (50)
        BigDecimal applied = service.applyAvailableCredits(
                DepositSourceType.WORKORDER, WORKORDER_ID, INVOICE_ID, new BigDecimal("150.00"));

        assertThat(applied).isEqualByComparingTo("150.00");
        assertThat(older.getStatus()).isEqualTo(DepositCreditStatus.FULLY_APPLIED);
        assertThat(older.getRemainingBalance()).isEqualByComparingTo("0.00");
        assertThat(newer.getStatus()).isEqualTo(DepositCreditStatus.PARTIALLY_APPLIED);
        assertThat(newer.getRemainingBalance()).isEqualByComparingTo("50.00");
    }

    @Test
    @DisplayName("DCS-005: a credit already applied to an invoice is not applied again")
    void apply_idempotentPerInvoice() {
        UUID creditId = UUID.fromString("00000000-0000-0000-0000-0000000000e9");
        DepositCredit c = credit(creditId, "80.0000", DepositCreditStatus.PARTIALLY_APPLIED);
        when(repository.findBySourceTypeAndSourceIdAndStatusInOrderByCreatedAtAsc(
                        eq(DepositSourceType.WORKORDER), eq(WORKORDER_ID), any()))
                .thenReturn(List.of(c));
        // Single-query idempotency: this credit was already applied to the invoice.
        when(applicationRepository.findDepositCreditIdsByInvoiceId(INVOICE_ID)).thenReturn(java.util.Set.of(creditId));

        BigDecimal applied = service.applyAvailableCredits(
                DepositSourceType.WORKORDER, WORKORDER_ID, INVOICE_ID, new BigDecimal("50.00"));

        assertThat(applied).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("DCS-006: refund zeroes the remaining balance and marks REFUNDED")
    void refund_zeroesRemaining() {
        UUID id = UUID.randomUUID();
        DepositCredit c = credit(id, "200.0000", DepositCreditStatus.AVAILABLE);
        when(repository.findById(id)).thenReturn(Optional.of(c));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        BigDecimal refunded = service.refundDeposit(id, "workorder cancelled");

        assertThat(refunded).isEqualByComparingTo("200.00");
        assertThat(c.getStatus()).isEqualTo(DepositCreditStatus.REFUNDED);
        assertThat(c.getRemainingBalance()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("DCS-007: refunding an already-refunded credit is a zero no-op")
    void refund_alreadyRefunded() {
        UUID id = UUID.randomUUID();
        DepositCredit c = credit(id, "0.0000", DepositCreditStatus.REFUNDED);
        when(repository.findById(id)).thenReturn(Optional.of(c));

        BigDecimal refunded = service.refundDeposit(id, "again");

        assertThat(refunded).isEqualByComparingTo("0.00");
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("DCS-008: an unknown deposit id is a 404")
    void get_notFound() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getDeposit(id)).isInstanceOf(DepositCreditNotFoundException.class);
    }
}
