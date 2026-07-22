package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.dto.CustomerCreditApplicationRequest;
import com.positivity.accounting.internal.dto.CustomerCreditRefundRequest;
import com.positivity.accounting.internal.dto.CustomerCreditReliefGLPostingEvent;
import com.positivity.accounting.internal.dto.CustomerCreditTransactionResponse;
import com.positivity.accounting.internal.entity.CustomerCredit;
import com.positivity.accounting.internal.entity.CustomerCreditTransaction;
import com.positivity.accounting.internal.entity.ExtInvoice;
import com.positivity.accounting.internal.enums.CustomerCreditStatus;
import com.positivity.accounting.internal.enums.CustomerCreditTransactionType;
import com.positivity.accounting.internal.repository.CustomerCreditRepository;
import com.positivity.accounting.internal.repository.CustomerCreditTransactionRepository;
import com.positivity.accounting.service.OutboxService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Unit tests for customer-credit draw-down (issue #992): the validation, idempotency, status
 * derivation, and outbox enqueue that guard the 2300 liability relief.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CustomerCreditServiceImpl (#992)")
class CustomerCreditServiceImplTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-07-22T12:00:00Z");
    private static final String USER = "tester";

    private static final UUID CREDIT_ID = UUID.randomUUID();
    private static final UUID CUSTOMER_ID = UUID.randomUUID();
    private static final UUID INVOICE_ID = UUID.randomUUID();

    @Mock
    private CustomerCreditRepository customerCreditRepository;

    @Mock
    private CustomerCreditTransactionRepository creditTransactionRepository;

    @Mock
    private InvoiceBalanceCalculator invoiceBalanceCalculator;

    @Mock
    private OutboxService outboxService;

    private CustomerCreditServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new CustomerCreditServiceImpl(
                Clock.fixed(FIXED_NOW, ZoneOffset.UTC),
                customerCreditRepository,
                creditTransactionRepository,
                invoiceBalanceCalculator,
                outboxService);

        when(creditTransactionRepository.findByRequestId(any())).thenReturn(Optional.empty());
        when(creditTransactionRepository.save(any())).thenAnswer(invocation -> {
            CustomerCreditTransaction saved = invocation.getArgument(0);
            saved.setCreditTransactionId(UUID.randomUUID());
            return saved;
        });
        when(customerCreditRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName(
            "Applying part of a credit records the draw-down, marks it PARTIALLY_CONSUMED, and enqueues the relief")
    void partialApplication_recordsDrawDownAndEnqueuesRelief() {
        CustomerCredit credit = credit(new BigDecimal("600.00"));
        stubCredit(credit);
        stubInvoice(new BigDecimal("1000.00"));

        CustomerCreditTransactionResponse response =
                service.applyCreditToInvoice(CREDIT_ID, applicationRequest("req-1", new BigDecimal("250.00")), USER);

        assertThat(response.getTransactionType()).isEqualTo(CustomerCreditTransactionType.APPLICATION);
        assertThat(response.getInvoiceId()).isEqualTo(INVOICE_ID);
        assertThat(response.getCreditOpenAmountAfter()).isEqualByComparingTo("350.00");
        assertThat(response.getInvoiceBalanceAfter()).isEqualByComparingTo("750.00");
        assertThat(response.isReplayed()).isFalse();

        assertThat(credit.getAppliedAmount()).isEqualByComparingTo("250.00");
        assertThat(credit.getStatus()).isEqualTo(CustomerCreditStatus.PARTIALLY_CONSUMED);

        CustomerCreditReliefGLPostingEvent event = capturedReliefEvent();
        assertThat(event.getTransactionType()).isEqualTo(CustomerCreditTransactionType.APPLICATION);
        assertThat(event.getAmount()).isEqualByComparingTo("250.00");
        assertThat(event.getInvoiceId()).isEqualTo(INVOICE_ID);
        assertThat(event.getReliefTimestamp()).isEqualTo(FIXED_NOW);
    }

    @Test
    @DisplayName("Applying the whole credit marks it CONSUMED")
    void fullApplication_marksConsumed() {
        CustomerCredit credit = credit(new BigDecimal("600.00"));
        stubCredit(credit);
        stubInvoice(new BigDecimal("1000.00"));

        service.applyCreditToInvoice(CREDIT_ID, applicationRequest("req-2", new BigDecimal("600.00")), USER);

        assertThat(credit.getStatus()).isEqualTo(CustomerCreditStatus.CONSUMED);
        assertThat(credit.getOpenAmount()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("Over-drawing a credit is a 409 and enqueues nothing")
    void overDraw_isConflict() {
        CustomerCredit credit = credit(new BigDecimal("100.00"));
        credit.setAppliedAmount(new BigDecimal("80.00"));
        stubCredit(credit);
        stubInvoice(new BigDecimal("1000.00"));

        assertThatThrownBy(() -> service.applyCreditToInvoice(
                        CREDIT_ID, applicationRequest("req-3", new BigDecimal("50.00")), USER))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("exceeds the open amount")
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);

        verify(outboxService, never()).saveToOutbox(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Applying more than the invoice's balance due is a 409")
    void exceedsInvoiceBalance_isConflict() {
        stubCredit(credit(new BigDecimal("600.00")));
        stubInvoice(new BigDecimal("100.00"));

        assertThatThrownBy(() -> service.applyCreditToInvoice(
                        CREDIT_ID, applicationRequest("req-4", new BigDecimal("250.00")), USER))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("exceeds invoice");

        verify(outboxService, never()).saveToOutbox(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Applying to a non-AR-eligible invoice is a 409")
    void invoiceNotArEligible_isConflict() {
        stubCredit(credit(new BigDecimal("600.00")));
        ExtInvoice invoice = invoice(new BigDecimal("1000.00"));
        invoice.setStatus("DRAFT");
        when(invoiceBalanceCalculator.findInvoice(INVOICE_ID)).thenReturn(Optional.of(invoice));
        when(invoiceBalanceCalculator.isArEligible(invoice)).thenReturn(false);

        assertThatThrownBy(() -> service.applyCreditToInvoice(
                        CREDIT_ID, applicationRequest("req-5", new BigDecimal("250.00")), USER))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("cannot be applied to DRAFT");
    }

    @Test
    @DisplayName("Applying a credit to another customer's invoice is a 409")
    void invoiceBelongsToAnotherCustomer_isConflict() {
        stubCredit(credit(new BigDecimal("600.00")));
        ExtInvoice invoice = invoice(new BigDecimal("1000.00"));
        invoice.setPartyId(UUID.randomUUID().toString());
        when(invoiceBalanceCalculator.findInvoice(INVOICE_ID)).thenReturn(Optional.of(invoice));
        when(invoiceBalanceCalculator.isArEligible(invoice)).thenReturn(true);

        assertThatThrownBy(() -> service.applyCreditToInvoice(
                        CREDIT_ID, applicationRequest("req-6", new BigDecimal("250.00")), USER))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("different customer");
    }

    @Test
    @DisplayName("Refunding posts a REFUND relief with no invoice and reduces the open amount")
    void refund_recordsDrawDownWithoutInvoice() {
        CustomerCredit credit = credit(new BigDecimal("600.00"));
        stubCredit(credit);

        CustomerCreditTransactionResponse response =
                service.refundCredit(CREDIT_ID, refundRequest("req-7", new BigDecimal("600.00")), USER);

        assertThat(response.getTransactionType()).isEqualTo(CustomerCreditTransactionType.REFUND);
        assertThat(response.getInvoiceId()).isNull();
        assertThat(response.getInvoiceBalanceAfter()).isNull();
        assertThat(credit.getRefundedAmount()).isEqualByComparingTo("600.00");
        assertThat(credit.getStatus()).isEqualTo(CustomerCreditStatus.CONSUMED);

        CustomerCreditReliefGLPostingEvent event = capturedReliefEvent();
        assertThat(event.getTransactionType()).isEqualTo(CustomerCreditTransactionType.REFUND);
        assertThat(event.getInvoiceId()).isNull();
    }

    @Test
    @DisplayName("A replayed requestId returns the original draw-down and relieves nothing further")
    void replayedRequestId_isANoOp() {
        CustomerCredit credit = credit(new BigDecimal("600.00"));
        credit.setRefundedAmount(new BigDecimal("200.00"));
        stubCredit(credit);

        CustomerCreditTransaction existing = CustomerCreditTransaction.builder()
                .creditTransactionId(UUID.randomUUID())
                .creditId(CREDIT_ID)
                .transactionType(CustomerCreditTransactionType.REFUND)
                .amount(new BigDecimal("200.00"))
                .currency("USD")
                .requestId("req-dup")
                .createdAt(FIXED_NOW)
                .build();
        when(creditTransactionRepository.findByRequestId("req-dup")).thenReturn(Optional.of(existing));

        CustomerCreditTransactionResponse response =
                service.refundCredit(CREDIT_ID, refundRequest("req-dup", new BigDecimal("200.00")), USER);

        assertThat(response.isReplayed()).isTrue();
        assertThat(response.getCreditTransactionId()).isEqualTo(existing.getCreditTransactionId());
        assertThat(response.getCreditOpenAmountAfter()).isEqualByComparingTo("400.00");

        verify(creditTransactionRepository, never()).save(any());
        verify(outboxService, never()).saveToOutbox(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("An unknown credit is a 404")
    void unknownCredit_isNotFound() {
        when(customerCreditRepository.findById(CREDIT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.refundCredit(CREDIT_ID, refundRequest("req-8", BigDecimal.ONE), USER))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ===== helpers =====

    private CustomerCreditReliefGLPostingEvent capturedReliefEvent() {
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outboxService).saveToOutbox(any(), eq("CustomerCreditRelief"), eq(CREDIT_ID), any(), payload.capture());
        return (CustomerCreditReliefGLPostingEvent) payload.getValue();
    }

    private void stubCredit(CustomerCredit credit) {
        when(customerCreditRepository.findById(CREDIT_ID)).thenReturn(Optional.of(credit));
    }

    private void stubInvoice(BigDecimal balanceDue) {
        ExtInvoice invoice = invoice(balanceDue);
        when(invoiceBalanceCalculator.findInvoice(INVOICE_ID)).thenReturn(Optional.of(invoice));
        when(invoiceBalanceCalculator.isArEligible(invoice)).thenReturn(true);
        when(invoiceBalanceCalculator.balanceDue(invoice)).thenReturn(balanceDue);
    }

    private static CustomerCredit credit(BigDecimal amount) {
        CustomerCredit credit = new CustomerCredit();
        credit.setCreditId(CREDIT_ID);
        credit.setCustomerId(CUSTOMER_ID);
        credit.setCurrency("USD");
        credit.setAmount(amount);
        credit.setSourcePaymentId(UUID.randomUUID());
        credit.setStatus(CustomerCreditStatus.AVAILABLE);
        credit.setAppliedAmount(BigDecimal.ZERO);
        credit.setRefundedAmount(BigDecimal.ZERO);
        credit.setCreatedAt(FIXED_NOW);
        return credit;
    }

    private static ExtInvoice invoice(BigDecimal total) {
        return ExtInvoice.builder()
                .invoiceId(INVOICE_ID)
                .partyId(CUSTOMER_ID.toString())
                .status("FINALIZED")
                .total(total)
                .invoiceCreatedAt(FIXED_NOW)
                .finalizedAt(FIXED_NOW)
                .aggregateVersion(1L)
                .updatedAt(FIXED_NOW)
                .build();
    }

    private static CustomerCreditApplicationRequest applicationRequest(String requestId, BigDecimal amount) {
        return CustomerCreditApplicationRequest.builder()
                .requestId(requestId)
                .invoiceId(INVOICE_ID)
                .amount(amount)
                .build();
    }

    private static CustomerCreditRefundRequest refundRequest(String requestId, BigDecimal amount) {
        return CustomerCreditRefundRequest.builder()
                .requestId(requestId)
                .amount(amount)
                .build();
    }
}
