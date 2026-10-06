package com.positivity.invoice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.location.LocationAncestry.AncestorSets;
import com.positivity.invoice.internal.dto.InitiatePaymentRequest;
import com.positivity.invoice.internal.dto.InitiatePaymentResponse;
import com.positivity.invoice.internal.dto.PaymentIntentResponse;
import com.positivity.invoice.internal.entity.Invoice;
import com.positivity.invoice.internal.entity.PaymentIntent;
import com.positivity.invoice.internal.entity.RefundRecord;
import com.positivity.invoice.internal.enums.PaymentFlow;
import com.positivity.invoice.internal.enums.PaymentIntentStatus;
import com.positivity.invoice.internal.enums.RefundStatus;
import com.positivity.invoice.internal.exception.InvalidPaymentStateException;
import com.positivity.invoice.internal.exception.InvoiceNotFoundException;
import com.positivity.invoice.internal.exception.InvoicePartyRequiredException;
import com.positivity.invoice.internal.exception.PaymentDeclinedException;
import com.positivity.invoice.internal.exception.PaymentIdempotencyConflictException;
import com.positivity.invoice.internal.exception.PaymentIntentNotFoundException;
import com.positivity.invoice.internal.payment.GatewayCaptureRequest;
import com.positivity.invoice.internal.payment.GatewayPaymentResult;
import com.positivity.invoice.internal.payment.GatewayVoidRequest;
import com.positivity.invoice.internal.payment.PaymentGatewayPort;
import com.positivity.invoice.internal.repository.InvoiceRepository;
import com.positivity.invoice.internal.repository.PaymentIntentRepository;
import com.positivity.invoice.internal.repository.RefundRecordRepository;
import com.positivity.invoice.internal.security.InvoicePermissions;
import com.positivity.security.common.GatewaySecurityConstants;
import com.positivity.security.common.LocationAncestorResolver;
import com.positivity.security.common.LocationScope;
import com.positivity.security.common.LocationScopeDeniedException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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

/**
 * Unit tests for {@link PaymentServiceImpl} covering Story #9:
 * Initiate Card Authorization and Capture.
 *
 * <p>
 * Covers:
 * <ul>
 * <li>AC1 — SALE_CAPTURE flow: PaymentIntent is created in PENDING then
 * transitions to CAPTURED</li>
 * <li>AC2 — AUTH_ONLY flow: PaymentIntent transitions PENDING → AUTHORIZED;
 * requires invoice:payment:flow_select</li>
 * <li>AC3 — Explicit manual capture: AUTHORIZED → CAPTURED; partial capture
 * voids remainder</li>
 * <li>AC4 — Idempotent retry: same idempotency key returns same result without
 * re-calling gateway</li>
 * <li>AC5 — Unknown gateway outcome triggers status inquiry before retry</li>
 * <li>AC6 — Authorization over $500 requires invoice:payment:limit_override</li>
 * <li>AC7 — AUTH_ONLY requires invoice:payment:flow_select when explicitly
 * requested</li>
 * <li>AC9 — PaymentIntent stores gatewayProvider and raw gatewayResponse</li>
 * </ul>
 *
 * Issue: #9
 */
@ExtendWith(MockitoExtension.class)
class PaymentServiceImplTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final UUID INVOICE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_INVOICE_ID = UUID.fromString("00000000-0000-0000-0000-000000000099");
    private static final UUID PAYMENT_INTENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID OTHER_PAYMENT_INTENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000098");
    private static final String PARTY_ID = "00000000-0000-0000-0000-0000000000aa";
    private static final String IDEMPOTENCY_KEY = "idem-key-001";
    private static final String CAPTURE_IDEMPOTENCY_KEY = "capture-idempotency-key-001";
    private static final BigDecimal AMOUNT_BELOW_LIMIT = BigDecimal.valueOf(200_00, 2);
    private static final BigDecimal AMOUNT_ABOVE_LIMIT = BigDecimal.valueOf(600_00, 2);

    @Spy
    Clock clock = TEST_CLOCK;

    @Mock
    private PaymentGatewayPort gatewayPort;

    @Mock
    private InvoiceRepository invoiceRepository;

    @Mock
    private PaymentIntentRepository paymentIntentRepository;

    @Mock
    private RefundRecordRepository refundRecordRepository;

    @Mock
    private com.positivity.invoice.internal.config.PaymentEventPublisher paymentEventPublisher;

    @InjectMocks
    private PaymentServiceImpl paymentService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /**
     * Sets up the security context with the provided authority strings.
     */
    private void withAuthorities(String... authorities) {
        var grants =
                List.of(authorities).stream().map(SimpleGrantedAuthority::new).toList();
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken("cashier1", null, grants));
    }

    // -------------------------------------------------------------------------
    // AC1 — SALE_CAPTURE flow
    // -------------------------------------------------------------------------

    /**
     * AC1: SALE_CAPTURE completes in one step.
     * PaymentIntent is created in PENDING then transitions to CAPTURED;
     * gateway is called via the combined auth+capture channel.
     */
    @Test
    void initiatePayment_saleCapture_capturesImmediately() {
        withAuthorities(InvoicePermissions.PAYMENT_PROCESS);
        var request = buildRequest(PaymentFlow.SALE_CAPTURE, AMOUNT_BELOW_LIMIT, IDEMPOTENCY_KEY);
        when(invoiceRepository.findById(INVOICE_ID)).thenReturn(Optional.of(invoice(INVOICE_ID)));
        when(paymentIntentRepository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
        when(gatewayPort.saleCapture(any())).thenReturn(capturedResult(AMOUNT_BELOW_LIMIT));
        when(paymentIntentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        InitiatePaymentResponse response = paymentService.initiatePayment(INVOICE_ID, request);

        assertThat(response.getStatus()).isEqualTo(PaymentIntentStatus.CAPTURED);
        assertThat(response.getCapturedAmount()).isEqualByComparingTo(AMOUNT_BELOW_LIMIT);
        verify(gatewayPort).saleCapture(any());
        verify(gatewayPort, never()).authorize(any());
        verify(paymentIntentRepository, times(2)).save(any(PaymentIntent.class));
    }

    // -------------------------------------------------------------------------
    // AC2 — AUTH_ONLY flow
    // -------------------------------------------------------------------------

    /**
     * AC2: AUTH_ONLY flow with invoice:payment:flow_select creates an
     * authorization hold; PaymentIntent is created in PENDING then transitions
     * to AUTHORIZED.
     */
    @Test
    void initiatePayment_authOnly_createsHold() {
        withAuthorities(InvoicePermissions.PAYMENT_PROCESS, InvoicePermissions.PAYMENT_FLOW_SELECT);
        var request = buildRequest(PaymentFlow.AUTH_ONLY, AMOUNT_BELOW_LIMIT, IDEMPOTENCY_KEY);
        when(invoiceRepository.findById(INVOICE_ID)).thenReturn(Optional.of(invoice(INVOICE_ID)));
        when(paymentIntentRepository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
        when(gatewayPort.authorize(any())).thenReturn(authorizedResult(AMOUNT_BELOW_LIMIT));
        when(paymentIntentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        InitiatePaymentResponse response = paymentService.initiatePayment(INVOICE_ID, request);

        assertThat(response.getStatus()).isEqualTo(PaymentIntentStatus.AUTHORIZED);
        assertThat(response.getAuthorizedAmount()).isEqualByComparingTo(AMOUNT_BELOW_LIMIT);
        verify(gatewayPort).authorize(any());
        verify(gatewayPort, never()).saleCapture(any());
    }

    // -------------------------------------------------------------------------
    // AC4 — Idempotency
    // -------------------------------------------------------------------------

    /**
     * AC4: Same idempotency key must return the same stored result without
     * re-calling the gateway (safe retry semantics).
     */
    @Test
    void initiatePayment_idempotent_returnsSameResult() {
        withAuthorities(InvoicePermissions.PAYMENT_PROCESS);
        var request = buildRequest(PaymentFlow.SALE_CAPTURE, AMOUNT_BELOW_LIMIT, IDEMPOTENCY_KEY);
        when(paymentIntentRepository.findByIdempotencyKey(IDEMPOTENCY_KEY))
                .thenReturn(Optional.of(capturedPaymentIntent()));

        InitiatePaymentResponse response = paymentService.initiatePayment(INVOICE_ID, request);

        assertThat(response.getStatus()).isEqualTo(PaymentIntentStatus.CAPTURED);
        verify(gatewayPort, never()).saleCapture(any());
        verify(gatewayPort, never()).authorize(any());
    }

    @Test
    void initiatePayment_idempotent_differentInvoice_throwsConflict() {
        withAuthorities(InvoicePermissions.PAYMENT_PROCESS);
        var request = buildRequest(PaymentFlow.SALE_CAPTURE, AMOUNT_BELOW_LIMIT, IDEMPOTENCY_KEY);
        var existing = capturedPaymentIntent();
        existing.setInvoice(invoice(OTHER_INVOICE_ID));
        when(paymentIntentRepository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> paymentService.initiatePayment(INVOICE_ID, request))
                .isInstanceOf(PaymentIdempotencyConflictException.class)
                .hasMessageContaining("already used with a different payment request");

        verify(gatewayPort, never()).saleCapture(any());
        verify(gatewayPort, never()).authorize(any());
        verify(paymentIntentRepository, never()).save(any(PaymentIntent.class));
    }

    @Test
    void initiatePayment_idempotent_differentAmount_throwsConflict() {
        withAuthorities(InvoicePermissions.PAYMENT_PROCESS);
        var request = buildRequest(PaymentFlow.SALE_CAPTURE, BigDecimal.valueOf(210_00, 2), IDEMPOTENCY_KEY);
        when(paymentIntentRepository.findByIdempotencyKey(IDEMPOTENCY_KEY))
                .thenReturn(Optional.of(capturedPaymentIntent()));

        assertThatThrownBy(() -> paymentService.initiatePayment(INVOICE_ID, request))
                .isInstanceOf(PaymentIdempotencyConflictException.class)
                .hasMessageContaining("already used with a different payment request");

        verify(gatewayPort, never()).saleCapture(any());
        verify(gatewayPort, never()).authorize(any());
        verify(paymentIntentRepository, never()).save(any(PaymentIntent.class));
    }

    // -------------------------------------------------------------------------
    // AC5 — Unknown outcome → status inquiry
    // -------------------------------------------------------------------------

    /**
     * AC5: When the gateway returns an unknown outcome, the service must perform
     * a status inquiry on the gateway reference before returning, to avoid
     * duplicate charges.
     */
    @Test
    void initiatePayment_unknownOutcome_performsStatusInquiry() {
        withAuthorities(InvoicePermissions.PAYMENT_PROCESS);
        var request = buildRequest(PaymentFlow.SALE_CAPTURE, AMOUNT_BELOW_LIMIT, IDEMPOTENCY_KEY);
        var unknownResult = unknownOutcomeResult();
        when(invoiceRepository.findById(INVOICE_ID)).thenReturn(Optional.of(invoice(INVOICE_ID)));
        when(paymentIntentRepository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
        when(gatewayPort.saleCapture(any())).thenReturn(unknownResult);
        when(gatewayPort.inquireStatus(any())).thenReturn(capturedResult(AMOUNT_BELOW_LIMIT));
        when(paymentIntentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        InitiatePaymentResponse response = paymentService.initiatePayment(INVOICE_ID, request);

        verify(gatewayPort).inquireStatus(any());
        assertThat(response.getStatus()).isEqualTo(PaymentIntentStatus.CAPTURED);
    }

    @Test
    void initiatePayment_authOnly_unknownOutcome_performsStatusInquiry() {
        withAuthorities(InvoicePermissions.PAYMENT_PROCESS, InvoicePermissions.PAYMENT_FLOW_SELECT);
        var request = buildRequest(PaymentFlow.AUTH_ONLY, AMOUNT_BELOW_LIMIT, IDEMPOTENCY_KEY);
        var unknownResult = unknownOutcomeResult();
        var successResult = authorizedResult(AMOUNT_BELOW_LIMIT);
        when(invoiceRepository.findById(INVOICE_ID)).thenReturn(Optional.of(invoice(INVOICE_ID)));
        when(paymentIntentRepository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
        when(gatewayPort.authorize(any())).thenReturn(unknownResult);
        when(gatewayPort.inquireStatus(unknownResult.getGatewayReference())).thenReturn(successResult);
        when(paymentIntentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        InitiatePaymentResponse response = paymentService.initiatePayment(INVOICE_ID, request);

        verify(gatewayPort, times(1)).authorize(any());
        verify(gatewayPort, times(1)).inquireStatus(unknownResult.getGatewayReference());
        assertThat(response.getStatus()).isEqualTo(PaymentIntentStatus.AUTHORIZED);
    }

    @Test
    void initiatePayment_saleCapture_setsCaptureFailedWhenDeclined() {
        withAuthorities(InvoicePermissions.PAYMENT_PROCESS);
        var request = buildRequest(PaymentFlow.SALE_CAPTURE, AMOUNT_BELOW_LIMIT, IDEMPOTENCY_KEY);
        when(invoiceRepository.findById(INVOICE_ID)).thenReturn(Optional.of(invoice(INVOICE_ID)));
        when(paymentIntentRepository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
        when(gatewayPort.saleCapture(any())).thenReturn(declinedResult());
        when(paymentIntentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThatThrownBy(() -> paymentService.initiatePayment(INVOICE_ID, request))
                .isInstanceOf(PaymentDeclinedException.class)
                .hasMessage("Gateway declined the payment");

        ArgumentCaptor<PaymentIntent> paymentIntentCaptor = ArgumentCaptor.forClass(PaymentIntent.class);
        verify(paymentIntentRepository, times(2)).save(paymentIntentCaptor.capture());
        assertThat(paymentIntentCaptor.getAllValues())
                .extracting(PaymentIntent::getStatus)
                .contains(PaymentIntentStatus.CAPTURE_FAILED);
    }

    // -------------------------------------------------------------------------
    // Permission checks — AC6, AC7
    // -------------------------------------------------------------------------

    /**
     * Initiating a payment without invoice:payment:process must throw an
     * authorization exception (AC6 baseline permission), whatever else is held.
     */
    @Test
    void initiatePayment_requiresProcessPaymentPermission() {
        withAuthorities(
                InvoicePermissions.MANAGE,
                InvoicePermissions.PAYMENT_CAPTURE,
                InvoicePermissions.PAYMENT_LIMIT_OVERRIDE,
                InvoicePermissions.PAYMENT_FLOW_SELECT);
        var request = buildRequest(PaymentFlow.SALE_CAPTURE, AMOUNT_BELOW_LIMIT, IDEMPOTENCY_KEY);

        assertThatThrownBy(() -> paymentService.initiatePayment(INVOICE_ID, request))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining(InvoicePermissions.PAYMENT_PROCESS);
        verifyNoInteractions(gatewayPort, paymentIntentRepository, invoiceRepository);
    }

    /**
     * #2393: the four retired raw strings are no longer authorities the service recognises, so a
     * caller holding only them is denied on every path.
     */
    @Test
    void initiatePayment_legacyRawAuthorities_areNotRecognised() {
        withAuthorities("PROCESS_PAYMENT", "OVERRIDE_PAYMENT_LIMIT", "SELECT_PAYMENT_FLOW", "MANUAL_CAPTURE");
        var request = buildRequest(PaymentFlow.SALE_CAPTURE, AMOUNT_BELOW_LIMIT, IDEMPOTENCY_KEY);

        assertThatThrownBy(() -> paymentService.initiatePayment(INVOICE_ID, request))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> paymentService.capturePayment(
                        INVOICE_ID, PAYMENT_INTENT_ID, AMOUNT_BELOW_LIMIT, CAPTURE_IDEMPOTENCY_KEY))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(gatewayPort, paymentIntentRepository, invoiceRepository);
    }

    /**
     * AC6: Authorization amount over $500 without invoice:payment:limit_override
     * must throw an authorization exception naming that code.
     */
    @Test
    void initiatePayment_overThreshold_requiresOverridePermission() {
        withAuthorities(InvoicePermissions.PAYMENT_PROCESS, InvoicePermissions.PAYMENT_FLOW_SELECT);
        var request = buildRequest(PaymentFlow.SALE_CAPTURE, AMOUNT_ABOVE_LIMIT, IDEMPOTENCY_KEY);

        assertThatThrownBy(() -> paymentService.initiatePayment(INVOICE_ID, request))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining(InvoicePermissions.PAYMENT_LIMIT_OVERRIDE);
        verifyNoInteractions(gatewayPort, paymentIntentRepository, invoiceRepository);
    }

    /** AC6: with invoice:payment:limit_override an over-$500 payment goes through. */
    @Test
    void initiatePayment_overThreshold_withOverridePermission_captures() {
        withAuthorities(InvoicePermissions.PAYMENT_PROCESS, InvoicePermissions.PAYMENT_LIMIT_OVERRIDE);
        var request = buildRequest(PaymentFlow.SALE_CAPTURE, AMOUNT_ABOVE_LIMIT, IDEMPOTENCY_KEY);
        when(invoiceRepository.findById(INVOICE_ID)).thenReturn(Optional.of(invoice(INVOICE_ID)));
        when(paymentIntentRepository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
        when(gatewayPort.saleCapture(any())).thenReturn(capturedResult(AMOUNT_ABOVE_LIMIT));
        when(paymentIntentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        InitiatePaymentResponse response = paymentService.initiatePayment(INVOICE_ID, request);

        assertThat(response.getStatus()).isEqualTo(PaymentIntentStatus.CAPTURED);
        assertThat(response.getCapturedAmount()).isEqualByComparingTo(AMOUNT_ABOVE_LIMIT);
    }

    /** AC6 boundary: exactly $500.00 is not over the threshold, so the override is not needed. */
    @Test
    void initiatePayment_atThreshold_doesNotRequireOverridePermission() {
        withAuthorities(InvoicePermissions.PAYMENT_PROCESS);
        var atLimit = new BigDecimal("500.00");
        var request = buildRequest(PaymentFlow.SALE_CAPTURE, atLimit, IDEMPOTENCY_KEY);
        when(invoiceRepository.findById(INVOICE_ID)).thenReturn(Optional.of(invoice(INVOICE_ID)));
        when(paymentIntentRepository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
        when(gatewayPort.saleCapture(any())).thenReturn(capturedResult(atLimit));
        when(paymentIntentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThat(paymentService.initiatePayment(INVOICE_ID, request).getStatus())
                .isEqualTo(PaymentIntentStatus.CAPTURED);
    }

    /**
     * AC7: AUTH_ONLY flow explicitly requested by cashier without
     * invoice:payment:flow_select must throw an authorization exception naming that code.
     */
    @Test
    void initiatePayment_authOnly_requiresSelectPaymentFlowPermission() {
        // flow_select intentionally absent; limit_override held to prove it is not a substitute
        withAuthorities(InvoicePermissions.PAYMENT_PROCESS, InvoicePermissions.PAYMENT_LIMIT_OVERRIDE);
        var request = buildRequest(PaymentFlow.AUTH_ONLY, AMOUNT_BELOW_LIMIT, IDEMPOTENCY_KEY);

        assertThatThrownBy(() -> paymentService.initiatePayment(INVOICE_ID, request))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining(InvoicePermissions.PAYMENT_FLOW_SELECT);
        verifyNoInteractions(gatewayPort, paymentIntentRepository, invoiceRepository);
    }

    /** AC6 + AC7: an over-$500 AUTH_ONLY hold needs both conditional codes. */
    @Test
    void initiatePayment_authOnlyOverThreshold_requiresBothConditionalPermissions() {
        withAuthorities(InvoicePermissions.PAYMENT_PROCESS, InvoicePermissions.PAYMENT_FLOW_SELECT);
        var request = buildRequest(PaymentFlow.AUTH_ONLY, AMOUNT_ABOVE_LIMIT, IDEMPOTENCY_KEY);

        assertThatThrownBy(() -> paymentService.initiatePayment(INVOICE_ID, request))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining(InvoicePermissions.PAYMENT_LIMIT_OVERRIDE);
    }

    // -------------------------------------------------------------------------
    // AC3 — Manual capture and partial capture
    // -------------------------------------------------------------------------

    /**
     * AC3: Partial capture (capturedAmount < authorizedAmount) must automatically
     * void the remainder; voidedRemainderAmount must be set to the difference.
     */
    @Test
    void capturePayment_partialCapture_voidRemainderAutomatically() {
        withAuthorities(InvoicePermissions.PAYMENT_CAPTURE);
        var authorizedAmount = BigDecimal.valueOf(300_00, 2);
        var partialAmount = BigDecimal.valueOf(200_00, 2);
        var expectedVoided = authorizedAmount.subtract(partialAmount);
        when(paymentIntentRepository.findById(PAYMENT_INTENT_ID))
                .thenReturn(Optional.of(authorizedPaymentIntent(authorizedAmount)));
        when(gatewayPort.capture(any())).thenReturn(capturedResult(partialAmount));
        when(gatewayPort.voidRemainder(any())).thenReturn(voidedResult());
        when(paymentIntentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        InitiatePaymentResponse response =
                paymentService.capturePayment(INVOICE_ID, PAYMENT_INTENT_ID, partialAmount, CAPTURE_IDEMPOTENCY_KEY);

        assertThat(response.getStatus()).isEqualTo(PaymentIntentStatus.CAPTURED);
        assertThat(response.getCapturedAmount()).isEqualByComparingTo(partialAmount);
        assertThat(response.getVoidedRemainderAmount()).isEqualByComparingTo(expectedVoided);

        ArgumentCaptor<GatewayCaptureRequest> captureRequestCaptor =
                ArgumentCaptor.forClass(GatewayCaptureRequest.class);
        verify(gatewayPort).capture(captureRequestCaptor.capture());
        assertThat(captureRequestCaptor.getValue().gatewayReference()).isEqualTo("gw-auth-ref-001");

        ArgumentCaptor<GatewayVoidRequest> voidRequestCaptor = ArgumentCaptor.forClass(GatewayVoidRequest.class);
        verify(gatewayPort).voidRemainder(voidRequestCaptor.capture());
        assertThat(voidRequestCaptor.getValue().gatewayReference()).isEqualTo("gw-auth-ref-001");
    }

    /**
     * AC3: Explicit capture without invoice:payment:capture must throw
     * an authorization exception naming that code; invoice:payment:process is not a substitute.
     */
    @Test
    void capturePayment_requiresManualCapturePermission() {
        withAuthorities(InvoicePermissions.PAYMENT_PROCESS); // invoice:payment:capture intentionally absent
        var captureAmount = BigDecimal.valueOf(200);

        assertThatThrownBy(() -> paymentService.capturePayment(
                        INVOICE_ID, PAYMENT_INTENT_ID, captureAmount, CAPTURE_IDEMPOTENCY_KEY))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining(InvoicePermissions.PAYMENT_CAPTURE);
        verifyNoInteractions(gatewayPort, paymentIntentRepository);
    }

    /**
     * AC3: Explicit manual capture with invoice:payment:capture must
     * transition PaymentIntent AUTHORIZED → CAPTURED; full amount captured.
     */
    @Test
    void capturePayment_transitionsAuthorizedToCaptured() {
        withAuthorities(InvoicePermissions.PAYMENT_CAPTURE);
        var authorizedAmount = BigDecimal.valueOf(300_00, 2);
        when(paymentIntentRepository.findById(PAYMENT_INTENT_ID))
                .thenReturn(Optional.of(authorizedPaymentIntent(authorizedAmount)));
        when(gatewayPort.capture(any())).thenReturn(capturedResult(authorizedAmount));
        when(paymentIntentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        InitiatePaymentResponse response =
                paymentService.capturePayment(INVOICE_ID, PAYMENT_INTENT_ID, authorizedAmount, CAPTURE_IDEMPOTENCY_KEY);

        assertThat(response.getStatus()).isEqualTo(PaymentIntentStatus.CAPTURED);
        assertThat(response.getCapturedAmount()).isEqualByComparingTo(authorizedAmount);
    }

    @Test
    void capturePayment_unknownOutcome_performsStatusInquiry() {
        withAuthorities(InvoicePermissions.PAYMENT_CAPTURE);
        var captureAmount = BigDecimal.valueOf(200_00, 2);
        var unknownResult = unknownOutcomeResult();
        var successResult = capturedResult(captureAmount);
        when(paymentIntentRepository.findById(PAYMENT_INTENT_ID))
                .thenReturn(Optional.of(authorizedPaymentIntent(BigDecimal.valueOf(300_00, 2))));
        when(gatewayPort.capture(any())).thenReturn(unknownResult);
        when(gatewayPort.inquireStatus("gw-auth-ref-001")).thenReturn(successResult);
        when(paymentIntentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        InitiatePaymentResponse response =
                paymentService.capturePayment(INVOICE_ID, PAYMENT_INTENT_ID, captureAmount, CAPTURE_IDEMPOTENCY_KEY);

        verify(gatewayPort, times(1)).inquireStatus("gw-auth-ref-001");
        assertThat(response.getStatus()).isEqualTo(PaymentIntentStatus.CAPTURED);
    }

    @Test
    void capturePayment_throws_whenPaymentIntentNotInAuthorizedState() {
        withAuthorities(InvoicePermissions.PAYMENT_CAPTURE);
        var captureAmount = BigDecimal.valueOf(200, 2);
        var paymentIntent = new PaymentIntent();
        paymentIntent.setId(PAYMENT_INTENT_ID);
        paymentIntent.setInvoice(invoice(INVOICE_ID));
        paymentIntent.setStatus(PaymentIntentStatus.CAPTURED);
        when(paymentIntentRepository.findById(PAYMENT_INTENT_ID)).thenReturn(Optional.of(paymentIntent));

        assertThatThrownBy(() -> paymentService.capturePayment(
                        INVOICE_ID, PAYMENT_INTENT_ID, captureAmount, CAPTURE_IDEMPOTENCY_KEY))
                .isInstanceOf(InvalidPaymentStateException.class)
                .hasMessageContaining("AUTHORIZED status");
    }

    @Test
    void capturePayment_throws_whenPaymentIntentNotFound() {
        withAuthorities(InvoicePermissions.PAYMENT_CAPTURE);
        var captureAmount = BigDecimal.valueOf(200, 2);
        when(paymentIntentRepository.findById(PAYMENT_INTENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentService.capturePayment(
                        INVOICE_ID, PAYMENT_INTENT_ID, captureAmount, CAPTURE_IDEMPOTENCY_KEY))
                .isInstanceOf(PaymentIntentNotFoundException.class)
                .hasMessageContaining("Payment intent not found");
    }

    // -------------------------------------------------------------------------
    // AC9 — Gateway metadata stored on PaymentIntent
    // -------------------------------------------------------------------------

    /**
     * AC9: PaymentIntent must store gatewayProvider and raw gatewayResponse
     * fields populated from the gateway result (supports future adapter
     * portability).
     */
    @Test
    void initiatePayment_saleCapture_storesGatewayMetadata() {
        withAuthorities(InvoicePermissions.PAYMENT_PROCESS);
        var request = buildRequest(PaymentFlow.SALE_CAPTURE, AMOUNT_BELOW_LIMIT, IDEMPOTENCY_KEY);
        var result = capturedResult(AMOUNT_BELOW_LIMIT);
        when(invoiceRepository.findById(INVOICE_ID)).thenReturn(Optional.of(invoice(INVOICE_ID)));
        when(paymentIntentRepository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
        when(gatewayPort.saleCapture(any())).thenReturn(result);
        when(paymentIntentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        InitiatePaymentResponse response = paymentService.initiatePayment(INVOICE_ID, request);

        assertThat(response.getGatewayProvider()).isNotBlank();
        assertThat(response.getGatewayResponse()).isNotNull();
    }

    // -------------------------------------------------------------------------
    // Fixture / builder helpers
    // -------------------------------------------------------------------------

    /**
     * Builds an {@link InitiatePaymentRequest} with the given flow, amount and
     * idempotency key.
     *
     * @param flow           payment flow (SALE_CAPTURE or AUTH_ONLY)
     * @param amount         payment amount
     * @param idempotencyKey client-provided idempotency key
     * @return populated request DTO
     */
    private InitiatePaymentRequest buildRequest(PaymentFlow flow, BigDecimal amount, String idempotencyKey) {
        var req = new InitiatePaymentRequest();
        req.setPaymentFlow(flow);
        req.setAmount(amount);
        req.setIdempotencyKey(idempotencyKey);
        req.setPaymentToken("tok_test_001"); // tokenized — no PAN (AC8)
        return req;
    }

    private PaymentIntent capturedPaymentIntent() {
        var intent = new PaymentIntent();
        intent.setId(PAYMENT_INTENT_ID);
        intent.setInvoice(invoice(INVOICE_ID));
        intent.setIdempotencyKey(IDEMPOTENCY_KEY);
        intent.setPaymentFlow(PaymentFlow.SALE_CAPTURE);
        intent.setPaymentToken("tok_test_001");
        intent.setStatus(PaymentIntentStatus.CAPTURED);
        intent.setAuthorizedAmount(AMOUNT_BELOW_LIMIT);
        intent.setCapturedAmount(AMOUNT_BELOW_LIMIT);
        intent.setVoidedRemainderAmount(BigDecimal.ZERO);
        intent.setGatewayProvider("stripe");
        intent.setGatewayResponse("{}");
        return intent;
    }

    private PaymentIntent authorizedPaymentIntent(BigDecimal authorizedAmount) {
        var intent = new PaymentIntent();
        intent.setId(PAYMENT_INTENT_ID);
        intent.setInvoice(invoice(INVOICE_ID));
        intent.setStatus(PaymentIntentStatus.AUTHORIZED);
        intent.setAuthorizedAmount(authorizedAmount);
        intent.setGatewayProvider("stripe");
        intent.setGatewayResponse("{}");
        intent.setGatewayReference("gw-auth-ref-001");
        return intent;
    }

    /**
     * Stubs a successful captured gateway result.
     *
     * @param amount the captured amount
     * @return mocked {@link GatewayPaymentResult}
     */
    private GatewayPaymentResult capturedResult(BigDecimal amount) {
        return gatewayResult(true, false, amount, "gw-ref-001", "stripe", "{\"status\":\"captured\"}");
    }

    private GatewayPaymentResult authorizedResult(BigDecimal amount) {
        return gatewayResult(true, false, amount, "gw-ref-001", "stripe", "{\"status\":\"authorized\"}");
    }

    private GatewayPaymentResult unknownOutcomeResult() {
        return gatewayResult(false, true, null, "gw-ref-001", "stripe", "{\"status\":\"unknown\"}");
    }

    private GatewayPaymentResult declinedResult() {
        return gatewayResult(false, false, AMOUNT_BELOW_LIMIT, "gw-ref-001", "stripe", "{\"status\":\"declined\"}");
    }

    private GatewayPaymentResult voidedResult() {
        return gatewayResult(true, false, null, null, "stripe", "{\"status\":\"voided\"}");
    }

    private GatewayPaymentResult gatewayResult(
            boolean successful,
            boolean unknown,
            BigDecimal amount,
            String gatewayReference,
            String gatewayProvider,
            String rawResponse) {
        return new GatewayPaymentResult() {
            @Override
            public boolean isSuccessful() {
                return successful;
            }

            @Override
            public boolean isUnknown() {
                return unknown;
            }

            @Override
            public BigDecimal getAmount() {
                return amount;
            }

            @Override
            public String getGatewayReference() {
                return gatewayReference;
            }

            @Override
            public String getGatewayProvider() {
                return gatewayProvider;
            }

            @Override
            public String getRawResponse() {
                return rawResponse;
            }
        };
    }

    /** An invoice that names a customer, as every invoice reaching a payment must (CAP:550 S9). */
    private Invoice invoice(UUID id) {
        Invoice invoice = new Invoice();
        invoice.setId(id);
        invoice.setPartyId(PARTY_ID);
        return invoice;
    }

    // -------------------------------------------------------------------------
    // CAP:550 S9 — party backstops (spec §4.4 item 1, AW12)
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("party backstops (CAP:550 S9, #2507)")
    class PartyBackstops {

        private Invoice partyLessInvoice(String partyId) {
            Invoice invoice = new Invoice();
            invoice.setId(INVOICE_ID);
            invoice.setInvoiceNumber("INV-000009");
            invoice.setPartyId(partyId);
            return invoice;
        }

        /** AC2: no PaymentIntent row and no gateway call for a null party. */
        @Test
        @DisplayName("initiatePayment (SALE_CAPTURE) on a null-party invoice: 422, no intent saved, gateway untouched")
        void initiatePayment_nullParty_refusedBeforeIntentAndGateway() {
            withAuthorities(InvoicePermissions.PAYMENT_PROCESS);
            var request = buildRequest(PaymentFlow.SALE_CAPTURE, AMOUNT_BELOW_LIMIT, IDEMPOTENCY_KEY);
            when(invoiceRepository.findById(INVOICE_ID)).thenReturn(Optional.of(partyLessInvoice(null)));
            when(paymentIntentRepository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> paymentService.initiatePayment(INVOICE_ID, request))
                    .isInstanceOf(InvoicePartyRequiredException.class)
                    .hasMessageContaining("no customer");

            verify(paymentIntentRepository, never()).save(any());
            verifyNoInteractions(gatewayPort);
            verifyNoInteractions(paymentEventPublisher);
        }

        /** A blank party is as missing as a null one. */
        @Test
        @DisplayName("initiatePayment (AUTH_ONLY) on a blank-party invoice: 422, no intent saved, gateway untouched")
        void initiatePayment_blankParty_refusedBeforeIntentAndGateway() {
            withAuthorities(InvoicePermissions.PAYMENT_PROCESS, InvoicePermissions.PAYMENT_FLOW_SELECT);
            var request = buildRequest(PaymentFlow.AUTH_ONLY, AMOUNT_BELOW_LIMIT, IDEMPOTENCY_KEY);
            when(invoiceRepository.findById(INVOICE_ID)).thenReturn(Optional.of(partyLessInvoice("  ")));
            when(paymentIntentRepository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> paymentService.initiatePayment(INVOICE_ID, request))
                    .isInstanceOf(InvoicePartyRequiredException.class);

            verify(paymentIntentRepository, never()).save(any());
            verifyNoInteractions(gatewayPort);
        }

        /** AC3: an AUTHORIZED hold on a party-less invoice is not captured and stays AUTHORIZED. */
        @Test
        @DisplayName("capturePayment on a null-party invoice: 422, gateway capture not called, hold stays AUTHORIZED")
        void capturePayment_nullParty_refusedBeforeGateway() {
            withAuthorities(InvoicePermissions.PAYMENT_CAPTURE);
            PaymentIntent intent = authorizedPaymentIntent(AMOUNT_BELOW_LIMIT);
            intent.setInvoice(partyLessInvoice(null));
            when(paymentIntentRepository.findById(PAYMENT_INTENT_ID)).thenReturn(Optional.of(intent));

            assertThatThrownBy(() -> paymentService.capturePayment(
                            INVOICE_ID, PAYMENT_INTENT_ID, AMOUNT_BELOW_LIMIT, CAPTURE_IDEMPOTENCY_KEY))
                    .isInstanceOf(InvoicePartyRequiredException.class);

            assertThat(intent.getStatus()).isEqualTo(PaymentIntentStatus.AUTHORIZED);
            verify(paymentIntentRepository, never()).save(any());
            verifyNoInteractions(gatewayPort);
            verifyNoInteractions(paymentEventPublisher);
        }

        /** The state check still comes first: a non-AUTHORIZED intent answers INVALID_PAYMENT_STATE, not the party code. */
        @Test
        @DisplayName("capturePayment: the AUTHORIZED state check precedes the party check")
        void capturePayment_stateCheckPrecedesPartyCheck() {
            withAuthorities(InvoicePermissions.PAYMENT_CAPTURE);
            PaymentIntent intent = capturedPaymentIntent();
            intent.setInvoice(partyLessInvoice(null));
            when(paymentIntentRepository.findById(PAYMENT_INTENT_ID)).thenReturn(Optional.of(intent));

            assertThatThrownBy(() -> paymentService.capturePayment(
                            INVOICE_ID, PAYMENT_INTENT_ID, AMOUNT_BELOW_LIMIT, CAPTURE_IDEMPOTENCY_KEY))
                    .isInstanceOf(InvalidPaymentStateException.class);
            verifyNoInteractions(gatewayPort);
        }

        /** AC8: a replay of an intent created before deploy returns the stored intent unchanged, party or not. */
        @Test
        @DisplayName("initiatePayment replay of a pre-deploy intent on a party-less invoice returns it unchanged")
        void initiatePayment_replayOnPartyLessInvoice_returnsStoredIntent() {
            withAuthorities(InvoicePermissions.PAYMENT_PROCESS);
            var request = buildRequest(PaymentFlow.SALE_CAPTURE, AMOUNT_BELOW_LIMIT, IDEMPOTENCY_KEY);
            PaymentIntent stored = capturedPaymentIntent();
            stored.setInvoice(partyLessInvoice(null));
            when(paymentIntentRepository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.of(stored));

            InitiatePaymentResponse response = paymentService.initiatePayment(INVOICE_ID, request);

            assertThat(response.getPaymentIntentId()).isEqualTo(PAYMENT_INTENT_ID);
            assertThat(response.getStatus()).isEqualTo(PaymentIntentStatus.CAPTURED);
            verify(paymentIntentRepository, never()).save(any());
            verifyNoInteractions(gatewayPort);
            verifyNoInteractions(paymentEventPublisher);
        }

        /** AC4: the CASH house account is a valid party — a sale-capture on it settles and publishes. */
        @Test
        @DisplayName("initiatePayment on the CASH house-account party captures and publishes the settled fact")
        void initiatePayment_cashHouseAccountParty_captures() {
            withAuthorities(InvoicePermissions.PAYMENT_PROCESS);
            var request = buildRequest(PaymentFlow.SALE_CAPTURE, AMOUNT_BELOW_LIMIT, IDEMPOTENCY_KEY);
            Invoice cashInvoice = partyLessInvoice("00000000-0000-0000-0000-0000000000ca");
            when(invoiceRepository.findById(INVOICE_ID)).thenReturn(Optional.of(cashInvoice));
            when(paymentIntentRepository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
            when(gatewayPort.saleCapture(any())).thenReturn(capturedResult(AMOUNT_BELOW_LIMIT));
            when(paymentIntentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            InitiatePaymentResponse response = paymentService.initiatePayment(INVOICE_ID, request);

            assertThat(response.getStatus()).isEqualTo(PaymentIntentStatus.CAPTURED);
            ArgumentCaptor<PaymentIntent> captor = ArgumentCaptor.forClass(PaymentIntent.class);
            verify(paymentEventPublisher).publishPaymentSettled(captor.capture());
            assertThat(captor.getValue().getInvoice().getPartyId()).isEqualTo("00000000-0000-0000-0000-0000000000ca");
        }
    }

    // -------------------------------------------------------------------------
    // listInvoicePayments / getInvoicePayment (#2226, #2215)
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("listInvoicePayments / getInvoicePayment (#2226, #2215)")
    class PaymentReadTests {

        /** The node a scoped caller is assigned: a region above the test invoice's location. */
        private final UUID regionNode = UUID.fromString("019200aa-0000-7000-8000-00000000a000");

        private final UUID otherShop = UUID.fromString("019200aa-0000-7000-8000-00000000000b");

        private final UUID testLocation = UUID.fromString("01960003-0000-7000-8000-000000000001");

        /** Replica stand-in: testLocation sits under regionNode on the OTHER dimension; otherShop does not. */
        private final LocationAncestorResolver resolver = id -> {
            if (id.equals(testLocation)) {
                return new AncestorSets(Set.of(id), Set.of(id, regionNode));
            }
            if (id.equals(otherShop)) {
                return new AncestorSets(Set.of(id), Set.of(id));
            }
            return AncestorSets.EMPTY;
        };

        private void authenticate(LocationScope scope) {
            var authentication = new UsernamePasswordAuthenticationToken(
                    "invoice-test-user", null, List.of(new SimpleGrantedAuthority(InvoicePermissions.VIEW)));
            authentication.setDetails(Map.of(
                    GatewaySecurityConstants.DETAIL_USERNAME,
                    "invoice-test-user",
                    GatewaySecurityConstants.DETAIL_LOCATION_SCOPE,
                    scope));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }

        /** A caller whose invoice:invoice:view is scoped (OTHER dimension) to the given assigned nodes. */
        private LocationScope viewScopedTo(UUID... nodes) {
            return LocationScope.of(
                    Set.of(), Set.of(InvoicePermissions.VIEW), Optional.of(Set.of(nodes)), true, resolver);
        }

        private Invoice scopedInvoice() {
            Invoice invoice = invoice(INVOICE_ID);
            invoice.setLocationId(testLocation);
            return invoice;
        }

        @Test
        @DisplayName("getInvoicePayment maps status, amounts and the refundable balance")
        void getInvoicePayment_mapsRefundableBalance() {
            authenticate(LocationScope.unscoped());
            PaymentIntent intent = capturedPaymentIntent();
            when(paymentIntentRepository.findById(PAYMENT_INTENT_ID)).thenReturn(Optional.of(intent));
            when(refundRecordRepository.findByPaymentIntent_Id(PAYMENT_INTENT_ID))
                    .thenReturn(List.of(
                            refundRecord(BigDecimal.valueOf(50), RefundStatus.COMPLETED),
                            refundRecord(BigDecimal.valueOf(999), RefundStatus.FAILED)));

            PaymentIntentResponse response = paymentService.getInvoicePayment(INVOICE_ID, PAYMENT_INTENT_ID);

            assertThat(response.getPaymentId()).isEqualTo(PAYMENT_INTENT_ID);
            assertThat(response.getInvoiceId()).isEqualTo(INVOICE_ID);
            assertThat(response.getStatus()).isEqualTo(PaymentIntentStatus.CAPTURED);
            assertThat(response.getPaymentFlow()).isEqualTo(PaymentFlow.SALE_CAPTURE);
            assertThat(response.getCapturedAmount()).isEqualByComparingTo(AMOUNT_BELOW_LIMIT);
            assertThat(response.getRefundedAmount()).isEqualByComparingTo(BigDecimal.valueOf(50));
            assertThat(response.getRefundableAmount())
                    .isEqualByComparingTo(AMOUNT_BELOW_LIMIT.subtract(BigDecimal.valueOf(50)));
        }

        @Test
        @DisplayName("getInvoicePayment: an AUTHORIZED (not yet captured) intent has no refundable balance")
        void getInvoicePayment_notCaptured_refundableIsNull() {
            authenticate(LocationScope.unscoped());
            PaymentIntent intent = authorizedPaymentIntent(AMOUNT_BELOW_LIMIT);
            when(paymentIntentRepository.findById(PAYMENT_INTENT_ID)).thenReturn(Optional.of(intent));
            when(refundRecordRepository.findByPaymentIntent_Id(PAYMENT_INTENT_ID))
                    .thenReturn(List.of());

            PaymentIntentResponse response = paymentService.getInvoicePayment(INVOICE_ID, PAYMENT_INTENT_ID);

            assertThat(response.getRefundableAmount()).isNull();
        }

        @Test
        @DisplayName("getInvoicePayment: a payment intent under another invoice 404s like a missing one")
        void getInvoicePayment_wrongInvoice_throws404() {
            authenticate(LocationScope.unscoped());
            PaymentIntent intent = capturedPaymentIntent(); // anchored to INVOICE_ID
            when(paymentIntentRepository.findById(PAYMENT_INTENT_ID)).thenReturn(Optional.of(intent));

            assertThatThrownBy(() -> paymentService.getInvoicePayment(OTHER_INVOICE_ID, PAYMENT_INTENT_ID))
                    .isInstanceOf(PaymentIntentNotFoundException.class);
        }

        @Test
        @DisplayName("getInvoicePayment: a missing payment intent 404s")
        void getInvoicePayment_missing_throws404() {
            authenticate(LocationScope.unscoped());
            when(paymentIntentRepository.findById(PAYMENT_INTENT_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> paymentService.getInvoicePayment(INVOICE_ID, PAYMENT_INTENT_ID))
                    .isInstanceOf(PaymentIntentNotFoundException.class);
        }

        @Test
        @DisplayName("getInvoicePayment: invoice location in reach returns detail")
        void getInvoicePayment_inReach_returnsDetail() {
            authenticate(viewScopedTo(regionNode));
            PaymentIntent intent = capturedPaymentIntent();
            intent.setInvoice(scopedInvoice());
            when(paymentIntentRepository.findById(PAYMENT_INTENT_ID)).thenReturn(Optional.of(intent));
            when(refundRecordRepository.findByPaymentIntent_Id(PAYMENT_INTENT_ID))
                    .thenReturn(List.of());

            assertThat(paymentService
                            .getInvoicePayment(INVOICE_ID, PAYMENT_INTENT_ID)
                            .getPaymentId())
                    .isEqualTo(PAYMENT_INTENT_ID);
        }

        @Test
        @DisplayName("getInvoicePayment: invoice location out of reach denies")
        void getInvoicePayment_outOfReach_denies() {
            authenticate(viewScopedTo(otherShop));
            PaymentIntent intent = capturedPaymentIntent();
            intent.setInvoice(scopedInvoice());
            when(paymentIntentRepository.findById(PAYMENT_INTENT_ID)).thenReturn(Optional.of(intent));

            assertThatThrownBy(() -> paymentService.getInvoicePayment(INVOICE_ID, PAYMENT_INTENT_ID))
                    .isInstanceOf(LocationScopeDeniedException.class);
        }

        @Test
        @DisplayName("listInvoicePayments returns every payment intent mapped, pre-rollout caller unaffected")
        void listInvoicePayments_returnsMappedIntents() {
            authenticate(LocationScope.unscoped());
            when(invoiceRepository.findById(INVOICE_ID)).thenReturn(Optional.of(invoice(INVOICE_ID)));
            PaymentIntent intent = capturedPaymentIntent();
            when(paymentIntentRepository.findByInvoice_Id(INVOICE_ID)).thenReturn(List.of(intent));
            when(refundRecordRepository.findByInvoice_Id(INVOICE_ID)).thenReturn(List.of());

            List<PaymentIntentResponse> results = paymentService.listInvoicePayments(INVOICE_ID);

            assertThat(results).hasSize(1);
            assertThat(results.get(0).getPaymentId()).isEqualTo(PAYMENT_INTENT_ID);
        }

        @Test
        @DisplayName(
                "listInvoicePayments batches the refund lookup: one findByInvoice_Id call, never findByPaymentIntent_Id")
        void listInvoicePayments_batchesRefundLookup() {
            authenticate(LocationScope.unscoped());
            when(invoiceRepository.findById(INVOICE_ID)).thenReturn(Optional.of(invoice(INVOICE_ID)));

            PaymentIntent first = capturedPaymentIntent();
            PaymentIntent second = capturedPaymentIntent();
            second.setId(OTHER_PAYMENT_INTENT_ID);
            when(paymentIntentRepository.findByInvoice_Id(INVOICE_ID)).thenReturn(List.of(first, second));
            when(refundRecordRepository.findByInvoice_Id(INVOICE_ID))
                    .thenReturn(List.of(
                            refundRecordFor(first, BigDecimal.valueOf(50), RefundStatus.COMPLETED),
                            refundRecordFor(second, BigDecimal.valueOf(30), RefundStatus.COMPLETED),
                            refundRecordFor(second, BigDecimal.valueOf(999), RefundStatus.FAILED)));

            List<PaymentIntentResponse> results = paymentService.listInvoicePayments(INVOICE_ID);

            assertThat(results).hasSize(2);
            Map<UUID, BigDecimal> refundedByPaymentId = results.stream()
                    .collect(java.util.stream.Collectors.toMap(
                            PaymentIntentResponse::getPaymentId, PaymentIntentResponse::getRefundedAmount));
            assertThat(refundedByPaymentId.get(PAYMENT_INTENT_ID)).isEqualByComparingTo(BigDecimal.valueOf(50));
            assertThat(refundedByPaymentId.get(OTHER_PAYMENT_INTENT_ID)).isEqualByComparingTo(BigDecimal.valueOf(30));

            verify(refundRecordRepository).findByInvoice_Id(INVOICE_ID);
            verify(refundRecordRepository, never()).findByPaymentIntent_Id(any());
        }

        @Test
        @DisplayName("listInvoicePayments: a missing invoice 404s")
        void listInvoicePayments_missingInvoice_throws404() {
            authenticate(LocationScope.unscoped());
            when(invoiceRepository.findById(INVOICE_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> paymentService.listInvoicePayments(INVOICE_ID))
                    .isInstanceOf(InvoiceNotFoundException.class);
        }

        @Test
        @DisplayName("listInvoicePayments: invoice location out of reach denies")
        void listInvoicePayments_outOfReach_denies() {
            authenticate(viewScopedTo(otherShop));
            when(invoiceRepository.findById(INVOICE_ID)).thenReturn(Optional.of(scopedInvoice()));

            assertThatThrownBy(() -> paymentService.listInvoicePayments(INVOICE_ID))
                    .isInstanceOf(LocationScopeDeniedException.class);
        }

        private RefundRecord refundRecord(BigDecimal amount, RefundStatus status) {
            RefundRecord record = new RefundRecord();
            record.setAmount(amount);
            record.setStatus(status);
            return record;
        }

        /** A refund anchored to the given payment intent, for the batched-lookup grouping test. */
        private RefundRecord refundRecordFor(PaymentIntent paymentIntent, BigDecimal amount, RefundStatus status) {
            RefundRecord record = refundRecord(amount, status);
            record.setPaymentIntent(paymentIntent);
            return record;
        }
    }

    // -------------------------------------------------------------------------
    // initiatePayment / capturePayment location scope (#2393, ADR-0061)
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("initiatePayment / capturePayment are gated on the invoice's location (#2393, ADR-0061)")
    class PaymentMutationScopeTests {

        /** The node a scoped caller is assigned: a region above the test invoice's location. */
        private final UUID regionNode = UUID.fromString("019200aa-0000-7000-8000-00000000a000");

        private final UUID otherShop = UUID.fromString("019200aa-0000-7000-8000-00000000000b");

        private final UUID testLocation = UUID.fromString("01960003-0000-7000-8000-000000000001");

        /** Replica stand-in: testLocation sits under regionNode on the OTHER dimension; otherShop does not. */
        private final LocationAncestorResolver resolver = id -> {
            if (id.equals(testLocation)) {
                return new AncestorSets(Set.of(id), Set.of(id, regionNode));
            }
            if (id.equals(otherShop)) {
                return new AncestorSets(Set.of(id), Set.of(id));
            }
            return AncestorSets.EMPTY;
        };

        /** Authenticates a caller holding {@code held}, of which {@code scoped} are bound to {@code node}. */
        private void authenticate(Set<String> held, Set<String> scoped, UUID node) {
            var authentication = new UsernamePasswordAuthenticationToken(
                    "invoice-test-user",
                    null,
                    held.stream().map(SimpleGrantedAuthority::new).toList());
            authentication.setDetails(Map.of(
                    GatewaySecurityConstants.DETAIL_USERNAME,
                    "invoice-test-user",
                    GatewaySecurityConstants.DETAIL_LOCATION_SCOPE,
                    LocationScope.of(Set.of(), scoped, Optional.of(Set.of(node)), true, resolver)));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }

        private Invoice scopedInvoice() {
            Invoice invoice = invoice(INVOICE_ID);
            invoice.setLocationId(testLocation);
            return invoice;
        }

        private PaymentIntent scopedAuthorizedIntent() {
            PaymentIntent intent = authorizedPaymentIntent(AMOUNT_BELOW_LIMIT);
            intent.setInvoice(scopedInvoice());
            return intent;
        }

        @Test
        @DisplayName("initiatePayment: invoice location in reach takes the payment")
        void initiatePayment_inReach_captures() {
            Set<String> process = Set.of(InvoicePermissions.PAYMENT_PROCESS);
            authenticate(process, process, regionNode);
            var request = buildRequest(PaymentFlow.SALE_CAPTURE, AMOUNT_BELOW_LIMIT, IDEMPOTENCY_KEY);
            when(invoiceRepository.findById(INVOICE_ID)).thenReturn(Optional.of(scopedInvoice()));
            when(paymentIntentRepository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
            when(gatewayPort.saleCapture(any())).thenReturn(capturedResult(AMOUNT_BELOW_LIMIT));
            when(paymentIntentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            assertThat(paymentService.initiatePayment(INVOICE_ID, request).getStatus())
                    .isEqualTo(PaymentIntentStatus.CAPTURED);
        }

        @Test
        @DisplayName("initiatePayment: invoice location out of reach denies before anything is saved or charged")
        void initiatePayment_outOfReach_denies() {
            Set<String> process = Set.of(InvoicePermissions.PAYMENT_PROCESS);
            authenticate(process, process, otherShop);
            var request = buildRequest(PaymentFlow.SALE_CAPTURE, AMOUNT_BELOW_LIMIT, IDEMPOTENCY_KEY);
            when(invoiceRepository.findById(INVOICE_ID)).thenReturn(Optional.of(scopedInvoice()));
            when(paymentIntentRepository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> paymentService.initiatePayment(INVOICE_ID, request))
                    .isInstanceOf(LocationScopeDeniedException.class);

            verify(paymentIntentRepository, never()).save(any(PaymentIntent.class));
            verifyNoInteractions(gatewayPort);
        }

        @Test
        @DisplayName("initiatePayment: a missing invoice 404s before any scope decision")
        void initiatePayment_missingInvoice_throws404() {
            Set<String> process = Set.of(InvoicePermissions.PAYMENT_PROCESS);
            authenticate(process, process, otherShop);
            var request = buildRequest(PaymentFlow.SALE_CAPTURE, AMOUNT_BELOW_LIMIT, IDEMPOTENCY_KEY);
            when(invoiceRepository.findById(INVOICE_ID)).thenReturn(Optional.empty());
            when(paymentIntentRepository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> paymentService.initiatePayment(INVOICE_ID, request))
                    .isInstanceOf(InvoiceNotFoundException.class);
        }

        @Test
        @DisplayName(
                "initiatePayment: an idempotent replay is scope-checked too, so it cannot read back another location's intent")
        void initiatePayment_replayOutOfReach_denies() {
            Set<String> process = Set.of(InvoicePermissions.PAYMENT_PROCESS);
            authenticate(process, process, otherShop);
            var request = buildRequest(PaymentFlow.SALE_CAPTURE, AMOUNT_BELOW_LIMIT, IDEMPOTENCY_KEY);
            PaymentIntent existing = capturedPaymentIntent();
            existing.setInvoice(scopedInvoice());
            when(paymentIntentRepository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.of(existing));

            assertThatThrownBy(() -> paymentService.initiatePayment(INVOICE_ID, request))
                    .isInstanceOf(LocationScopeDeniedException.class);
        }

        @Test
        @DisplayName(
                "initiatePayment: a limit override scoped away from the invoice does not lift the 500.00 threshold")
        void initiatePayment_overThreshold_overrideScopedElsewhere_denies() {
            // process is held globally; only the override is bound to another shop
            authenticate(
                    Set.of(InvoicePermissions.PAYMENT_PROCESS, InvoicePermissions.PAYMENT_LIMIT_OVERRIDE),
                    Set.of(InvoicePermissions.PAYMENT_LIMIT_OVERRIDE),
                    otherShop);
            var request = buildRequest(PaymentFlow.SALE_CAPTURE, AMOUNT_ABOVE_LIMIT, IDEMPOTENCY_KEY);
            when(invoiceRepository.findById(INVOICE_ID)).thenReturn(Optional.of(scopedInvoice()));
            when(paymentIntentRepository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> paymentService.initiatePayment(INVOICE_ID, request))
                    .isInstanceOf(LocationScopeDeniedException.class);

            verify(paymentIntentRepository, never()).save(any(PaymentIntent.class));
            verifyNoInteractions(gatewayPort);
        }

        @Test
        @DisplayName("initiatePayment: a flow-select grant scoped away from the invoice does not allow AUTH_ONLY")
        void initiatePayment_authOnly_flowSelectScopedElsewhere_denies() {
            authenticate(
                    Set.of(InvoicePermissions.PAYMENT_PROCESS, InvoicePermissions.PAYMENT_FLOW_SELECT),
                    Set.of(InvoicePermissions.PAYMENT_FLOW_SELECT),
                    otherShop);
            var request = buildRequest(PaymentFlow.AUTH_ONLY, AMOUNT_BELOW_LIMIT, IDEMPOTENCY_KEY);
            when(invoiceRepository.findById(INVOICE_ID)).thenReturn(Optional.of(scopedInvoice()));
            when(paymentIntentRepository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> paymentService.initiatePayment(INVOICE_ID, request))
                    .isInstanceOf(LocationScopeDeniedException.class);

            verify(paymentIntentRepository, never()).save(any(PaymentIntent.class));
            verifyNoInteractions(gatewayPort);
        }

        @Test
        @DisplayName("capturePayment: invoice location in reach captures the hold")
        void capturePayment_inReach_captures() {
            Set<String> capture = Set.of(InvoicePermissions.PAYMENT_CAPTURE);
            authenticate(capture, capture, regionNode);
            when(paymentIntentRepository.findById(PAYMENT_INTENT_ID)).thenReturn(Optional.of(scopedAuthorizedIntent()));
            when(gatewayPort.capture(any())).thenReturn(capturedResult(AMOUNT_BELOW_LIMIT));
            when(paymentIntentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            assertThat(paymentService
                            .capturePayment(INVOICE_ID, PAYMENT_INTENT_ID, AMOUNT_BELOW_LIMIT, CAPTURE_IDEMPOTENCY_KEY)
                            .getStatus())
                    .isEqualTo(PaymentIntentStatus.CAPTURED);
        }

        @Test
        @DisplayName("capturePayment: invoice location out of reach denies and leaves the hold AUTHORIZED")
        void capturePayment_outOfReach_denies() {
            Set<String> capture = Set.of(InvoicePermissions.PAYMENT_CAPTURE);
            authenticate(capture, capture, otherShop);
            PaymentIntent intent = scopedAuthorizedIntent();
            when(paymentIntentRepository.findById(PAYMENT_INTENT_ID)).thenReturn(Optional.of(intent));

            assertThatThrownBy(() -> paymentService.capturePayment(
                            INVOICE_ID, PAYMENT_INTENT_ID, AMOUNT_BELOW_LIMIT, CAPTURE_IDEMPOTENCY_KEY))
                    .isInstanceOf(LocationScopeDeniedException.class);

            assertThat(intent.getStatus()).isEqualTo(PaymentIntentStatus.AUTHORIZED);
            verify(paymentIntentRepository, never()).save(any(PaymentIntent.class));
            verifyNoInteractions(gatewayPort);
        }

        @Test
        @DisplayName("capturePayment: an intent under another invoice 404s before any scope decision")
        void capturePayment_wrongInvoice_throws404() {
            Set<String> capture = Set.of(InvoicePermissions.PAYMENT_CAPTURE);
            authenticate(capture, capture, otherShop);
            when(paymentIntentRepository.findById(PAYMENT_INTENT_ID)).thenReturn(Optional.of(scopedAuthorizedIntent()));

            assertThatThrownBy(() -> paymentService.capturePayment(
                            OTHER_INVOICE_ID, PAYMENT_INTENT_ID, AMOUNT_BELOW_LIMIT, CAPTURE_IDEMPOTENCY_KEY))
                    .isInstanceOf(PaymentIntentNotFoundException.class);
        }
    }
}
