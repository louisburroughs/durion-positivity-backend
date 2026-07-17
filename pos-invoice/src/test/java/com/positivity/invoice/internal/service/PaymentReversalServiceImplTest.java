package com.positivity.invoice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.invoice.internal.entity.Invoice;
import com.positivity.invoice.internal.entity.PaymentIntent;
import com.positivity.invoice.internal.entity.RefundRecord;
import com.positivity.invoice.internal.enums.PaymentIntentStatus;
import com.positivity.invoice.internal.enums.RefundReason;
import com.positivity.invoice.internal.enums.RefundStatus;
import com.positivity.invoice.internal.enums.VoidReason;
import com.positivity.invoice.internal.exception.InsufficientRefundableAmountException;
import com.positivity.invoice.internal.exception.InvalidPaymentStateException;
import com.positivity.invoice.internal.exception.InvoiceNotFoundException;
import com.positivity.invoice.internal.exception.PaymentGatewayException;
import com.positivity.invoice.internal.exception.PaymentIntentNotFoundException;
import com.positivity.invoice.internal.exception.PaymentWindowExpiredException;
import com.positivity.invoice.internal.payment.GatewayPaymentResult;
import com.positivity.invoice.internal.payment.GatewayRefundRequest;
import com.positivity.invoice.internal.payment.GatewayVoidRequest;
import com.positivity.invoice.internal.payment.PaymentGatewayPort;
import com.positivity.invoice.internal.repository.InvoiceRepository;
import com.positivity.invoice.internal.repository.PaymentIntentRepository;
import com.positivity.invoice.internal.repository.RefundRecordRepository;
import com.positivity.invoice.service.RefundPaymentResult;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;

/**
 * Unit tests for {@link PaymentReversalServiceImpl} covering Story #8:
 * Void Authorization / Refund Captured Payment.
 *
 * <p>
 * Covers:
 * <ul>
 * <li>AC1 — AUTHORIZED payment voided via gateway; status → VOIDED; audit
 * entry; VOID_PAYMENT permission required</li>
 * <li>AC2 — CAPTURED payment refund creates RefundRecord with
 * status=COMPLETED; REFUND_PAYMENT permission required</li>
 * <li>AC3 — Partial refund: refundableAmount = capturedAmount − sum(previous
 * refunds); throws InsufficientRefundableAmountException if exceeded</li>
 * <li>AC4 — Time-window: void ≤24 h from authorizedAt; refund ≤180 days from
 * capturedAt; SUPERVISOR_OVERRIDE bypasses; else throws
 * PaymentWindowExpiredException</li>
 * <li>AC6 — voidPayment on non-AUTHORIZED → InvalidPaymentStateException</li>
 * <li>AC7 — refundPayment on non-CAPTURED → InvalidPaymentStateException</li>
 * </ul>
 *
 * Issue: #8
 */
@ExtendWith(MockitoExtension.class)
class PaymentReversalServiceImplTest {

    // Fixed clock: 2026-01-15 14:30:22 UTC
    private static final Instant CLOCK_INSTANT = Instant.parse("2026-01-15T14:30:22Z");

    private static final UUID INVOICE_ID = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final UUID PAYMENT_INTENT_ID = UUID.fromString("00000000-0000-7000-8000-000000000002");
    private static final UUID MANAGER_ID = UUID.fromString("00000000-0000-7000-8000-000000000003");

    /** 4.5 hours before clock — well inside the 24-hour void window */
    private static final Instant RECENT_AUTH = Instant.parse("2026-01-15T10:00:00Z");

    /** 48 hours before clock — outside the 24-hour void window */
    private static final Instant OLD_AUTH = Instant.parse("2026-01-13T14:30:22Z");

    /** 182 days before clock — well outside 180-day refund window */
    private static final Instant OLD_CAPTURE = Instant.parse("2025-07-17T14:30:22Z");

    @Spy
    Clock clock = Clock.fixed(CLOCK_INSTANT, ZoneOffset.UTC);

    @Mock
    private PaymentIntentRepository paymentIntentRepository;

    @Mock
    private RefundRecordRepository refundRecordRepository;

    @Mock
    private InvoiceRepository invoiceRepository;

    @Mock
    private PaymentGatewayPort paymentGatewayPort;

    @InjectMocks
    private PaymentReversalServiceImpl paymentReversalServiceImpl;

    @BeforeEach
    void seedVoidPaymentAuthority() {
        withAuthorities("VOID_PAYMENT", "REFUND_PAYMENT");
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // -------------------------------------------------------------------------
    // AC1 / AC6 — voidPayment
    // -------------------------------------------------------------------------

    /**
     * AC1: AUTHORIZED intent within the 24-hour window is voided successfully.
     * The gateway void is called, status is set to VOIDED, and save is invoked.
     */
    @Test
    void voidPayment_success_setsVoidedStatus() {
        PaymentIntent pi = authorizedPaymentIntent();
        when(paymentIntentRepository.findById(PAYMENT_INTENT_ID)).thenReturn(Optional.of(pi));

        GatewayPaymentResult successResult = org.mockito.Mockito.mock(GatewayPaymentResult.class);
        when(successResult.isSuccessful()).thenReturn(true);
        when(paymentGatewayPort.voidRemainder(any(GatewayVoidRequest.class))).thenReturn(successResult);
        when(paymentIntentRepository.save(any(PaymentIntent.class))).thenAnswer(inv -> inv.getArgument(0));

        paymentReversalServiceImpl.voidPayment(INVOICE_ID, PAYMENT_INTENT_ID, VoidReason.CUSTOMER_REQUEST, null);

        ArgumentCaptor<PaymentIntent> captor = ArgumentCaptor.forClass(PaymentIntent.class);
        verify(paymentIntentRepository).save(captor.capture());

        assertThat(captor.getValue().getStatus()).isEqualTo(PaymentIntentStatus.VOIDED);
        verify(paymentGatewayPort).voidRemainder(any(GatewayVoidRequest.class));
    }

    /**
     * AC6: voidPayment on a CAPTURED intent (not AUTHORIZED) must throw
     * InvalidPaymentStateException (HTTP 409).
     */
    @Test
    void voidPayment_invalidState_throwsInvalidPaymentStateException() {
        PaymentIntent pi = capturedPaymentIntent();
        when(paymentIntentRepository.findById(PAYMENT_INTENT_ID)).thenReturn(Optional.of(pi));

        assertThatThrownBy(() -> paymentReversalServiceImpl.voidPayment(
                        INVOICE_ID, PAYMENT_INTENT_ID, VoidReason.ENTRY_ERROR, null))
                .isInstanceOf(InvalidPaymentStateException.class);

        verify(paymentGatewayPort, never()).voidRemainder(any());
    }

    /**
     * AC4: voidPayment on an AUTHORIZED intent whose authorizedAt is outside the
     * 24-hour window (without SUPERVISOR_OVERRIDE) must throw
     * PaymentWindowExpiredException (HTTP 422).
     */
    @Test
    void voidPayment_outsideTimeWindow_throwsPaymentWindowExpiredException() {
        PaymentIntent pi = authorizedPaymentIntent();
        pi.setCreatedAt(OLD_AUTH); // 48h ago — outside 24h void window
        when(paymentIntentRepository.findById(PAYMENT_INTENT_ID)).thenReturn(Optional.of(pi));

        assertThatThrownBy(() -> paymentReversalServiceImpl.voidPayment(
                        INVOICE_ID, PAYMENT_INTENT_ID, VoidReason.MANAGER_DISCRETION, null))
                .isInstanceOf(PaymentWindowExpiredException.class);

        verify(paymentGatewayPort, never()).voidRemainder(any());
    }

    /**
     * AC4: voidPayment with SUPERVISOR_OVERRIDE authority bypasses the 24-hour
     * window restriction and completes successfully.
     */
    @Test
    void voidPayment_withSupervisorOverride_outsideWindow_succeeds() {
        withAuthorities("VOID_PAYMENT", "REFUND_PAYMENT", "SUPERVISOR_OVERRIDE");

        PaymentIntent pi = authorizedPaymentIntent();
        pi.setCreatedAt(OLD_AUTH); // 48h ago — outside normal window
        when(paymentIntentRepository.findById(PAYMENT_INTENT_ID)).thenReturn(Optional.of(pi));

        GatewayPaymentResult successResult = org.mockito.Mockito.mock(GatewayPaymentResult.class);
        when(successResult.isSuccessful()).thenReturn(true);
        when(paymentGatewayPort.voidRemainder(any(GatewayVoidRequest.class))).thenReturn(successResult);
        when(paymentIntentRepository.save(any(PaymentIntent.class))).thenAnswer(inv -> inv.getArgument(0));

        // Must not throw — SUPERVISOR_OVERRIDE allows bypass
        paymentReversalServiceImpl.voidPayment(INVOICE_ID, PAYMENT_INTENT_ID, VoidReason.FRAUD_PREVENTION, null);

        verify(paymentGatewayPort).voidRemainder(any(GatewayVoidRequest.class));
    }

    @Test
    void refundPayment_withSupervisorOverride_outsideWindow_succeeds() {
        var auth = new UsernamePasswordAuthenticationToken(
                MANAGER_ID.toString(),
                null,
                List.of(
                        new SimpleGrantedAuthority("REFUND_PAYMENT"),
                        new SimpleGrantedAuthority("SUPERVISOR_OVERRIDE")));
        SecurityContextHolder.setContext(new SecurityContextImpl(auth));

        PaymentIntent captured = capturedPaymentIntent();
        captured.setCreatedAt(OLD_CAPTURE);
        when(paymentIntentRepository.findById(PAYMENT_INTENT_ID)).thenReturn(Optional.of(captured));
        when(refundRecordRepository.findByPaymentIntent_Id(PAYMENT_INTENT_ID)).thenReturn(List.of());
        GatewayPaymentResult successResult = successResult();
        when(paymentGatewayPort.refund(any())).thenReturn(successResult);
        when(refundRecordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThatCode(() -> paymentReversalServiceImpl.refundPayment(
                        INVOICE_ID,
                        PAYMENT_INTENT_ID,
                        BigDecimal.valueOf(50),
                        RefundReason.CUSTOMER_RETURN,
                        null,
                        null))
                .doesNotThrowAnyException();

        verify(refundRecordRepository).save(any());
    }

    /**
     * AC1: voidPayment without VOID_PAYMENT authority must throw
     * AccessDeniedException before any gateway interaction.
     */
    @Test
    void voidPayment_missingPermission_throwsAccessDeniedException() {
        withAuthorities("REFUND_PAYMENT"); // no VOID_PAYMENT

        assertThatThrownBy(() -> paymentReversalServiceImpl.voidPayment(
                        INVOICE_ID, PAYMENT_INTENT_ID, VoidReason.CUSTOMER_REQUEST, null))
                .isInstanceOf(AccessDeniedException.class);

        verify(paymentGatewayPort, never()).voidRemainder(any());
        verify(paymentIntentRepository, never()).findById(any());
    }

    @Test
    void refundPayment_missingPermission_throwsAccessDeniedException() {
        var auth = new UsernamePasswordAuthenticationToken(
                MANAGER_ID.toString(), null, List.of(new SimpleGrantedAuthority("VOID_PAYMENT")));
        SecurityContextHolder.setContext(new SecurityContextImpl(auth));

        assertThatThrownBy(() -> paymentReversalServiceImpl.refundPayment(
                        INVOICE_ID,
                        PAYMENT_INTENT_ID,
                        BigDecimal.valueOf(50),
                        RefundReason.CUSTOMER_RETURN,
                        null,
                        null))
                .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(paymentIntentRepository, refundRecordRepository, paymentGatewayPort);
    }

    // -------------------------------------------------------------------------
    // AC2 / AC3 / AC4 / AC7 — refundPayment
    // -------------------------------------------------------------------------

    /**
     * AC2: CAPTURED intent within 180 days, full amount, successful gateway
     * response — RefundRecord is saved with status COMPLETED.
     */
    @Test
    void refundPayment_success_createsRefundRecord() {
        PaymentIntent pi = capturedPaymentIntent(); // capturedAmount = 500
        when(paymentIntentRepository.findById(PAYMENT_INTENT_ID)).thenReturn(Optional.of(pi));
        when(refundRecordRepository.findByPaymentIntent_Id(PAYMENT_INTENT_ID))
                .thenReturn(List.of()); // no prior refunds

        GatewayPaymentResult successResult = successResult();
        when(paymentGatewayPort.refund(any(GatewayRefundRequest.class))).thenReturn(successResult);
        // refund +
        // GatewayRefundRequest

        ArgumentCaptor<RefundRecord> captor = ArgumentCaptor.forClass(RefundRecord.class);
        when(refundRecordRepository.save(any(RefundRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        paymentReversalServiceImpl.refundPayment(
                INVOICE_ID,
                PAYMENT_INTENT_ID,
                BigDecimal.valueOf(500_00, 2),
                RefundReason.CUSTOMER_RETURN,
                null,
                "  WC-2026-000042  ");

        verify(refundRecordRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(captor.getValue().getAmount()).isEqualByComparingTo("500.00");
        // The optional external reference is trimmed and persisted on the refund record.
        assertThat(captor.getValue().getExternalReference()).isEqualTo("WC-2026-000042");
        verify(paymentGatewayPort).refund(any(GatewayRefundRequest.class));
    }

    /**
     * AC3: Partial refund — capturedAmount=500, existing refunds total 100.
     * Requesting 200 is within the 400 remaining refundable amount → succeeds.
     */
    @Test
    void refundPayment_partialRefund_deductsFromRefundable() {
        PaymentIntent pi = capturedPaymentIntent(); // capturedAmount = 500
        when(paymentIntentRepository.findById(PAYMENT_INTENT_ID)).thenReturn(Optional.of(pi));

        // Prior refund of 100
        RefundRecord priorRefund = new RefundRecord();
        priorRefund.setAmount(BigDecimal.valueOf(100_00, 2));
        priorRefund.setStatus(RefundStatus.COMPLETED);
        when(refundRecordRepository.findByPaymentIntent_Id(PAYMENT_INTENT_ID)).thenReturn(List.of(priorRefund));

        GatewayPaymentResult successResult = successResult();
        when(paymentGatewayPort.refund(any(GatewayRefundRequest.class))).thenReturn(successResult);
        when(refundRecordRepository.save(any(RefundRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        // 200 ≤ (500 - 100 = 400 remaining) — should succeed
        paymentReversalServiceImpl.refundPayment(
                INVOICE_ID, PAYMENT_INTENT_ID, BigDecimal.valueOf(200_00, 2), RefundReason.OVERCHARGE, null, null);

        verify(refundRecordRepository).save(any(RefundRecord.class));
    }

    /**
     * AC3: Requesting a refund amount exceeding the remaining refundable amount
     * must throw InsufficientRefundableAmountException (HTTP 422).
     */
    @Test
    void refundPayment_exceedsRefundable_throwsInsufficientRefundableAmountException() {
        PaymentIntent pi = capturedPaymentIntent(); // capturedAmount = 500
        when(paymentIntentRepository.findById(PAYMENT_INTENT_ID)).thenReturn(Optional.of(pi));
        when(refundRecordRepository.findByPaymentIntent_Id(PAYMENT_INTENT_ID)).thenReturn(List.of());

        // 200 > capturedAmount 100 — wait, capturedPaymentIntent has 500; trying 600 to
        // exceed
        assertThatThrownBy(() -> paymentReversalServiceImpl.refundPayment(
                        INVOICE_ID,
                        PAYMENT_INTENT_ID,
                        BigDecimal.valueOf(600_00, 2), // exceeds capturedAmount=500
                        RefundReason.SERVICE_ERROR,
                        null,
                        null))
                .isInstanceOf(InsufficientRefundableAmountException.class);

        verify(paymentGatewayPort, never()).refund(any());
    }

    /**
     * AC4: refundPayment on a CAPTURED intent whose capturedAt is older than
     * 180 days (without SUPERVISOR_OVERRIDE) must throw
     * PaymentWindowExpiredException (HTTP 422).
     */
    @Test
    void refundPayment_outsideRefundWindow_throwsPaymentWindowExpiredException() {
        PaymentIntent pi = capturedPaymentIntent();
        pi.setCreatedAt(OLD_CAPTURE); // 182 days ago — outside 180-day refund window
        when(paymentIntentRepository.findById(PAYMENT_INTENT_ID)).thenReturn(Optional.of(pi));
        when(refundRecordRepository.findByPaymentIntent_Id(PAYMENT_INTENT_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> paymentReversalServiceImpl.refundPayment(
                        INVOICE_ID,
                        PAYMENT_INTENT_ID,
                        BigDecimal.valueOf(100_00, 2),
                        RefundReason.GOODWILL,
                        null,
                        null))
                .isInstanceOf(PaymentWindowExpiredException.class);

        verify(paymentGatewayPort, never()).refund(any());
    }

    /**
     * AC7: refundPayment on a non-CAPTURED intent (e.g., AUTHORIZED) must throw
     * InvalidPaymentStateException (HTTP 409).
     */
    @Test
    void refundPayment_invalidState_throwsInvalidPaymentStateException() {
        PaymentIntent pi = authorizedPaymentIntent(); // AUTHORIZED, not CAPTURED
        when(paymentIntentRepository.findById(PAYMENT_INTENT_ID)).thenReturn(Optional.of(pi));

        assertThatThrownBy(() -> paymentReversalServiceImpl.refundPayment(
                        INVOICE_ID,
                        PAYMENT_INTENT_ID,
                        BigDecimal.valueOf(100_00, 2),
                        RefundReason.CHARGEBACK_AVOIDANCE,
                        null,
                        null))
                .isInstanceOf(InvalidPaymentStateException.class);

        verify(paymentGatewayPort, never()).refund(any());
    }

    // -------------------------------------------------------------------------
    // AC-extra: PaymentIntentNotFoundException paths — voidPayment
    // -------------------------------------------------------------------------

    /**
     * voidPayment: PaymentIntent not found in repository →
     * PaymentIntentNotFoundException.
     */
    @Test
    void voidPayment_intentNotFound_throwsPaymentIntentNotFoundException() {
        when(paymentIntentRepository.findById(PAYMENT_INTENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentReversalServiceImpl.voidPayment(
                        INVOICE_ID, PAYMENT_INTENT_ID, VoidReason.CUSTOMER_REQUEST, null))
                .isInstanceOf(PaymentIntentNotFoundException.class);

        verify(paymentGatewayPort, never()).voidRemainder(any());
    }

    /**
     * voidPayment: PaymentIntent belongs to a different invoice →
     * PaymentIntentNotFoundException.
     */
    @Test
    void voidPayment_invoiceIdMismatch_throwsPaymentIntentNotFoundException() {
        PaymentIntent pi = authorizedPaymentIntent(); // pi.invoiceId = INVOICE_ID
        when(paymentIntentRepository.findById(PAYMENT_INTENT_ID)).thenReturn(Optional.of(pi));

        UUID differentInvoice = UUID.fromString("00000000-0000-7000-8000-000000000099");
        assertThatThrownBy(() -> paymentReversalServiceImpl.voidPayment(
                        differentInvoice, PAYMENT_INTENT_ID, VoidReason.ENTRY_ERROR, null))
                .isInstanceOf(PaymentIntentNotFoundException.class);

        verify(paymentGatewayPort, never()).voidRemainder(any());
    }

    /**
     * voidPayment: gateway returns isSuccessful=false → PaymentGatewayException.
     * Also exercises the PaymentGatewayException(String) constructor.
     */
    @Test
    void voidPayment_gatewayFailure_throwsPaymentGatewayException() {
        PaymentIntent pi = authorizedPaymentIntent();
        when(paymentIntentRepository.findById(PAYMENT_INTENT_ID)).thenReturn(Optional.of(pi));

        GatewayPaymentResult failResult = org.mockito.Mockito.mock(GatewayPaymentResult.class);
        when(failResult.isSuccessful()).thenReturn(false);
        when(paymentGatewayPort.voidRemainder(any(GatewayVoidRequest.class))).thenReturn(failResult);

        assertThatThrownBy(() -> paymentReversalServiceImpl.voidPayment(
                        INVOICE_ID, PAYMENT_INTENT_ID, VoidReason.FRAUD_PREVENTION, null))
                .isInstanceOf(PaymentGatewayException.class);

        verify(paymentIntentRepository, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // AC-extra: PaymentIntentNotFoundException + gateway-failure paths —
    // refundPayment
    // -------------------------------------------------------------------------

    /**
     * refundPayment: PaymentIntent not found in repository →
     * PaymentIntentNotFoundException.
     */
    @Test
    void refundPayment_intentNotFound_throwsPaymentIntentNotFoundException() {
        when(paymentIntentRepository.findById(PAYMENT_INTENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentReversalServiceImpl.refundPayment(
                        INVOICE_ID,
                        PAYMENT_INTENT_ID,
                        BigDecimal.valueOf(100_00, 2),
                        RefundReason.GOODWILL,
                        null,
                        null))
                .isInstanceOf(PaymentIntentNotFoundException.class);

        verifyNoInteractions(refundRecordRepository, paymentGatewayPort);
    }

    /**
     * refundPayment: PaymentIntent belongs to a different invoice →
     * PaymentIntentNotFoundException.
     */
    @Test
    void refundPayment_invoiceIdMismatch_throwsPaymentIntentNotFoundException() {
        PaymentIntent pi = capturedPaymentIntent(); // pi.invoiceId = INVOICE_ID
        when(paymentIntentRepository.findById(PAYMENT_INTENT_ID)).thenReturn(Optional.of(pi));

        UUID differentInvoice = UUID.fromString("00000000-0000-7000-8000-000000000099");
        assertThatThrownBy(() -> paymentReversalServiceImpl.refundPayment(
                        differentInvoice,
                        PAYMENT_INTENT_ID,
                        BigDecimal.valueOf(100_00, 2),
                        RefundReason.OVERCHARGE,
                        null,
                        null))
                .isInstanceOf(PaymentIntentNotFoundException.class);

        verifyNoInteractions(refundRecordRepository, paymentGatewayPort);
    }

    /**
     * refundPayment: gateway returns isSuccessful=false → RefundRecord saved with
     * status FAILED
     * (no exception thrown; record is persisted for audit).
     */
    @Test
    void refundPayment_gatewayFailure_savesFailedRefundRecord() {
        PaymentIntent pi = capturedPaymentIntent();
        when(paymentIntentRepository.findById(PAYMENT_INTENT_ID)).thenReturn(Optional.of(pi));
        when(refundRecordRepository.findByPaymentIntent_Id(PAYMENT_INTENT_ID)).thenReturn(List.of());

        GatewayPaymentResult failResult = org.mockito.Mockito.mock(GatewayPaymentResult.class);
        when(failResult.isSuccessful()).thenReturn(false);
        when(paymentGatewayPort.refund(any(GatewayRefundRequest.class))).thenReturn(failResult);
        when(refundRecordRepository.save(any(RefundRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        paymentReversalServiceImpl.refundPayment(
                INVOICE_ID, PAYMENT_INTENT_ID, BigDecimal.valueOf(100_00, 2), RefundReason.CUSTOMER_RETURN, null, null);

        ArgumentCaptor<RefundRecord> captor = ArgumentCaptor.forClass(RefundRecord.class);
        verify(refundRecordRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(RefundStatus.FAILED);
        assertThat(captor.getValue().getGatewayReference()).isNull();
        assertThat(captor.getValue().getCompletedAt()).isNull();
    }

    // -------------------------------------------------------------------------
    // Issue #922 — refundPayment replay dedupe on externalReference
    // -------------------------------------------------------------------------

    /**
     * #922: a retry carrying the same externalReference must return the existing non-FAILED
     * refund's result WITHOUT calling the payment gateway again and without saving a second
     * RefundRecord — the customer must never be paid twice.
     */
    @Test
    void refundPayment_replaySameExternalReference_returnsExistingWithoutGatewayOrSave() {
        PaymentIntent pi = capturedPaymentIntent();
        when(paymentIntentRepository.findById(PAYMENT_INTENT_ID)).thenReturn(Optional.of(pi));

        RefundRecord existing = new RefundRecord();
        existing.setId(UUID.fromString("00000000-0000-7000-8000-000000000042"));
        existing.setPaymentIntent(pi);
        existing.setInvoice(pi.getInvoice());
        existing.setAmount(BigDecimal.valueOf(120_00, 2));
        existing.setStatus(RefundStatus.COMPLETED);
        existing.setExternalReference("WC-2026-000042");
        when(refundRecordRepository.findByPaymentIntent_Id(PAYMENT_INTENT_ID)).thenReturn(List.of(existing));

        RefundPaymentResult result = paymentReversalServiceImpl.refundPayment(
                INVOICE_ID,
                PAYMENT_INTENT_ID,
                BigDecimal.valueOf(120_00, 2),
                RefundReason.CUSTOMER_RETURN,
                null,
                "  WC-2026-000042  ");

        assertThat(result.getRefundId()).isEqualTo(existing.getId());
        assertThat(result.getAmount()).isEqualByComparingTo("120.00");
        verifyNoInteractions(paymentGatewayPort);
        verify(refundRecordRepository, never()).save(any());
    }

    /**
     * #922: a FAILED refund with the same externalReference must NOT dedupe — the retry
     * proceeds through the gateway and records a new RefundRecord (FAILED references stay
     * retryable; the V12 partial unique index excludes FAILED rows for the same reason).
     */
    @Test
    void refundPayment_failedRefundSameExternalReference_retryProceedsThroughGateway() {
        PaymentIntent pi = capturedPaymentIntent();
        when(paymentIntentRepository.findById(PAYMENT_INTENT_ID)).thenReturn(Optional.of(pi));

        RefundRecord failed = new RefundRecord();
        failed.setId(UUID.fromString("00000000-0000-7000-8000-000000000043"));
        failed.setPaymentIntent(pi);
        failed.setAmount(BigDecimal.valueOf(120_00, 2));
        failed.setStatus(RefundStatus.FAILED);
        failed.setExternalReference("WC-2026-000042");
        when(refundRecordRepository.findByPaymentIntent_Id(PAYMENT_INTENT_ID)).thenReturn(List.of(failed));

        GatewayPaymentResult successResult = successResult();
        when(paymentGatewayPort.refund(any(GatewayRefundRequest.class))).thenReturn(successResult);
        when(refundRecordRepository.save(any(RefundRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        RefundPaymentResult result = paymentReversalServiceImpl.refundPayment(
                INVOICE_ID,
                PAYMENT_INTENT_ID,
                BigDecimal.valueOf(120_00, 2),
                RefundReason.CUSTOMER_RETURN,
                null,
                "WC-2026-000042");

        assertThat(result.getRefundId()).isNotEqualTo(failed.getId());
        assertThat(result.getStatus()).isEqualTo(RefundStatus.COMPLETED);
        verify(paymentGatewayPort).refund(any(GatewayRefundRequest.class));
        verify(refundRecordRepository).save(any(RefundRecord.class));
    }

    // -------------------------------------------------------------------------
    // Issue #922 — listRefundsForInvoice (warranty settlement reconciliation)
    // -------------------------------------------------------------------------

    /** All refunds anchored to the invoice are mapped, including requestedAt. */
    @Test
    void listRefundsForInvoice_mapsAllAnchoredRecords() {
        when(invoiceRepository.existsById(INVOICE_ID)).thenReturn(true);

        RefundRecord paymentAnchored = new RefundRecord();
        paymentAnchored.setId(UUID.fromString("00000000-0000-7000-8000-000000000051"));
        paymentAnchored.setPaymentIntent(capturedPaymentIntent());
        paymentAnchored.setInvoice(invoice(INVOICE_ID));
        paymentAnchored.setAmount(BigDecimal.valueOf(100_00, 2));
        paymentAnchored.setStatus(RefundStatus.COMPLETED);
        paymentAnchored.setReason(RefundReason.CUSTOMER_RETURN);
        paymentAnchored.setGatewayReference("gw-txn-xyz");
        paymentAnchored.setRequestedAt(Instant.parse("2026-01-10T09:00:00Z"));
        paymentAnchored.setCompletedAt(Instant.parse("2026-01-10T09:00:05Z"));

        RefundRecord standalone = new RefundRecord();
        standalone.setId(UUID.fromString("00000000-0000-7000-8000-000000000052"));
        standalone.setInvoice(invoice(INVOICE_ID));
        standalone.setAmount(BigDecimal.valueOf(50_00, 2));
        standalone.setStatus(RefundStatus.COMPLETED);
        standalone.setReason(RefundReason.OTHER);
        standalone.setExternalReference("WC-2026-000042");
        standalone.setRequestedAt(Instant.parse("2026-01-12T10:00:00Z"));
        standalone.setCompletedAt(Instant.parse("2026-01-12T10:00:00Z"));

        when(refundRecordRepository.findAllAnchoredToInvoice(INVOICE_ID))
                .thenReturn(List.of(paymentAnchored, standalone));

        List<RefundPaymentResult> results = paymentReversalServiceImpl.listRefundsForInvoice(INVOICE_ID);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).getRefundId()).isEqualTo(paymentAnchored.getId());
        assertThat(results.get(0).getPaymentIntentId()).isEqualTo(PAYMENT_INTENT_ID);
        assertThat(results.get(0).getGatewayReference()).isEqualTo("gw-txn-xyz");
        assertThat(results.get(0).getRequestedAt()).isEqualTo(Instant.parse("2026-01-10T09:00:00Z"));
        assertThat(results.get(1).getRefundId()).isEqualTo(standalone.getId());
        assertThat(results.get(1).getPaymentIntentId()).isNull();
        assertThat(results.get(1).getExternalReference()).isEqualTo("WC-2026-000042");
        verifyNoInteractions(paymentGatewayPort);
    }

    @Test
    void listRefundsForInvoice_unknownInvoice_throwsInvoiceNotFoundException() {
        when(invoiceRepository.existsById(INVOICE_ID)).thenReturn(false);

        assertThatThrownBy(() -> paymentReversalServiceImpl.listRefundsForInvoice(INVOICE_ID))
                .isInstanceOf(InvoiceNotFoundException.class);

        verifyNoInteractions(refundRecordRepository, paymentGatewayPort);
    }

    // -------------------------------------------------------------------------
    // Issue #926 — standalone refunds (no captured PaymentIntent)
    // -------------------------------------------------------------------------

    /**
     * Invoice-anchored standalone refund: no gateway leg, record saved COMPLETED with the
     * invoice's party id, no PaymentIntent, and completedAt stamped from the clock.
     */
    @Test
    void refundInvoiceStandalone_success_savesCompletedRecordWithoutGateway() {
        withAuthorities("ISSUE_MANUAL_REFUND");
        Invoice invoice = invoiceWithTotal(BigDecimal.valueOf(500_00, 2));
        when(invoiceRepository.findById(INVOICE_ID)).thenReturn(Optional.of(invoice));
        when(refundRecordRepository.findByInvoice_Id(INVOICE_ID)).thenReturn(List.of());
        when(refundRecordRepository.save(any(RefundRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        paymentReversalServiceImpl.refundInvoiceStandalone(
                INVOICE_ID, BigDecimal.valueOf(120_00, 2), RefundReason.OTHER, "walk-in claim", " WC-2026-000042 ");

        ArgumentCaptor<RefundRecord> captor = ArgumentCaptor.forClass(RefundRecord.class);
        verify(refundRecordRepository).save(captor.capture());
        RefundRecord saved = captor.getValue();
        assertThat(saved.getPaymentIntent()).isNull();
        assertThat(saved.getInvoice()).isSameAs(invoice);
        assertThat(saved.getPartyId()).isEqualTo("party-0001");
        assertThat(saved.getStatus()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(saved.getGatewayReference()).isNull();
        assertThat(saved.getExternalReference()).isEqualTo("WC-2026-000042");
        assertThat(saved.getCompletedAt()).isEqualTo(CLOCK_INSTANT);
        verifyNoInteractions(paymentGatewayPort);
    }

    /** Standalone refunds require the finance-only ISSUE_MANUAL_REFUND authority. */
    @Test
    void refundInvoiceStandalone_missingPermission_throwsAccessDeniedException() {
        withAuthorities("REFUND_PAYMENT"); // captured-payment authority is not enough

        assertThatThrownBy(() -> paymentReversalServiceImpl.refundInvoiceStandalone(
                        INVOICE_ID, BigDecimal.valueOf(50), RefundReason.OTHER, null, null))
                .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(invoiceRepository, refundRecordRepository, paymentGatewayPort);
    }

    @Test
    void refundInvoiceStandalone_invoiceNotFound_throwsInvoiceNotFoundException() {
        withAuthorities("ISSUE_MANUAL_REFUND");
        when(invoiceRepository.findById(INVOICE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentReversalServiceImpl.refundInvoiceStandalone(
                        INVOICE_ID, BigDecimal.valueOf(50), RefundReason.OTHER, null, null))
                .isInstanceOf(InvoiceNotFoundException.class);

        verifyNoInteractions(refundRecordRepository, paymentGatewayPort);
    }

    /**
     * Cumulative refunds on the invoice (payment-anchored and standalone alike) are capped at
     * the invoice total: total=500, prior refund 450 → requesting 100 exceeds the remaining 50.
     */
    @Test
    void refundInvoiceStandalone_exceedsInvoiceTotal_throwsInsufficientRefundableAmountException() {
        withAuthorities("ISSUE_MANUAL_REFUND");
        Invoice invoice = invoiceWithTotal(BigDecimal.valueOf(500_00, 2));
        when(invoiceRepository.findById(INVOICE_ID)).thenReturn(Optional.of(invoice));

        RefundRecord prior = new RefundRecord();
        prior.setAmount(BigDecimal.valueOf(450_00, 2));
        prior.setStatus(RefundStatus.COMPLETED);
        when(refundRecordRepository.findByInvoice_Id(INVOICE_ID)).thenReturn(List.of(prior));

        assertThatThrownBy(() -> paymentReversalServiceImpl.refundInvoiceStandalone(
                        INVOICE_ID, BigDecimal.valueOf(100_00, 2), RefundReason.OTHER, null, null))
                .isInstanceOf(InsufficientRefundableAmountException.class);

        verify(refundRecordRepository, never()).save(any());
    }

    /**
     * Idempotent replay guard: retrying with the same externalReference returns the existing
     * non-FAILED standalone record instead of recording a second refund.
     */
    @Test
    void refundInvoiceStandalone_replaySameExternalReference_returnsExistingRecord() {
        withAuthorities("ISSUE_MANUAL_REFUND");
        Invoice invoice = invoiceWithTotal(BigDecimal.valueOf(500_00, 2));
        when(invoiceRepository.findById(INVOICE_ID)).thenReturn(Optional.of(invoice));

        RefundRecord existing = new RefundRecord();
        existing.setId(UUID.fromString("00000000-0000-7000-8000-000000000042"));
        existing.setInvoice(invoice);
        existing.setAmount(BigDecimal.valueOf(120_00, 2));
        existing.setStatus(RefundStatus.COMPLETED);
        existing.setExternalReference("WC-2026-000042");
        when(refundRecordRepository.findByInvoice_Id(INVOICE_ID)).thenReturn(List.of(existing));

        RefundPaymentResult result = paymentReversalServiceImpl.refundInvoiceStandalone(
                INVOICE_ID, BigDecimal.valueOf(120_00, 2), RefundReason.OTHER, null, "WC-2026-000042");

        assertThat(result.getRefundId()).isEqualTo(existing.getId());
        verify(refundRecordRepository, never()).save(any());
    }

    /** Party-anchored standalone refund: no invoice in the system, anchored to the party id. */
    @Test
    void refundPartyStandalone_success_savesCompletedRecordAnchoredToParty() {
        withAuthorities("ISSUE_MANUAL_REFUND");
        when(refundRecordRepository.findByPartyIdAndPaymentIntentIsNull("party-0009"))
                .thenReturn(List.of());
        when(refundRecordRepository.save(any(RefundRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        paymentReversalServiceImpl.refundPartyStandalone(
                " party-0009 ", BigDecimal.valueOf(75_00, 2), RefundReason.OTHER, null, "WC-2026-000043");

        ArgumentCaptor<RefundRecord> captor = ArgumentCaptor.forClass(RefundRecord.class);
        verify(refundRecordRepository).save(captor.capture());
        RefundRecord saved = captor.getValue();
        assertThat(saved.getPaymentIntent()).isNull();
        assertThat(saved.getInvoice()).isNull();
        assertThat(saved.getPartyId()).isEqualTo("party-0009");
        assertThat(saved.getStatus()).isEqualTo(RefundStatus.COMPLETED);
        verifyNoInteractions(paymentGatewayPort);
    }

    @Test
    void refundPartyStandalone_blankPartyId_throwsIllegalArgumentException() {
        withAuthorities("ISSUE_MANUAL_REFUND");

        assertThatThrownBy(() -> paymentReversalServiceImpl.refundPartyStandalone(
                        "   ", BigDecimal.valueOf(50), RefundReason.OTHER, null, null))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(refundRecordRepository, paymentGatewayPort);
    }

    /**
     * The party-anchored replay guard must ignore invoice-anchored standalone refunds even
     * though they carry the same partyId (stamped from the invoice) — a party-endpoint retry
     * must never replay a record with a different anchor shape.
     */
    @Test
    void refundPartyStandalone_invoiceAnchoredRecordSameReference_isNotReplayed() {
        withAuthorities("ISSUE_MANUAL_REFUND");

        RefundRecord invoiceAnchored = new RefundRecord();
        invoiceAnchored.setId(UUID.fromString("00000000-0000-7000-8000-000000000044"));
        invoiceAnchored.setInvoice(invoice(INVOICE_ID));
        invoiceAnchored.setPartyId("party-0009");
        invoiceAnchored.setAmount(BigDecimal.valueOf(75_00, 2));
        invoiceAnchored.setStatus(RefundStatus.COMPLETED);
        invoiceAnchored.setExternalReference("WC-2026-000043");
        when(refundRecordRepository.findByPartyIdAndPaymentIntentIsNull("party-0009"))
                .thenReturn(List.of(invoiceAnchored));
        when(refundRecordRepository.save(any(RefundRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        RefundPaymentResult result = paymentReversalServiceImpl.refundPartyStandalone(
                "party-0009", BigDecimal.valueOf(75_00, 2), RefundReason.OTHER, null, "WC-2026-000043");

        assertThat(result.getRefundId()).isNotEqualTo(invoiceAnchored.getId());
        assertThat(result.getInvoiceId()).isNull();
        verify(refundRecordRepository).save(any(RefundRecord.class));
    }

    @Test
    void refundPartyStandalone_replaySameExternalReference_returnsExistingRecord() {
        withAuthorities("ISSUE_MANUAL_REFUND");

        RefundRecord existing = new RefundRecord();
        existing.setId(UUID.fromString("00000000-0000-7000-8000-000000000043"));
        existing.setPartyId("party-0009");
        existing.setAmount(BigDecimal.valueOf(75_00, 2));
        existing.setStatus(RefundStatus.COMPLETED);
        existing.setExternalReference("WC-2026-000043");
        when(refundRecordRepository.findByPartyIdAndPaymentIntentIsNull("party-0009"))
                .thenReturn(List.of(existing));

        RefundPaymentResult result = paymentReversalServiceImpl.refundPartyStandalone(
                "party-0009", BigDecimal.valueOf(75_00, 2), RefundReason.OTHER, null, "WC-2026-000043");

        assertThat(result.getRefundId()).isEqualTo(existing.getId());
        verify(refundRecordRepository, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // PaymentGatewayException — direct constructor coverage
    // -------------------------------------------------------------------------

    /**
     * Exercises the PaymentGatewayException(String, Throwable) two-arg constructor
     * to reach 100% instruction coverage on that exception class.
     */
    @Test
    void paymentGatewayException_withCause_preservesMessageAndCause() {
        Throwable cause = new RuntimeException("underlying network error");
        PaymentGatewayException ex = new PaymentGatewayException("gateway timeout", cause);

        assertThat(ex.getMessage()).isEqualTo("gateway timeout");
        assertThat(ex.getCause()).isSameAs(cause);
    }

    // -------------------------------------------------------------------------
    // Fixture helpers
    // -------------------------------------------------------------------------

    /**
     * Builds an AUTHORIZED {@link PaymentIntent} fixture with a recent
     * createdAt timestamp (within the 24-hour void window).
     */
    private PaymentIntent authorizedPaymentIntent() {
        PaymentIntent pi = new PaymentIntent();
        pi.setId(PAYMENT_INTENT_ID);
        pi.setInvoice(invoice(INVOICE_ID));
        pi.setStatus(PaymentIntentStatus.AUTHORIZED);
        pi.setAuthorizedAmount(BigDecimal.valueOf(200_00, 2));
        pi.setCapturedAmount(BigDecimal.ZERO);
        pi.setGatewayReference("gw-ref-001");
        pi.setCreatedAt(RECENT_AUTH);
        return pi;
    }

    /**
     * Builds a CAPTURED {@link PaymentIntent} fixture with a recent
     * createdAt timestamp (within the 180-day refund window).
     */
    private PaymentIntent capturedPaymentIntent() {
        PaymentIntent pi = new PaymentIntent();
        pi.setId(PAYMENT_INTENT_ID);
        pi.setInvoice(invoice(INVOICE_ID));
        pi.setStatus(PaymentIntentStatus.CAPTURED);
        pi.setAuthorizedAmount(BigDecimal.valueOf(500_00, 2));
        pi.setCapturedAmount(BigDecimal.valueOf(500_00, 2));
        pi.setGatewayReference("gw-ref-002");
        pi.setCreatedAt(RECENT_AUTH);
        return pi;
    }

    /**
     * Builds a successful {@link GatewayPaymentResult} stub.
     */
    private GatewayPaymentResult successResult() {
        GatewayPaymentResult result = org.mockito.Mockito.mock(GatewayPaymentResult.class);
        when(result.isSuccessful()).thenReturn(true);
        when(result.getGatewayReference()).thenReturn("gw-txn-xyz");
        return result;
    }

    private Invoice invoice(UUID id) {
        Invoice invoice = new Invoice();
        invoice.setId(id);
        return invoice;
    }

    /** Builds an invoice fixture for standalone-refund tests (total + party anchor). */
    private Invoice invoiceWithTotal(BigDecimal total) {
        Invoice invoice = invoice(INVOICE_ID);
        invoice.setTotal(total);
        invoice.setPartyId("party-0001");
        return invoice;
    }

    /**
     * Replaces the security context with the given authority strings.
     */
    private void withAuthorities(String... authorities) {
        var grants =
                List.of(authorities).stream().map(SimpleGrantedAuthority::new).toList();
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken("cashier1", null, grants));
    }
}
