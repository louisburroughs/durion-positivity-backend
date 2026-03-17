package com.positivity.invoice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
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

import com.positivity.invoice.internal.dto.InitiatePaymentRequest;
import com.positivity.invoice.internal.dto.InitiatePaymentResponse;
import com.positivity.invoice.internal.entity.PaymentIntent;
import com.positivity.invoice.internal.exception.InvalidPaymentStateException;
import com.positivity.invoice.internal.exception.PaymentDeclinedException;
import com.positivity.invoice.internal.exception.PaymentIdempotencyConflictException;
import com.positivity.invoice.internal.exception.PaymentIntentNotFoundException;
import com.positivity.invoice.internal.enums.PaymentFlow;
import com.positivity.invoice.internal.enums.PaymentIntentStatus;
import com.positivity.invoice.internal.payment.GatewayCaptureRequest;
import com.positivity.invoice.internal.payment.GatewayPaymentResult;
import com.positivity.invoice.internal.entity.Invoice;
import com.positivity.invoice.internal.payment.GatewayVoidRequest;
import com.positivity.invoice.internal.payment.PaymentGatewayPort;
import com.positivity.invoice.internal.repository.InvoiceRepository;
import com.positivity.invoice.internal.repository.PaymentIntentRepository;

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
 * requires SELECT_PAYMENT_FLOW</li>
 * <li>AC3 — Explicit manual capture: AUTHORIZED → CAPTURED; partial capture
 * voids remainder</li>
 * <li>AC4 — Idempotent retry: same idempotency key returns same result without
 * re-calling gateway</li>
 * <li>AC5 — Unknown gateway outcome triggers status inquiry before retry</li>
 * <li>AC6 — Authorization over $500 requires OVERRIDE_PAYMENT_LIMIT
 * permission</li>
 * <li>AC7 — AUTH_ONLY requires SELECT_PAYMENT_FLOW permission when explicitly
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
        var grants = List.of(authorities).stream()
                .map(SimpleGrantedAuthority::new)
                .toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("cashier1", null, grants));
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
        withAuthorities("PROCESS_PAYMENT");
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
     * AC2: AUTH_ONLY flow with SELECT_PAYMENT_FLOW permission creates an
     * authorization hold; PaymentIntent is created in PENDING then transitions
     * to AUTHORIZED.
     */
    @Test
    void initiatePayment_authOnly_createsHold() {
        withAuthorities("PROCESS_PAYMENT", "SELECT_PAYMENT_FLOW");
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
        withAuthorities("PROCESS_PAYMENT");
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
        withAuthorities("PROCESS_PAYMENT");
        var request = buildRequest(PaymentFlow.SALE_CAPTURE, AMOUNT_BELOW_LIMIT, IDEMPOTENCY_KEY);
        var existing = capturedPaymentIntent();
        existing.setInvoice(invoice(OTHER_INVOICE_ID));
        when(paymentIntentRepository.findByIdempotencyKey(IDEMPOTENCY_KEY))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> paymentService.initiatePayment(INVOICE_ID, request))
            .isInstanceOf(PaymentIdempotencyConflictException.class)
            .hasMessageContaining("already used with a different payment request");

        verify(gatewayPort, never()).saleCapture(any());
        verify(gatewayPort, never()).authorize(any());
        verify(paymentIntentRepository, never()).save(any(PaymentIntent.class));
    }

    @Test
    void initiatePayment_idempotent_differentAmount_throwsConflict() {
        withAuthorities("PROCESS_PAYMENT");
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
        withAuthorities("PROCESS_PAYMENT");
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
        withAuthorities("PROCESS_PAYMENT", "SELECT_PAYMENT_FLOW");
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
        withAuthorities("PROCESS_PAYMENT");
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
     * Initiating a payment without PROCESS_PAYMENT permission must throw
     * an authorization exception (AC6 baseline permission).
     */
    @Test
    void initiatePayment_requiresProcessPaymentPermission() {
        withAuthorities("SOME_OTHER_PERMISSION");
        var request = buildRequest(PaymentFlow.SALE_CAPTURE, AMOUNT_BELOW_LIMIT, IDEMPOTENCY_KEY);

        assertThatThrownBy(() -> paymentService.initiatePayment(INVOICE_ID, request))
                .isInstanceOf(AccessDeniedException.class);
    }

    /**
     * AC6: Authorization amount over $500 without OVERRIDE_PAYMENT_LIMIT
     * permission must throw an authorization exception.
     */
    @Test
    void initiatePayment_overThreshold_requiresOverridePermission() {
        withAuthorities("PROCESS_PAYMENT");
        var request = buildRequest(PaymentFlow.SALE_CAPTURE, AMOUNT_ABOVE_LIMIT, IDEMPOTENCY_KEY);

        assertThatThrownBy(() -> paymentService.initiatePayment(INVOICE_ID, request))
                .isInstanceOf(AccessDeniedException.class);
    }

    /**
     * AC7: AUTH_ONLY flow explicitly requested by cashier without
     * SELECT_PAYMENT_FLOW permission must throw an authorization exception.
     */
    @Test
    void initiatePayment_authOnly_requiresSelectPaymentFlowPermission() {
        withAuthorities("PROCESS_PAYMENT"); // SELECT_PAYMENT_FLOW intentionally absent
        var request = buildRequest(PaymentFlow.AUTH_ONLY, AMOUNT_BELOW_LIMIT, IDEMPOTENCY_KEY);

        assertThatThrownBy(() -> paymentService.initiatePayment(INVOICE_ID, request))
                .isInstanceOf(AccessDeniedException.class);
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
        withAuthorities("PROCESS_PAYMENT", "MANUAL_CAPTURE");
        var authorizedAmount = BigDecimal.valueOf(300_00, 2);
        var partialAmount = BigDecimal.valueOf(200_00, 2);
        var expectedVoided = authorizedAmount.subtract(partialAmount);
        when(paymentIntentRepository.findById(PAYMENT_INTENT_ID))
                .thenReturn(Optional.of(authorizedPaymentIntent(authorizedAmount)));
        when(gatewayPort.capture(any())).thenReturn(capturedResult(partialAmount));
        when(gatewayPort.voidRemainder(any())).thenReturn(voidedResult());
        when(paymentIntentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        InitiatePaymentResponse response = paymentService.capturePayment(INVOICE_ID, PAYMENT_INTENT_ID, partialAmount,
                CAPTURE_IDEMPOTENCY_KEY);

        assertThat(response.getStatus()).isEqualTo(PaymentIntentStatus.CAPTURED);
        assertThat(response.getCapturedAmount()).isEqualByComparingTo(partialAmount);
        assertThat(response.getVoidedRemainderAmount()).isEqualByComparingTo(expectedVoided);

        ArgumentCaptor<GatewayCaptureRequest> captureRequestCaptor = ArgumentCaptor
                .forClass(GatewayCaptureRequest.class);
        verify(gatewayPort).capture(captureRequestCaptor.capture());
        assertThat(captureRequestCaptor.getValue().gatewayReference()).isEqualTo("gw-auth-ref-001");

        ArgumentCaptor<GatewayVoidRequest> voidRequestCaptor = ArgumentCaptor.forClass(GatewayVoidRequest.class);
        verify(gatewayPort).voidRemainder(voidRequestCaptor.capture());
        assertThat(voidRequestCaptor.getValue().gatewayReference()).isEqualTo("gw-auth-ref-001");
    }

    /**
     * AC3: Explicit capture without MANUAL_CAPTURE permission must throw
     * an authorization exception.
     */
    @Test
    void capturePayment_requiresManualCapturePermission() {
        withAuthorities("PROCESS_PAYMENT"); // MANUAL_CAPTURE intentionally absent
        var captureAmount = BigDecimal.valueOf(200);

        assertThatThrownBy(
                () -> paymentService.capturePayment(INVOICE_ID, PAYMENT_INTENT_ID, captureAmount,
                        CAPTURE_IDEMPOTENCY_KEY))
                .isInstanceOf(AccessDeniedException.class);
    }

    /**
     * AC3: Explicit manual capture with MANUAL_CAPTURE permission must
     * transition PaymentIntent AUTHORIZED → CAPTURED; full amount captured.
     */
    @Test
    void capturePayment_transitionsAuthorizedToCaptured() {
        withAuthorities("PROCESS_PAYMENT", "MANUAL_CAPTURE");
        var authorizedAmount = BigDecimal.valueOf(300_00, 2);
        when(paymentIntentRepository.findById(PAYMENT_INTENT_ID))
                .thenReturn(Optional.of(authorizedPaymentIntent(authorizedAmount)));
        when(gatewayPort.capture(any())).thenReturn(capturedResult(authorizedAmount));
        when(paymentIntentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        InitiatePaymentResponse response = paymentService.capturePayment(INVOICE_ID, PAYMENT_INTENT_ID,
                authorizedAmount, CAPTURE_IDEMPOTENCY_KEY);

        assertThat(response.getStatus()).isEqualTo(PaymentIntentStatus.CAPTURED);
        assertThat(response.getCapturedAmount()).isEqualByComparingTo(authorizedAmount);
    }

    @Test
    void capturePayment_unknownOutcome_performsStatusInquiry() {
        withAuthorities("PROCESS_PAYMENT", "MANUAL_CAPTURE");
        var captureAmount = BigDecimal.valueOf(200_00, 2);
        var unknownResult = unknownOutcomeResult();
        var successResult = capturedResult(captureAmount);
        when(paymentIntentRepository.findById(PAYMENT_INTENT_ID))
                .thenReturn(Optional.of(authorizedPaymentIntent(BigDecimal.valueOf(300_00, 2))));
        when(gatewayPort.capture(any())).thenReturn(unknownResult);
        when(gatewayPort.inquireStatus("gw-auth-ref-001")).thenReturn(successResult);
        when(paymentIntentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        InitiatePaymentResponse response = paymentService.capturePayment(
                INVOICE_ID,
                PAYMENT_INTENT_ID,
                captureAmount,
                CAPTURE_IDEMPOTENCY_KEY);

        verify(gatewayPort, times(1)).inquireStatus("gw-auth-ref-001");
        assertThat(response.getStatus()).isEqualTo(PaymentIntentStatus.CAPTURED);
    }

    @Test
    void capturePayment_throws_whenPaymentIntentNotInAuthorizedState() {
        withAuthorities("PROCESS_PAYMENT", "MANUAL_CAPTURE");
        var captureAmount = BigDecimal.valueOf(200, 2);
        var paymentIntent = new PaymentIntent();
        paymentIntent.setId(PAYMENT_INTENT_ID);
        paymentIntent.setInvoice(invoice(INVOICE_ID));
        paymentIntent.setStatus(PaymentIntentStatus.CAPTURED);
        when(paymentIntentRepository.findById(PAYMENT_INTENT_ID)).thenReturn(Optional.of(paymentIntent));

        assertThatThrownBy(
                () -> paymentService.capturePayment(INVOICE_ID, PAYMENT_INTENT_ID, captureAmount,
                        CAPTURE_IDEMPOTENCY_KEY))
                .isInstanceOf(InvalidPaymentStateException.class)
                .hasMessageContaining("AUTHORIZED status");
    }

    @Test
    void capturePayment_throws_whenPaymentIntentNotFound() {
        withAuthorities("PROCESS_PAYMENT", "MANUAL_CAPTURE");
        var captureAmount = BigDecimal.valueOf(200, 2);
        when(paymentIntentRepository.findById(PAYMENT_INTENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(
                () -> paymentService.capturePayment(INVOICE_ID, PAYMENT_INTENT_ID, captureAmount,
                        CAPTURE_IDEMPOTENCY_KEY))
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
        withAuthorities("PROCESS_PAYMENT");
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

    private Invoice invoice(UUID id) {
        Invoice invoice = new Invoice();
        invoice.setId(id);
        return invoice;
    }
}
