package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.dto.ReconcileAPPaymentGatewayRequest;
import com.positivity.accounting.internal.entity.APPayment;
import com.positivity.accounting.internal.entity.AccountingAuditLog;
import com.positivity.accounting.internal.enums.APPaymentSettlementStatus;
import com.positivity.accounting.internal.enums.APPaymentStatus;
import com.positivity.accounting.internal.exception.AccountingPeriodClosedException;
import com.positivity.accounting.internal.payment.GatewayPaymentResponse;
import com.positivity.accounting.internal.payment.PaymentGatewayException;
import com.positivity.accounting.internal.payment.PaymentGatewayProvider;
import com.positivity.accounting.internal.repository.APPaymentRepository;
import com.positivity.accounting.internal.repository.AccountingAuditLogRepository;
import com.positivity.tenancy.TenantIterator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

@ExtendWith(MockitoExtension.class)
@DisplayName("APPaymentSettlementJob (#2642 ruling 1): orphans by reference, in-flight charges by transaction id")
class APPaymentSettlementJobTest {

    private static final Instant NOW = Instant.parse("2026-10-17T15:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 17);
    private static final UUID ORPHAN_ID = UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f7001");
    private static final UUID FLIGHT_ID = UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f7002");

    @Mock
    private APPaymentRepository payments;

    @Mock
    private APPaymentService paymentService;

    @Mock
    private APPaymentReturnService returns;

    @Mock
    private APPaymentFailurePersistenceService failures;

    @Mock
    private AccountingAuditLogRepository auditLogs;

    @Mock
    private ObjectProvider<PaymentGatewayProvider> gatewayProvider;

    @Mock
    private PaymentGatewayProvider gateway;

    @Mock
    private TenantIterator tenantIterator;

    @Mock
    private AccountingCalendarZoneResolver zoneResolver;

    @Mock
    private PlatformTransactionManager transactionManager;

    private APPaymentSettlementJob job;
    private APPayment orphan;
    private APPayment inFlight;

    @BeforeEach
    void setUp() {
        TransactionStatus status = new SimpleTransactionStatus();
        lenient().when(transactionManager.getTransaction(any())).thenReturn(status);
        lenient().when(gatewayProvider.getIfAvailable()).thenReturn(gateway);
        lenient().when(gatewayProvider.getObject()).thenReturn(gateway);
        lenient().when(zoneResolver.today()).thenReturn(TODAY);
        job = new APPaymentSettlementJob(
                payments,
                paymentService,
                returns,
                failures,
                auditLogs,
                gatewayProvider,
                tenantIterator,
                zoneResolver,
                transactionManager,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofMinutes(15),
                24,
                200);
        orphan = new APPayment(ORPHAN_ID);
        orphan.setPaymentRef("PAY-ORPHAN");
        orphan.setStatus(APPaymentStatus.GATEWAY_PENDING);
        orphan.setCreatedAt(NOW.minus(Duration.ofHours(1)));
        inFlight = new APPayment(FLIGHT_ID);
        inFlight.setPaymentRef("PAY-FLIGHT");
        inFlight.setStatus(APPaymentStatus.GL_POSTED);
        inFlight.setGatewayTransactionId("ch_flight");
        inFlight.setSettlementStatus(APPaymentSettlementStatus.IN_FLIGHT);
        lenient().when(payments.findOrphanIds(any(), any())).thenReturn(List.of(ORPHAN_ID));
        lenient().when(payments.findInFlightIds(any(), any())).thenReturn(List.of(FLIGHT_ID));
        lenient().when(payments.findById(ORPHAN_ID)).thenReturn(Optional.of(orphan));
        lenient().when(payments.findById(FLIGHT_ID)).thenReturn(Optional.of(inFlight));
        lenient().when(payments.lockById(ORPHAN_ID)).thenReturn(Optional.of(orphan));
        lenient().when(payments.lockById(FLIGHT_ID)).thenReturn(Optional.of(inFlight));
        lenient().when(gateway.findByReference(any())).thenReturn(Optional.empty());
        lenient().when(gateway.getPaymentStatus(any())).thenReturn(Optional.empty());
    }

    private static GatewayPaymentResponse answer(PaymentGatewayProvider.GatewayPaymentStatus status, String code) {
        return GatewayPaymentResponse.builder()
                .transactionId("ch_found")
                .status(status)
                .failureCode(code)
                .build();
    }

    private ReconcileAPPaymentGatewayRequest reconciled() {
        ArgumentCaptor<ReconcileAPPaymentGatewayRequest> sent =
                ArgumentCaptor.forClass(ReconcileAPPaymentGatewayRequest.class);
        verify(paymentService).reconcileGateway(eq(ORPHAN_ID), sent.capture(), eq("system"));
        return sent.getValue();
    }

    @Test
    @DisplayName("an orphan the gateway holds as SUCCEEDED is reconciled CHARGED with the found transaction id and"
            + " marked SETTLED")
    void orphanChargedAndSettled() {
        when(gateway.findByReference("PAY-ORPHAN"))
                .thenReturn(Optional.of(answer(PaymentGatewayProvider.GatewayPaymentStatus.SUCCEEDED, null)));
        payments.findInFlightIds(any(), any());
        when(payments.findInFlightIds(any(), any())).thenReturn(List.of());

        assertThat(job.reReadBoundTenant()).isEqualTo(1);

        ReconcileAPPaymentGatewayRequest request = reconciled();
        assertThat(request.getGatewayOutcome()).isEqualTo(ReconcileAPPaymentGatewayRequest.GatewayOutcome.CHARGED);
        assertThat(request.getGatewayTransactionId()).isEqualTo("ch_found");
        assertThat(request.getJustification()).startsWith("GATEWAY_LOOKUP");
        assertThat(orphan.getSettlementStatus()).isEqualTo(APPaymentSettlementStatus.SETTLED);
        assertThat(orphan.getSettledAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("an orphan the gateway holds as PENDING is reconciled CHARGED and stays in flight; DECLINED marks it"
            + " GATEWAY_FAILED")
    void orphanPendingOrDeclined() {
        when(payments.findInFlightIds(any(), any())).thenReturn(List.of());
        when(gateway.findByReference("PAY-ORPHAN"))
                .thenReturn(Optional.of(answer(PaymentGatewayProvider.GatewayPaymentStatus.PENDING, null)));
        job.reReadBoundTenant();
        assertThat(reconciled().getGatewayOutcome()).isEqualTo(ReconcileAPPaymentGatewayRequest.GatewayOutcome.CHARGED);
        assertThat(orphan.getSettlementStatus()).isNull();
        verify(failures, never()).persistGatewayFailure(any(), any());

        org.mockito.Mockito.clearInvocations(paymentService);
        when(gateway.findByReference("PAY-ORPHAN"))
                .thenReturn(Optional.of(answer(PaymentGatewayProvider.GatewayPaymentStatus.DECLINED, "card_declined")));
        job.reReadBoundTenant();
        verify(failures).persistGatewayFailure(eq(ORPHAN_ID), argThat(msg -> msg.contains("card_declined")));
        verify(paymentService, never()).reconcileGateway(any(), any(), any());
    }

    @Test
    @DisplayName(
            "no charge under the reference: left alone inside the idempotency window, VOIDED (NOT_CHARGED) after it")
    void orphanNotFound() {
        when(payments.findInFlightIds(any(), any())).thenReturn(List.of());
        assertThat(job.reReadBoundTenant()).isZero();
        verify(paymentService, never()).reconcileGateway(any(), any(), any());

        orphan.setCreatedAt(NOW.minus(Duration.ofHours(25)));
        assertThat(job.reReadBoundTenant()).isEqualTo(1);
        assertThat(reconciled().getGatewayOutcome())
                .isEqualTo(ReconcileAPPaymentGatewayRequest.GatewayOutcome.NOT_CHARGED);
    }

    @Test
    @DisplayName("an in-flight charge the gateway shows SUCCEEDED is SETTLED with an AP_PAYMENT_SETTLE audit row and no"
            + " entry; PENDING only stamps the check; a vanished charge is left in flight")
    void inFlightSettledOrPending() {
        when(payments.findOrphanIds(any(), any())).thenReturn(List.of());
        when(gateway.getPaymentStatus("ch_flight"))
                .thenReturn(Optional.of(answer(PaymentGatewayProvider.GatewayPaymentStatus.PENDING, null)));
        assertThat(job.reReadBoundTenant()).isZero();
        assertThat(inFlight.getSettlementStatus()).isEqualTo(APPaymentSettlementStatus.IN_FLIGHT);
        assertThat(inFlight.getSettlementCheckedAt()).isEqualTo(NOW);
        verify(auditLogs, never()).save(any());

        when(gateway.getPaymentStatus("ch_flight")).thenReturn(Optional.empty());
        assertThat(job.reReadBoundTenant()).isZero();
        assertThat(inFlight.getSettlementStatus()).isEqualTo(APPaymentSettlementStatus.IN_FLIGHT);

        when(gateway.getPaymentStatus("ch_flight"))
                .thenReturn(Optional.of(answer(PaymentGatewayProvider.GatewayPaymentStatus.SUCCEEDED, null)));
        assertThat(job.reReadBoundTenant()).isEqualTo(1);
        assertThat(inFlight.getSettlementStatus()).isEqualTo(APPaymentSettlementStatus.SETTLED);
        assertThat(inFlight.getSettledAt()).isEqualTo(NOW);
        verify(auditLogs)
                .save(argThat((AccountingAuditLog row) ->
                        "AP_PAYMENT_SETTLE".equals(row.getOperation()) && "system".equals(row.getUserId())));
        verify(returns, never()).returnFromGateway(any(), any(), any());
    }

    @Test
    @DisplayName(
            "an in-flight charge the gateway shows FAILED is returned (today, the gateway's code); a period refusal"
                    + " of the mirror holds the payment RETURN_PENDING instead")
    void inFlightReturned() {
        when(payments.findOrphanIds(any(), any())).thenReturn(List.of());
        when(gateway.getPaymentStatus("ch_flight"))
                .thenReturn(Optional.of(answer(PaymentGatewayProvider.GatewayPaymentStatus.FAILED, "R01")));

        assertThat(job.reReadBoundTenant()).isEqualTo(1);
        verify(returns).returnFromGateway(inFlight, "R01", TODAY);
        verify(returns, never()).holdReturn(any(), any(), any(), any());

        doThrow(new AccountingPeriodClosedException("2026-10", "closed"))
                .when(returns)
                .returnFromGateway(any(), any(), any());
        assertThat(job.reReadBoundTenant()).isEqualTo(1);
        verify(returns).holdReturn(FLIGHT_ID, "R01", TODAY, "PERIOD_CLOSED");
    }

    @Test
    @DisplayName("a gateway failure on one payment is logged and the sweep goes on; no gateway bean means no sweep")
    void gatewayFailuresDoNotStopTheSweep() {
        when(gateway.findByReference("PAY-ORPHAN")).thenThrow(new PaymentGatewayException("down"));
        when(gateway.getPaymentStatus("ch_flight"))
                .thenReturn(Optional.of(answer(PaymentGatewayProvider.GatewayPaymentStatus.SUCCEEDED, null)));

        assertThat(job.reReadBoundTenant()).isEqualTo(1);
        assertThat(inFlight.getSettlementStatus()).isEqualTo(APPaymentSettlementStatus.SETTLED);

        when(gatewayProvider.getIfAvailable()).thenReturn(null);
        job.reRead();
        verify(tenantIterator, never()).forEachActiveTenant(any());
    }

    @Test
    @DisplayName("the scheduled entry runs the sweep per active tenant")
    void perTenant() {
        doAnswer(inv -> {
                    java.util.function.Consumer<UUID> work = inv.getArgument(0);
                    work.accept(UUID.randomUUID());
                    return 1;
                })
                .when(tenantIterator)
                .forEachActiveTenant(any());
        when(gateway.getPaymentStatus("ch_flight"))
                .thenReturn(Optional.of(answer(PaymentGatewayProvider.GatewayPaymentStatus.SUCCEEDED, null)));

        job.reRead();

        assertThat(inFlight.getSettlementStatus()).isEqualTo(APPaymentSettlementStatus.SETTLED);
    }
}
