package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.config.LedgerCurrency;
import com.positivity.accounting.internal.dto.PaymentApplicationResponse;
import com.positivity.accounting.internal.entity.ExtInvoice;
import com.positivity.accounting.internal.entity.ReceivablePayment;
import com.positivity.accounting.internal.entity.ReceivablePayment.ReceivablePaymentStatus;
import com.positivity.accounting.internal.enums.AccountingEventStatus;
import com.positivity.accounting.internal.enums.ApplicationSource;
import com.positivity.accounting.internal.enums.PostingFailureReason;
import com.positivity.accounting.internal.repository.PaymentApplicationRepository;
import com.positivity.accounting.internal.repository.PaymentApplicationReversalRepository;
import com.positivity.accounting.internal.repository.ReceivablePaymentRepository;
import com.positivity.accounting.internal.service.AutomaticPaymentApplicationService.Outcome;
import com.positivity.accounting.internal.service.AutomaticPaymentApplicationService.Result;
import com.positivity.domainevents.payment.PaymentSettledV1;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Cases a-h of #2503 item 2, the outcome rows (item 5) and the reprocess entry (item 6). */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AutomaticPaymentApplicationService (#2503)")
class AutomaticPaymentApplicationServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-06T12:00:00Z"), ZoneOffset.UTC);
    private static final Instant SETTLED_AT = Instant.parse("2026-10-05T14:31:07Z");
    private static final UUID INTENT = UUID.fromString("0199b000-0000-7000-8000-000000000001");
    private static final UUID INVOICE = UUID.fromString("0199b000-0000-7000-8000-000000000002");
    private static final UUID CUSTOMER = UUID.fromString("0199b000-0000-7000-8000-000000000003");
    private static final String EVENT_ID = "0199b000-0000-7000-8000-000000000099";

    @Mock
    private PaymentApplicationService paymentApplicationService;

    @Mock
    private ReceivablePaymentRepository receivablePaymentRepository;

    @Mock
    private InvoiceBalanceCalculator invoiceBalanceCalculator;

    @Mock
    private AccountingPeriodGate periodGate;

    @Mock
    private KafkaFactIngestionRecorder recorder;

    @Mock
    private PaymentApplicationRepository paymentApplicationRepository;

    @Mock
    private PaymentApplicationReversalRepository reversalRepository;

    private final ObjectMapper mapper = new ObjectMapper();
    private final MeterRegistry meters = new SimpleMeterRegistry();
    private AutomaticPaymentApplicationService service;
    private ExtInvoice invoice;
    private ReceivablePayment payment;

    @BeforeEach
    void setUp() {
        @SuppressWarnings("unchecked")
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(meters);
        service = new AutomaticPaymentApplicationService(
                paymentApplicationService,
                receivablePaymentRepository,
                paymentApplicationRepository,
                reversalRepository,
                invoiceBalanceCalculator,
                periodGate,
                recorder,
                new LedgerCurrency("USD"),
                mapper,
                TestZoneResolvers.utc(CLOCK),
                provider);

        invoice = new ExtInvoice();
        invoice.setInvoiceId(INVOICE);
        invoice.setInvoiceNumber("INV-1");
        invoice.setPartyId(CUSTOMER.toString());
        invoice.setStatus("FINALIZED");
        payment = new ReceivablePayment();
        payment.setPaymentId(INTENT);
        payment.setCustomerId(CUSTOMER);
        payment.setCurrency("USD");
        payment.setTotalAmount(new BigDecimal("115.00"));
        payment.setUnappliedAmount(new BigDecimal("115.00"));
        payment.setStatus(ReceivablePaymentStatus.AVAILABLE);

        // The happy path; each case below breaks exactly one rule.
        lenient().when(invoiceBalanceCalculator.findInvoice(INVOICE)).thenReturn(Optional.of(invoice));
        lenient()
                .when(invoiceBalanceCalculator.isArEligible(any()))
                .thenAnswer(inv -> InvoiceBalanceCalculator.AR_ELIGIBLE_STATUSES.contains(
                        inv.<ExtInvoice>getArgument(0).getStatus()));
        lenient().when(invoiceBalanceCalculator.balanceDue(invoice)).thenReturn(new BigDecimal("115.00"));
        lenient().when(periodGate.isPostingBlocked(any())).thenReturn(false);
        lenient()
                .when(paymentApplicationService.applyAutomatically(any(), any(), any(), any(), any(), any()))
                .thenReturn(applied("115.00", null));
    }

    // ===== a-g: nothing applied =====

    static Stream<Arguments> notApplied() {
        return Stream.of(
                Arguments.of(
                        "a. ON_ACCOUNT is not applied automatically",
                        (Consumer<AutomaticPaymentApplicationServiceTest>) t -> t.method = "ON_ACCOUNT",
                        Outcome.SKIPPED_METHOD,
                        "method ON_ACCOUNT is not applied automatically"),
                Arguments.of(
                        "a. OTHER is not applied automatically",
                        (Consumer<AutomaticPaymentApplicationServiceTest>) t -> t.method = "OTHER",
                        Outcome.SKIPPED_METHOD,
                        "method OTHER"),
                Arguments.of(
                        "b. invoice not replicated",
                        (Consumer<AutomaticPaymentApplicationServiceTest>)
                                t -> when(t.invoiceBalanceCalculator.findInvoice(INVOICE))
                                        .thenReturn(Optional.empty()),
                        Outcome.SUSPENDED_INVOICE,
                        "not in the invoice replica"),
                Arguments.of(
                        "c. invoice voided",
                        (Consumer<AutomaticPaymentApplicationServiceTest>) t -> t.invoice.setStatus("VOIDED"),
                        Outcome.FAILED_INELIGIBLE,
                        "invoice INV-1 cannot take a payment (status: VOIDED)"),
                Arguments.of(
                        "d. invoice of another customer",
                        (Consumer<AutomaticPaymentApplicationServiceTest>)
                                t -> t.invoice.setPartyId(UUID.randomUUID().toString()),
                        Outcome.SKIPPED_PARTY,
                        "customer differs from invoice INV-1"),
                Arguments.of(
                        "d. invoice without a party",
                        (Consumer<AutomaticPaymentApplicationServiceTest>) t -> t.invoice.setPartyId(null),
                        Outcome.SKIPPED_PARTY,
                        "customer differs from invoice INV-1"),
                Arguments.of(
                        "e. settlement date in a closed period",
                        (Consumer<AutomaticPaymentApplicationServiceTest>)
                                t -> when(t.periodGate.isPostingBlocked(LocalDate.parse("2026-10-05")))
                                        .thenReturn(true),
                        Outcome.SUSPENDED_PERIOD,
                        "settlement date 2026-10-05"),
                Arguments.of(
                        "f. already applied by another path",
                        (Consumer<AutomaticPaymentApplicationServiceTest>) t -> {
                            t.payment.setUnappliedAmount(BigDecimal.ZERO);
                            t.payment.setStatus(ReceivablePaymentStatus.FULLY_APPLIED);
                        },
                        Outcome.ALREADY_APPLIED,
                        "nothing unapplied"),
                Arguments.of(
                        "g. invoice already paid in full",
                        (Consumer<AutomaticPaymentApplicationServiceTest>)
                                t -> when(t.invoiceBalanceCalculator.balanceDue(t.invoice))
                                        .thenReturn(new BigDecimal("0.00")),
                        Outcome.SKIPPED_PAID,
                        "invoice INV-1 has no open balance; left for a person"));
    }

    private String method = "CARD";

    @ParameterizedTest(name = "{0}")
    @MethodSource("notApplied")
    @DisplayName("cases a-g apply nothing, record the outcome and count it")
    void casesAThroughG(
            String name, Consumer<AutomaticPaymentApplicationServiceTest> arrange, Outcome expected, String detail) {
        arrange.accept(this);

        Result result = service.applySettled(payment, fact(method), EVENT_ID);

        assertThat(result.outcome()).isEqualTo(expected);
        assertThat(result.detail()).contains(detail);
        verify(paymentApplicationService, never()).applyAutomatically(any(), any(), any(), any(), any(), any());
        assertThat(count(expected)).isEqualTo(1.0);
        LocalDateTime settledOn = LocalDateTime.ofInstant(SETTLED_AT, ZoneOffset.UTC);
        switch (expected.status()) {
            case SKIPPED ->
                verify(recorder)
                        .recordSkippedOnce(
                                eq("pos-invoice"),
                                eq(PaymentSettledV1.EVENT_TYPE),
                                eq(EVENT_ID),
                                eq(INTENT),
                                eq(settledOn),
                                any(),
                                eq(PostingFailureReason.NOT_POSTABLE),
                                contains(detail));
            case SUSPENDED, FAILED ->
                verify(recorder)
                        .recordSuspended(
                                eq("pos-invoice"),
                                eq(PaymentSettledV1.EVENT_TYPE),
                                eq(EVENT_ID),
                                eq(INTENT),
                                eq(settledOn),
                                any(),
                                eq(expected.status()),
                                eq(expected.reason()),
                                contains(detail));
            default -> verifyNoInteractions(recorder); // f writes no row
        }
    }

    @Test
    @DisplayName(
            "the first rule that matches decides: a foreign customer on an unreplicated invoice is INVOICE_NOT_FOUND")
    void firstRuleDecides() {
        when(invoiceBalanceCalculator.findInvoice(INVOICE)).thenReturn(Optional.empty());

        assertThat(service.applySettled(payment, fact("ON_ACCOUNT"), EVENT_ID).outcome())
                .isEqualTo(Outcome.SKIPPED_METHOD);
        assertThat(service.applySettled(payment, fact("CARD"), EVENT_ID).outcome())
                .isEqualTo(Outcome.SUSPENDED_INVOICE);
    }

    @Test
    @DisplayName("d compares the party by UUID, not by text: an upper-case UUID is the same customer")
    void partyComparedByUuid() {
        invoice.setPartyId(CUSTOMER.toString().toUpperCase(java.util.Locale.ROOT));

        assertThat(service.applySettled(payment, fact("CARD"), EVENT_ID).outcome())
                .isEqualTo(Outcome.APPLIED);
    }

    // ===== h: applied =====

    @Test
    @DisplayName("h. CASH or CARD applies the whole unapplied amount to that invoice, dated settledAt, keyed"
            + " PAYMENT_SETTLED:<paymentIntentId>, and writes no row")
    void appliesDatedAtSettlement() {
        payment.setUnappliedAmount(new BigDecimal("120.00"));
        when(paymentApplicationService.applyAutomatically(any(), any(), any(), any(), any(), any()))
                .thenReturn(applied("115.00", "5.00"));

        Result result = service.applySettled(payment, fact(" cash "), EVENT_ID);

        assertThat(result.outcome()).isEqualTo(Outcome.APPLIED);
        assertThat(result.detail())
                .contains("applied 115.00 to invoice INV-1")
                .contains("5.00 kept as customer credit");
        verify(paymentApplicationService)
                .applyAutomatically(
                        INTENT,
                        INVOICE,
                        new BigDecimal("120.00"),
                        "PAYMENT_SETTLED:" + INTENT,
                        SETTLED_AT,
                        ApplicationSource.PAYMENT_SETTLED);
        verifyNoInteractions(recorder);
        assertThat(count(Outcome.APPLIED)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("0. an application under this settlement's request id decides first: a re-publish after the invoice"
            + " left the replica or the period closed writes no row and applies nothing (review #2550)")
    void ownRequestIdDecidesBeforeMutableRules() {
        when(paymentApplicationRepository.existsByApplicationRequestId("PAYMENT_SETTLED:" + INTENT))
                .thenReturn(true);
        when(invoiceBalanceCalculator.findInvoice(INVOICE)).thenReturn(Optional.empty());
        when(periodGate.isPostingBlocked(any())).thenReturn(true);

        Result result = service.applySettled(payment, fact("CARD"), EVENT_ID);

        assertThat(result.outcome()).isEqualTo(Outcome.ALREADY_APPLIED);
        verify(paymentApplicationService, never()).applyAutomatically(any(), any(), any(), any(), any(), any());
        verifyNoInteractions(recorder);
        assertThat(count(Outcome.ALREADY_APPLIED)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("0. a payment whose automatic application (either path) was undone is never applied again (BR-8)")
    void undoneByAnyAutomaticPathIsNotRepeated() {
        when(reversalRepository.existsReversedAutomaticApplication(INTENT)).thenReturn(true);

        Result result = service.applySettled(payment, fact("CARD"), EVENT_ID);

        assertThat(result.outcome()).isEqualTo(Outcome.ALREADY_APPLIED);
        assertThat(result.detail()).contains("undone; not applied again");
        verify(paymentApplicationService, never()).applyAutomatically(any(), any(), any(), any(), any(), any());
        verifyNoInteractions(recorder);
    }

    @Test
    @DisplayName("e. a hard-locked settlement date is held for a person, never told to reopen (review #2550)")
    void hardLockIsNotReopenable() {
        when(periodGate.isPostingBlocked(any())).thenReturn(true);
        when(periodGate.isHardLocked(LocalDate.parse("2026-10-05"))).thenReturn(true);

        Result result = service.applySettled(payment, fact("CARD"), EVENT_ID);

        assertThat(result.outcome()).isEqualTo(Outcome.SUSPENDED_PERIOD);
        assertThat(result.detail())
                .contains("hard-lock")
                .contains("cannot be reopened")
                .doesNotContain("after reopening");
    }

    // ===== #2558: the tenant's accounting-calendar zone =====

    /** 2026-01-31T23:30-06:00: still January in Chicago, already February in UTC (the clock's zone). */
    private static final Instant JAN_31_2330_CHICAGO = Instant.parse("2026-02-01T05:30:00Z");

    private AutomaticPaymentApplicationService serviceWith(AccountingCalendarZoneResolver zoneResolver) {
        @SuppressWarnings("unchecked")
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        return new AutomaticPaymentApplicationService(
                paymentApplicationService,
                receivablePaymentRepository,
                paymentApplicationRepository,
                reversalRepository,
                invoiceBalanceCalculator,
                periodGate,
                recorder,
                new LedgerCurrency("USD"),
                mapper,
                zoneResolver,
                provider);
    }

    private static PaymentSettledV1 factAt(Instant settledAt) {
        PaymentSettledV1 base = fact("CARD");
        return new PaymentSettledV1(
                base.paymentIntentId(),
                base.invoiceId(),
                base.invoiceNumber(),
                null,
                null,
                base.partyId(),
                base.methodType(),
                base.amount(),
                base.currencyCode(),
                "stripe",
                "txn_1",
                settledAt);
    }

    @Test
    @DisplayName("#2558 e. in a Chicago calendar a settlement at 2026-01-31T23:30-06:00 is gated on 2026-01-31,"
            + " and its held row is dated then")
    void chicagoCalendar_gatesTheJanuaryDate() {
        AutomaticPaymentApplicationService chicago =
                serviceWith(TestZoneResolvers.fixed(java.time.ZoneId.of("America/Chicago"), CLOCK));
        when(periodGate.isPostingBlocked(LocalDate.parse("2026-01-31"))).thenReturn(true);

        Result result = chicago.applySettled(payment, factAt(JAN_31_2330_CHICAGO), EVENT_ID);

        assertThat(result.outcome()).isEqualTo(Outcome.SUSPENDED_PERIOD);
        assertThat(result.detail()).contains("2026-01-31");
        verify(periodGate, never()).isPostingBlocked(LocalDate.parse("2026-02-01"));
        verify(recorder)
                .recordSuspended(
                        anyString(),
                        anyString(),
                        eq(EVENT_ID),
                        any(),
                        eq(java.time.LocalDateTime.parse("2026-01-31T23:30:00")),
                        any(),
                        eq(AccountingEventStatus.SUSPENDED),
                        eq("PERIOD_CLOSED"),
                        anyString());
    }

    @Test
    @DisplayName("#2558: without an accounting time zone the settlement is held SUSPENDED / ACCOUNTING_TIME_ZONE_UNSET;"
            + " no period is guessed and nothing is applied")
    void unsetZone_holdsTheSettlement() {
        AutomaticPaymentApplicationService unset = serviceWith(TestZoneResolvers.unset(CLOCK));

        Result result = unset.applySettled(payment, factAt(JAN_31_2330_CHICAGO), EVENT_ID);

        assertThat(result.outcome()).isEqualTo(Outcome.SUSPENDED_TIME_ZONE);
        assertThat(result.outcome().reason()).isEqualTo("ACCOUNTING_TIME_ZONE_UNSET");
        verifyNoInteractions(periodGate);
        verify(paymentApplicationService, never()).applyAutomatically(any(), any(), any(), any(), any(), any());
        verify(recorder)
                .recordSuspended(
                        anyString(),
                        anyString(),
                        eq(EVENT_ID),
                        any(),
                        eq(java.time.LocalDateTime.parse("2026-02-01T05:30:00")),
                        any(),
                        eq(AccountingEventStatus.SUSPENDED),
                        eq("ACCOUNTING_TIME_ZONE_UNSET"),
                        contains("time zone"));
    }

    // ===== item 6: reprocess =====

    @Test
    @DisplayName("reapply re-runs the decision from the stored payload against the recorded payment, writing no row")
    void reapplyFromStoredPayload() {
        when(receivablePaymentRepository.findById(INTENT)).thenReturn(Optional.of(payment));
        Map<String, Object> stored = mapper.convertValue(fact("CARD"), new TypeReference<Map<String, Object>>() {});

        Result result = service.reapply(stored);

        assertThat(result.outcome()).isEqualTo(Outcome.APPLIED);
        verify(paymentApplicationService)
                .applyAutomatically(
                        eq(INTENT), eq(INVOICE), any(), eq("PAYMENT_SETTLED:" + INTENT), eq(SETTLED_AT), any());
        verify(paymentApplicationService, never())
                .handlePaymentCleared(any(), any(), any(), any(), any(), any(), any(), any());
        verifyNoInteractions(recorder);
    }

    @Test
    @DisplayName("reapply refuses a payment that was never recorded")
    void reapplyWithoutPaymentFails() {
        when(receivablePaymentRepository.findById(INTENT)).thenReturn(Optional.empty());
        Map<String, Object> stored = mapper.convertValue(fact("CARD"), new TypeReference<Map<String, Object>>() {});

        assertThatThrownBy(() -> service.reapply(stored))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("never recorded");
        verify(paymentApplicationService, never()).applyAutomatically(any(), any(), any(), anyString(), any(), any());
    }

    @Test
    @DisplayName("only the four held reasons route a reprocess back here; a currency hold does not")
    void reprocessableReasons() {
        assertThat(AutomaticPaymentApplicationService.REPROCESSABLE_REASONS)
                .containsExactlyInAnyOrder(
                        "PERIOD_CLOSED", "ACCOUNTING_TIME_ZONE_UNSET", "INVOICE_NOT_FOUND", "INVOICE_NOT_ELIGIBLE")
                .doesNotContain("CURRENCY_NOT_SUPPORTED");
    }

    private double count(Outcome outcome) {
        return meters.get("accounting.payment.settled.auto_apply")
                .tag("outcome", outcome.tag())
                .counter()
                .count();
    }

    private static PaymentApplicationResponse applied(String amount, String credit) {
        return PaymentApplicationResponse.builder()
                .appliedAmount(new BigDecimal(amount))
                .remainingAmount(BigDecimal.ZERO)
                .customerCredit(
                        credit == null
                                ? null
                                : PaymentApplicationResponse.CustomerCreditInfo.builder()
                                        .amount(new BigDecimal(credit))
                                        .build())
                .build();
    }

    private static PaymentSettledV1 fact(String method) {
        return new PaymentSettledV1(
                INTENT,
                INVOICE,
                "INV-1",
                null,
                null,
                CUSTOMER.toString(),
                method,
                new BigDecimal("115.00"),
                "USD",
                "stripe",
                "txn_1",
                SETTLED_AT);
    }
}
