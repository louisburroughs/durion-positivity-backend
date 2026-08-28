package com.positivity.accounting.internal.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.dto.CustomerCreditReliefGLPostingEvent;
import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.internal.enums.CustomerCreditTransactionType;
import com.positivity.accounting.internal.exception.AccountingPeriodClosedException;
import com.positivity.accounting.internal.service.CustomerCreditPostingLifecycleService;
import com.positivity.accounting.internal.service.GLMappingResolver;
import com.positivity.accounting.internal.service.GLPostingService;
import com.positivity.accounting.internal.service.IdempotencyService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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

/**
 * Unit tests for the customer-credit relief posting handler (issue #992): account resolution per
 * relief type, idempotency, posting write-back, and period-gate propagation.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CustomerCreditReliefGLPostingEventHandler (#992)")
class CustomerCreditReliefGLPostingEventHandlerTest {

    private static final Instant RELIEF_AT = Instant.parse("2026-07-22T12:00:00Z");
    private static final LocalDateTime RELIEF_DATE = LocalDateTime.ofInstant(RELIEF_AT, ZoneOffset.UTC);

    private static final UUID CREDIT_ID = UUID.randomUUID();
    private static final UUID CREDIT_TRANSACTION_ID = UUID.randomUUID();
    private static final UUID INVOICE_ID = UUID.randomUUID();
    private static final UUID LIABILITY_ACCOUNT = UUID.randomUUID();
    private static final UUID CONTRA_ACCOUNT = UUID.randomUUID();
    private static final UUID JOURNAL_ENTRY_ID = UUID.randomUUID();

    @Mock
    private IdempotencyService idempotencyService;

    @Mock
    private GLMappingResolver glMappingResolver;

    @Mock
    private GLPostingService glPostingService;

    @Mock
    private CustomerCreditPostingLifecycleService postingLifecycleService;

    private CustomerCreditReliefGLPostingEventHandler handler;

    @BeforeEach
    void setUp() {
        handler = new CustomerCreditReliefGLPostingEventHandler(
                Clock.fixed(RELIEF_AT, ZoneOffset.UTC),
                idempotencyService,
                glMappingResolver,
                glPostingService,
                postingLifecycleService);

        when(idempotencyService.isKeyProcessed(any())).thenReturn(false);
        when(glPostingService.postCustomerCreditRelief(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(postedEntry());
    }

    @Test
    @DisplayName("APPLICATION resolves the CUSTOMER_CREDIT_APPLICATION category: Dr 2300 / Cr AR")
    void application_resolvesArContraAccount() {
        stubMapping("CUSTOMER_CREDIT_APPLICATION", "ACCOUNTS_RECEIVABLE");

        handler.onCustomerCreditReliefGLPosting(event(CustomerCreditTransactionType.APPLICATION, INVOICE_ID));

        ArgumentCaptor<String> label = ArgumentCaptor.forClass(String.class);
        verify(glPostingService)
                .postCustomerCreditRelief(
                        any(),
                        eq(CREDIT_ID),
                        eq(LIABILITY_ACCOUNT),
                        eq(CONTRA_ACCOUNT),
                        eq(new BigDecimal("250.00")),
                        eq(RELIEF_DATE),
                        any(),
                        label.capture(),
                        eq(null));
        assertThat(label.getValue()).isEqualTo("AR Reduction");

        verify(postingLifecycleService).recordReliefPosting(CREDIT_TRANSACTION_ID, JOURNAL_ENTRY_ID);
        verify(idempotencyService).registerKey(any(), eq(JOURNAL_ENTRY_ID));
    }

    @Test
    @DisplayName("REFUND resolves the CUSTOMER_CREDIT_REFUND category: Dr 2300 / Cr Undeposited Funds")
    void refund_resolvesUndepositedFundsContraAccount() {
        stubMapping("CUSTOMER_CREDIT_REFUND", "UNDEPOSITED_FUNDS");

        handler.onCustomerCreditReliefGLPosting(event(CustomerCreditTransactionType.REFUND, null));

        verify(glMappingResolver).resolveGLAccount("CUSTOMER_CREDIT_REFUND", "CUSTOMER_CREDIT_LIABILITY", RELIEF_DATE);
        verify(glMappingResolver).resolveGLAccount("CUSTOMER_CREDIT_REFUND", "UNDEPOSITED_FUNDS", RELIEF_DATE);
        verify(postingLifecycleService).recordReliefPosting(CREDIT_TRANSACTION_ID, JOURNAL_ENTRY_ID);
    }

    @Test
    @DisplayName("An already-processed request id posts nothing")
    void alreadyProcessed_skipsPosting() {
        when(idempotencyService.isKeyProcessed(any())).thenReturn(true);

        handler.onCustomerCreditReliefGLPosting(event(CustomerCreditTransactionType.APPLICATION, INVOICE_ID));

        verifyNoInteractions(glPostingService, glMappingResolver, postingLifecycleService);
    }

    @Test
    @DisplayName("Application and refund of the same request id derive distinct journal source ids")
    void sourceEventId_isNamespacedPerReliefType() {
        assertThat(CustomerCreditReliefGLPostingEventHandler.toSourceEventId(
                        CustomerCreditTransactionType.APPLICATION, "req-1"))
                .isNotEqualTo(CustomerCreditReliefGLPostingEventHandler.toSourceEventId(
                        CustomerCreditTransactionType.REFUND, "req-1"));
        // Deterministic across replays.
        assertThat(CustomerCreditReliefGLPostingEventHandler.toSourceEventId(
                        CustomerCreditTransactionType.APPLICATION, "req-1"))
                .isEqualTo(CustomerCreditReliefGLPostingEventHandler.toSourceEventId(
                        CustomerCreditTransactionType.APPLICATION, "req-1"));
    }

    @Test
    @DisplayName("A closed period propagates unwrapped so the outbox retry keeps the reason visible")
    void closedPeriod_propagatesUnwrapped() {
        stubMapping("CUSTOMER_CREDIT_APPLICATION", "ACCOUNTS_RECEIVABLE");
        when(glPostingService.postCustomerCreditRelief(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new AccountingPeriodClosedException("2026-07", "2026-07 is closed"));

        assertThatThrownBy(() -> handler.onCustomerCreditReliefGLPosting(
                        event(CustomerCreditTransactionType.APPLICATION, INVOICE_ID)))
                .isInstanceOf(AccountingPeriodClosedException.class);

        verify(idempotencyService, never()).registerKey(any(), any());
        verify(postingLifecycleService, never()).recordReliefPosting(any(), any());
    }

    // ===== helpers =====

    private void stubMapping(String category, String contraKey) {
        when(glMappingResolver.resolveGLAccount(category, "CUSTOMER_CREDIT_LIABILITY", RELIEF_DATE))
                .thenReturn(LIABILITY_ACCOUNT);
        when(glMappingResolver.resolveGLAccount(category, contraKey, RELIEF_DATE))
                .thenReturn(CONTRA_ACCOUNT);
    }

    private static JournalEntry postedEntry() {
        JournalEntry entry = new JournalEntry();
        entry.setJournalEntryId(JOURNAL_ENTRY_ID);
        return entry;
    }

    private static CustomerCreditReliefGLPostingEvent event(CustomerCreditTransactionType type, UUID invoiceId) {
        return CustomerCreditReliefGLPostingEvent.builder()
                .eventId(UUID.randomUUID())
                .requestId("req-1")
                .creditTransactionId(CREDIT_TRANSACTION_ID)
                .creditId(CREDIT_ID)
                .transactionType(type)
                .invoiceId(invoiceId)
                .customerId(UUID.randomUUID())
                .currency("USD")
                .amount(new BigDecimal("250.00"))
                .reliefTimestamp(RELIEF_AT)
                .build();
    }
}
