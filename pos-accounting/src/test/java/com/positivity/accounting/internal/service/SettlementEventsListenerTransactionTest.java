package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.accounting.internal.config.LedgerCurrency;
import com.positivity.accounting.internal.entity.AccountingEvent;
import com.positivity.accounting.internal.enums.AccountingEventStatus;
import com.positivity.accounting.internal.repository.AccountingEventRepository;
import com.positivity.accounting.internal.repository.ExtInvoiceDepositCreditApplicationRepository;
import com.positivity.accounting.internal.repository.ExtInvoicePaymentReversalRepository;
import com.positivity.accounting.internal.repository.ProcessedEventRepository;
import com.positivity.accounting.internal.repository.ReceivablePaymentRepository;
import com.positivity.domainevents.payment.PaymentSettledV1;
import com.positivity.tenancy.TenantContext;
import com.positivity.tenancy.testing.TenantTestSupport;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Pins {@link SettlementEventsListener}'s transaction shape (ADR-0044 as amended by #2146, PR
 * #2324 review) against a real transaction manager: the listener method opens no transaction of its
 * own, and a settled payment held for its currency (ADR-0067 PC-9) commits its {@code SUSPENDED /
 * CURRENCY_NOT_SUPPORTED} record and its processed mark together in a {@code REQUIRES_NEW}
 * transaction, independent of any caller's transaction.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("SettlementEventsListener transaction shape")
class SettlementEventsListenerTransactionTest {

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private AccountingEventRepository accountingEventRepository;

    @Autowired
    private SettlementReconciliationService reconciliationService;

    @Autowired
    private PaymentApplicationService paymentApplicationService;

    @Autowired
    private ExtInvoicePaymentReversalRepository extInvoicePaymentReversalRepository;

    @Autowired
    private ExtInvoiceDepositCreditApplicationRepository extInvoiceDepositCreditApplicationRepository;

    @Autowired
    private KafkaFactIngestionRecorder ingestionRecorder;

    @Autowired
    private ObjectProvider<MeterRegistry> meterRegistry;

    @Autowired
    private AutomaticPaymentApplicationService automaticPaymentApplicationService;

    @Autowired
    private ReceivablePaymentRepository receivablePaymentRepository;

    private final ObjectMapper mapper = new ObjectMapper();
    private SettlementEventsListener listener;
    private String eventId;
    private UUID paymentIntentId;

    @BeforeEach
    void setUp() {
        TenantContext.bind(TenantTestSupport.TENANT_A);
        eventId = UUID.randomUUID().toString();
        paymentIntentId = UUID.randomUUID();
        listener = listenerWith(automaticPaymentApplicationService);
    }

    private SettlementEventsListener listenerWith(AutomaticPaymentApplicationService automatic) {
        return new SettlementEventsListener(
                Clock.systemUTC(),
                mapper,
                processedEventRepository,
                reconciliationService,
                paymentApplicationService,
                extInvoicePaymentReversalRepository,
                extInvoiceDepositCreditApplicationRepository,
                new LedgerCurrency("USD"),
                ingestionRecorder,
                automatic,
                meterRegistry,
                transactionManager);
    }

    @AfterEach
    void tearDown() {
        // Held records carry no reprocessing_attempt_history rows, so they can be deleted directly.
        accountingEventRepository.deleteAll(heldRecords());
        receivablePaymentRepository.findById(paymentIntentId).ifPresent(receivablePaymentRepository::delete);
        processedEventRepository.deleteById(eventId);
        TenantContext.clear();
    }

    @Test
    @DisplayName("A foreign-currency hold commits its record and processed mark with no caller transaction")
    void foreignCurrencyHoldCommitsWithoutCallerTransaction() {
        assertThatCode(() -> listener.onPaymentEvent(eurSettled())).doesNotThrowAnyException();

        assertThat(processedEventRepository.existsById(eventId)).isTrue();
        List<AccountingEvent> held = heldRecords();
        assertThat(held).hasSize(1);
        AccountingEvent record = held.getFirst();
        assertThat(record.getStatus()).isEqualTo(AccountingEventStatus.SUSPENDED);
        assertThat(record.getFailureReasonCode()).isEqualTo("CURRENCY_NOT_SUPPORTED");
        assertThat(record.getSourceSystem()).isEqualTo("pos-invoice");
        assertThat(record.getIngestionId()).isEqualTo(UUID.fromString(eventId));
    }

    @Test
    @DisplayName("The hold commits in its own transaction even when an enclosing transaction rolls back")
    void foreignCurrencyHoldCommitsIndependentlyOfEnclosingTransaction() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            listener.onPaymentEvent(eurSettled());
            status.setRollbackOnly();
        });

        assertThat(processedEventRepository.existsById(eventId)).isTrue();
        assertThat(heldRecords()).hasSize(1);
    }

    @Test
    @DisplayName("#2503: the payment, its automatic-application outcome row and the processed mark commit together")
    void settledPaymentAndOutcomeCommitTogether() {
        // The invoice is not replicated, so the automatic application holds it: SUSPENDED / INVOICE_NOT_FOUND.
        listener.onPaymentEvent(settled("USD"));

        assertThat(processedEventRepository.existsById(eventId)).isTrue();
        assertThat(receivablePaymentRepository.findById(paymentIntentId)).isPresent();
        assertThat(heldRecords()).singleElement().satisfies(record -> {
            assertThat(record.getStatus()).isEqualTo(AccountingEventStatus.SUSPENDED);
            assertThat(record.getFailureReasonCode()).isEqualTo("INVOICE_NOT_FOUND");
            assertThat(record.getSourceSystem()).isEqualTo("pos-invoice");
        });
    }

    @Test
    @DisplayName("#2503: a failing automatic application rolls the recorded payment back and leaves the event"
            + " unmarked, for redelivery")
    void failingAutomaticApplicationRollsEverythingBack() {
        AutomaticPaymentApplicationService failing = org.mockito.Mockito.mock(AutomaticPaymentApplicationService.class);
        org.mockito.Mockito.when(failing.applySettled(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("optimistic lock"));

        assertThatThrownBy(() -> listenerWith(failing).onPaymentEvent(settled("USD")))
                .hasMessageContaining("optimistic lock");

        assertThat(processedEventRepository.existsById(eventId)).isFalse();
        assertThat(receivablePaymentRepository.findById(paymentIntentId)).isEmpty();
    }

    private List<AccountingEvent> heldRecords() {
        return accountingEventRepository.findAll().stream()
                .filter(e -> PaymentSettledV1.EVENT_TYPE.equals(e.getEventType()))
                .filter(e -> paymentIntentId.toString().equals(e.getDomainKeyId()))
                .toList();
    }

    private String eurSettled() {
        return settled("EUR");
    }

    private String settled(String currency) {
        PaymentSettledV1 payload = new PaymentSettledV1(
                paymentIntentId,
                UUID.randomUUID(),
                "INV-2324",
                null,
                null,
                UUID.randomUUID().toString(),
                "CARD",
                new BigDecimal("150.00"),
                currency,
                "stripe",
                "txn_2324",
                Instant.parse("2026-08-27T00:00:00Z"));
        return mapper.writeValueAsString(
                Map.of("eventType", PaymentSettledV1.EVENT_TYPE, "eventId", eventId, "payload", payload));
    }
}
