package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.dto.RecordAPPaymentReturnRequest;
import com.positivity.accounting.internal.entity.APPayment;
import com.positivity.accounting.internal.entity.APPaymentAllocation;
import com.positivity.accounting.internal.entity.APPaymentReturnRequest;
import com.positivity.accounting.internal.entity.AccountingAuditLog;
import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.internal.enums.APPaymentSettlementStatus;
import com.positivity.accounting.internal.enums.APPaymentStatus;
import com.positivity.accounting.internal.enums.PaymentMethod;
import com.positivity.accounting.internal.exception.IdempotencyConflictException;
import com.positivity.accounting.internal.exception.VendorBillException;
import com.positivity.accounting.internal.payment.GatewayPaymentResponse;
import com.positivity.accounting.internal.payment.PaymentGatewayException;
import com.positivity.accounting.internal.payment.PaymentGatewayProvider;
import com.positivity.accounting.internal.repository.APPaymentAllocationRepository;
import com.positivity.accounting.internal.repository.APPaymentRepository;
import com.positivity.accounting.internal.repository.APPaymentReturnRequestRepository;
import com.positivity.accounting.internal.repository.AccountingAuditLogRepository;
import java.math.BigDecimal;
import java.time.Clock;
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

@ExtendWith(MockitoExtension.class)
@DisplayName("APPaymentReturnService (#2642): a returned charge reopens the allocations and owes the mirror")
class APPaymentReturnServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-17T15:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 17);
    private static final UUID PAYMENT_ID = UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f7001");
    private static final UUID ENTRY_ID = UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f7010");
    private static final UUID MIRROR_ID = UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f7011");
    private static final UUID BILL_A = UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f8001");
    private static final UUID BILL_B = UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f8002");
    private static final UUID REQUEST_ID = UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f9001");

    @Mock
    private APPaymentRepository payments;

    @Mock
    private APPaymentAllocationRepository allocations;

    @Mock
    private APPaymentReturnRequestRepository returnRequests;

    @Mock
    private APPaymentPostingService posting;

    @Mock
    private AccountingAuditLogRepository auditLogs;

    @Mock
    private ApLockTimeout lockTimeout;

    @Mock
    private AccountingCalendarZoneResolver zoneResolver;

    @Mock
    private ObjectProvider<PaymentGatewayProvider> gatewayProvider;

    @Mock
    private PaymentGatewayProvider gateway;

    private APPaymentReturnService service;
    private APPayment payment;
    private List<APPaymentAllocation> live;

    @BeforeEach
    void setUp() {
        service = new APPaymentReturnService(
                payments,
                allocations,
                returnRequests,
                posting,
                auditLogs,
                lockTimeout,
                zoneResolver,
                gatewayProvider,
                Clock.fixed(NOW, ZoneOffset.UTC));
        payment = new APPayment(PAYMENT_ID);
        payment.setPaymentRef("PAY-412");
        payment.setPaymentMethod(PaymentMethod.ACH);
        payment.setGrossAmount(new BigDecimal("412.00"));
        payment.setStatus(APPaymentStatus.GL_POSTED);
        payment.setGlJournalEntry(new JournalEntry(ENTRY_ID));
        payment.setGatewayTransactionId("ch_412");
        payment.setSettlementStatus(APPaymentSettlementStatus.IN_FLIGHT);
        live = List.of(allocation(BILL_A, "300.00"), allocation(BILL_B, "112.00"));
        lenient().when(payments.lockById(PAYMENT_ID)).thenReturn(Optional.of(payment));
        lenient().when(allocations.findLiveByPaymentId(PAYMENT_ID)).thenReturn(live);
        lenient().when(returnRequests.findByRequestId(any())).thenReturn(Optional.empty());
        lenient().when(zoneResolver.today()).thenReturn(TODAY);
        lenient().when(gatewayProvider.getIfAvailable()).thenReturn(gateway);
        lenient()
                .when(gateway.getPaymentStatus("ch_412"))
                .thenReturn(Optional.of(GatewayPaymentResponse.builder()
                        .transactionId("ch_412")
                        .status(PaymentGatewayProvider.GatewayPaymentStatus.FAILED)
                        .failureCode("R01")
                        .build()));
        lenient().when(posting.postReturn(eq(payment), any(), any(), any())).thenAnswer(inv -> {
            payment.setReturnJournalEntryId(MIRROR_ID);
            payment.setStatus(APPaymentStatus.RETURNED);
            payment.setReturnDate(inv.getArgument(1) == null ? TODAY : inv.getArgument(1));
            return MIRROR_ID;
        });
    }

    private static APPaymentAllocation allocation(UUID billId, String amount) {
        return new APPaymentAllocation(PAYMENT_ID, billId, new BigDecimal(amount));
    }

    private static RecordAPPaymentReturnRequest request(LocalDate returnDate, String failureCode) {
        return RecordAPPaymentReturnRequest.builder()
                .requestId(REQUEST_ID)
                .returnDate(returnDate)
                .failureCode(failureCode)
                .justification("Bank statement 2026-10-15: ACH return R01 for PAY-412")
                .build();
    }

    private AccountingAuditLog auditRow(String operation) {
        ArgumentCaptor<AccountingAuditLog> captor = ArgumentCaptor.forClass(AccountingAuditLog.class);
        verify(auditLogs).save(captor.capture());
        assertThat(captor.getValue().getOperation()).isEqualTo(operation);
        return captor.getValue();
    }

    @Test
    @DisplayName("a GL_POSTED payment: the allocations reopen, nothing stays unapplied, the mirror posts on the return"
            + " date, the request is remembered and the return is audited with the reopened bills")
    void postedPaymentReturns() {
        APPayment returned = service.recordReturn(PAYMENT_ID, request(LocalDate.of(2026, 10, 15), "R01"), "ana");

        assertThat(returned.getStatus()).isEqualTo(APPaymentStatus.RETURNED);
        assertThat(returned.getUnappliedAmount()).isEqualByComparingTo("0");
        assertThat(returned.getReturnedAt()).isEqualTo(NOW);
        assertThat(returned.getReturnFailureCode()).isEqualTo("R01");
        assertThat(returned.getSettlementStatus()).isEqualTo(APPaymentSettlementStatus.RETURNED);
        assertThat(live).allSatisfy(row -> assertThat(row.getReopenedAt()).isEqualTo(NOW));
        verify(allocations).saveAll(live);
        verify(posting).postReturn(eq(payment), eq(LocalDate.of(2026, 10, 15)), isNull(), eq("ana"));
        verify(returnRequests)
                .save(argThat((APPaymentReturnRequest row) -> row.getRequestId().equals(REQUEST_ID)
                        && row.getPaymentId().equals(PAYMENT_ID)));
        AccountingAuditLog row = auditRow("AP_PAYMENT_RETURN");
        assertThat(row.getUserId()).isEqualTo("ana");
        assertThat(row.getOldValue()).isEqualTo("GL_POSTED");
        assertThat(row.getJustification()).contains("Bank statement");
        assertThat(row.getNewValue())
                .contains("RETURNED")
                .contains("failureCode=R01")
                .contains("returnJournalEntryId=" + MIRROR_ID)
                .contains("reopened=" + BILL_A + ":300," + BILL_B + ":112")
                .contains("gateway=FAILED/R01");
    }

    @Test
    @DisplayName("a payment whose original posting is still pending ends RETURN_PENDING: allocations reopened now, the"
            + " mirror owed to the posting")
    void pendingPaymentReturnsLater() {
        payment.setStatus(APPaymentStatus.GL_POST_FAILED);
        payment.setGlJournalEntry(null);
        payment.setGlPostError("GL_MAPPING_NOT_CONFIGURED");

        APPayment returned = service.recordReturn(PAYMENT_ID, request(null, null), "ana");

        assertThat(returned.getStatus()).isEqualTo(APPaymentStatus.RETURN_PENDING);
        assertThat(returned.getReturnJournalEntryId()).isNull();
        assertThat(returned.getReturnDate()).isNull();
        assertThat(live).allSatisfy(row -> assertThat(row.getReopenedAt()).isEqualTo(NOW));
        verify(posting, never()).postReturn(any(), any(), any(), any());
        verify(payments).save(payment);
        assertThat(auditRow("AP_PAYMENT_RETURN").getNewValue()).startsWith("RETURN_PENDING;");
    }

    @Test
    @DisplayName("the gateway's answer is recorded but never refuses: SUCCEEDED, NOT_FOUND and UNAVAILABLE all record")
    void gatewayAnswerIsRecordedOnly() {
        when(gateway.getPaymentStatus("ch_412"))
                .thenReturn(Optional.of(GatewayPaymentResponse.builder()
                        .transactionId("ch_412")
                        .status(PaymentGatewayProvider.GatewayPaymentStatus.SUCCEEDED)
                        .build()));
        service.recordReturn(PAYMENT_ID, request(null, null), "ana");
        assertThat(auditRow("AP_PAYMENT_RETURN").getNewValue()).contains("gateway=SUCCEEDED");

        org.mockito.Mockito.reset(auditLogs);
        payment.setStatus(APPaymentStatus.GL_POSTED);
        payment.setReturnJournalEntryId(null);
        when(gateway.getPaymentStatus("ch_412")).thenThrow(new PaymentGatewayException("down", null));
        service.recordReturn(
                PAYMENT_ID,
                RecordAPPaymentReturnRequest.builder()
                        .requestId(UUID.randomUUID())
                        .justification("Processor notice: returned, gateway console unreachable")
                        .build(),
                "ana");
        assertThat(auditRow("AP_PAYMENT_RETURN").getNewValue()).contains("gateway=UNAVAILABLE");
    }

    @Test
    @DisplayName("a replay of the same requestId and body answers the payment unchanged; another body is 409"
            + " IDEMPOTENCY_CONFLICT")
    void replay() {
        RecordAPPaymentReturnRequest first = request(LocalDate.of(2026, 10, 15), "R01");
        service.recordReturn(PAYMENT_ID, first, "ana");
        ArgumentCaptor<APPaymentReturnRequest> saved = ArgumentCaptor.forClass(APPaymentReturnRequest.class);
        verify(returnRequests).save(saved.capture());
        when(returnRequests.findByRequestId(REQUEST_ID)).thenReturn(Optional.of(saved.getValue()));

        APPayment again = service.recordReturn(PAYMENT_ID, first, "ana");
        assertThat(again).isSameAs(payment);
        verify(posting, org.mockito.Mockito.times(1)).postReturn(any(), any(), any(), any());

        assertThatThrownBy(() -> service.recordReturn(PAYMENT_ID, request(LocalDate.of(2026, 10, 14), "R01"), "ana"))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    @DisplayName("refusals: a charge that never moved money (nextAction per state), CASH, already returned, a return"
            + " date after today")
    void refusals() {
        for (APPaymentStatus status : List.of(
                APPaymentStatus.INITIATED,
                APPaymentStatus.GATEWAY_PENDING,
                APPaymentStatus.GATEWAY_FAILED,
                APPaymentStatus.VOIDED)) {
            payment.setStatus(status);
            assertThatThrownBy(() -> service.recordReturn(PAYMENT_ID, request(null, null), "ana"))
                    .as(status.name())
                    .isInstanceOfSatisfying(VendorBillException.class, e -> {
                        assertThat(e.getCode()).isEqualTo(VendorBillException.Code.AP_PAYMENT_NOT_RETURNABLE);
                        assertThat(e.getNextAction()).isNotBlank();
                    });
        }
        payment.setStatus(APPaymentStatus.GATEWAY_PENDING);
        assertThatThrownBy(() -> service.recordReturn(PAYMENT_ID, request(null, null), "ana"))
                .isInstanceOfSatisfying(
                        VendorBillException.class,
                        e -> assertThat(e.getNextAction()).contains("reconcileApPaymentGateway"));

        payment.setStatus(APPaymentStatus.GL_POSTED);
        payment.setPaymentMethod(PaymentMethod.CASH);
        assertThatThrownBy(() -> service.recordReturn(PAYMENT_ID, request(null, null), "ana"))
                .isInstanceOfSatisfying(
                        VendorBillException.class,
                        e -> assertThat(e.getCode()).isEqualTo(VendorBillException.Code.AP_PAYMENT_NOT_RETURNABLE));
        payment.setPaymentMethod(PaymentMethod.ACH);

        for (APPaymentStatus status : List.of(APPaymentStatus.RETURNED, APPaymentStatus.RETURN_PENDING)) {
            payment.setStatus(status);
            assertThatThrownBy(() -> service.recordReturn(PAYMENT_ID, request(null, null), "ana"))
                    .as(status.name())
                    .isInstanceOfSatisfying(
                            VendorBillException.class,
                            e -> assertThat(e.getCode())
                                    .isEqualTo(VendorBillException.Code.AP_PAYMENT_ALREADY_RETURNED));
        }

        payment.setStatus(APPaymentStatus.GL_POSTED);
        assertThatThrownBy(() -> service.recordReturn(PAYMENT_ID, request(TODAY.plusDays(1), null), "ana"))
                .isInstanceOfSatisfying(VendorBillException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(VendorBillException.Code.VALIDATION_ERROR);
                    assertThat(e.getFieldErrors())
                            .extracting(VendorBillException.FieldError::field)
                            .containsExactly("returnDate");
                });
        verify(posting, never()).postReturn(any(), any(), any(), any());
        verify(allocations, never()).saveAll(any());
    }

    @Test
    @DisplayName("the settlement job's path records the return as system with the gateway's failure date and code")
    void returnFromGateway() {
        service.returnFromGateway(payment, "R01", LocalDate.of(2026, 10, 16));

        verify(posting).postReturn(eq(payment), eq(LocalDate.of(2026, 10, 16)), isNull(), eq("system"));
        AccountingAuditLog row = auditRow("AP_PAYMENT_RETURN");
        assertThat(row.getUserId()).isEqualTo("system");
        assertThat(row.getJustification()).contains("Gateway re-read");
        assertThat(row.getNewValue()).doesNotContain("gateway=");
    }
}
