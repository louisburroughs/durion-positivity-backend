package com.positivity.invoice.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.invoice.internal.dto.InitiatePaymentRequest;
import com.positivity.invoice.internal.dto.InitiatePaymentResponse;
import com.positivity.invoice.internal.dto.RecordCashPaymentRequest;
import com.positivity.invoice.internal.enums.PaymentFlow;
import com.positivity.invoice.internal.enums.PaymentIntentStatus;
import com.positivity.invoice.internal.enums.PaymentMethodType;
import com.positivity.invoice.internal.exception.CashRoundingInvalidException;
import com.positivity.invoice.internal.exception.InvalidPaymentStateException;
import com.positivity.invoice.internal.exception.PaymentDeclinedException;
import com.positivity.invoice.internal.exception.PaymentIdempotencyConflictException;
import com.positivity.invoice.internal.exception.PaymentIntentNotFoundException;
import com.positivity.invoice.internal.service.PaymentService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Controller-layer unit tests for {@link PaymentController} covering Story #9:
 * Initiate Card Authorization and Capture.
 *
 * <p>
 * Uses standalone MockMvc (no Spring context) with Mockito for the service
 * layer.
 * Security context and permission enforcement are validated at the service
 * layer
 * in {@link PaymentServiceImplTest}; this layer asserts HTTP response codes and
 * JSON response shape per ADR-0017.
 *
 * <p>
 * ADR-0017 response codes:
 * <ul>
 * <li>201 Created — payment initiated successfully</li>
 * <li>200 OK — explicit capture completed</li>
 * <li>400 Bad Request — missing required fields</li>
 * </ul>
 *
 * Issue: #9
 */
@ExtendWith(MockitoExtension.class)
class PaymentControllerTest {

    private static final UUID INVOICE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID PAYMENT_INTENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Mock
    private PaymentService paymentService;

    @InjectMocks
    private PaymentController paymentController;

    @Spy
    private Clock clock = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(paymentController)
                .setControllerAdvice(new PaymentExceptionHandler(clock))
                .build();
        objectMapper = new ObjectMapper();
    }

    // -------------------------------------------------------------------------
    // POST /v1/invoices/{invoiceId}/payments — initiate payment
    // -------------------------------------------------------------------------

    /**
     * AC1/AC2: Successful payment initiation must return HTTP 201 Created
     * with the PaymentIntent representation in the response body (ADR-0017).
     */
    @Test
    void initiatePayment_returns201_whenSuccessful() throws Exception {
        var request = buildInitiateRequest(PaymentFlow.SALE_CAPTURE, BigDecimal.valueOf(200_00, 2));
        var response = buildCapturedResponse();
        when(paymentService.initiatePayment(eq(INVOICE_ID), any())).thenReturn(response);

        mockMvc.perform(post("/v1/invoices/{invoiceId}/payments", INVOICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CAPTURED"))
                .andExpect(jsonPath("$.capturedAmount").exists());
    }

    /**
     * Missing required fields (no idempotencyKey, no paymentToken) must return
     * HTTP 400 Bad Request (ADR-0017) without calling the service layer.
     */
    @Test
    void initiatePayment_returns400_whenInvalidRequest() throws Exception {
        var emptyBody = "{}";

        mockMvc.perform(post("/v1/invoices/{invoiceId}/payments", INVOICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(emptyBody))
                .andExpect(status().isBadRequest());
    }

    @Test
    void initiatePayment_returns404_whenPaymentIntentNotFound() throws Exception {
        when(paymentService.initiatePayment(any(), any())).thenThrow(new PaymentIntentNotFoundException("not found"));

        mockMvc.perform(post("/v1/invoices/" + INVOICE_ID + "/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"paymentFlow":"SALE_CAPTURE","amount":100.00,"idempotencyKey":"key-001","paymentToken":"tok_test"}
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    void initiatePayment_returns409_whenInvalidPaymentState() throws Exception {
        when(paymentService.initiatePayment(any(), any())).thenThrow(new InvalidPaymentStateException("invalid state"));

        mockMvc.perform(post("/v1/invoices/" + INVOICE_ID + "/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"paymentFlow":"SALE_CAPTURE","amount":100.00,"idempotencyKey":"key-001","paymentToken":"tok_test"}
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void initiatePayment_returns409_whenIdempotencyConflict() throws Exception {
        when(paymentService.initiatePayment(any(), any()))
                .thenThrow(new PaymentIdempotencyConflictException("idempotency conflict"));

        mockMvc.perform(post("/v1/invoices/" + INVOICE_ID + "/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"paymentFlow":"SALE_CAPTURE","amount":100.00,"idempotencyKey":"key-001","paymentToken":"tok_test"}
                                """))
                .andExpect(status().isConflict());
    }

    // -------------------------------------------------------------------------
    // POST /v1/invoices/{invoiceId}/payments/cash — record cash tender (#2662)
    // -------------------------------------------------------------------------

    private static final String CASH_BODY = """
            {"idempotencyKey":"cash-001","amount":149.98,"settledAmount":150.00,
             "cashRoundingAmount":0.02,"cashIncrement":0.05,"currencyCode":"CAD"}
            """;

    @Test
    void recordCashPayment_returns201_withTheRoundingFields() throws Exception {
        InitiatePaymentResponse response = new InitiatePaymentResponse();
        response.setPaymentIntentId(PAYMENT_INTENT_ID);
        response.setStatus(PaymentIntentStatus.CAPTURED);
        response.setMethodType(PaymentMethodType.CASH);
        response.setCapturedAmount(new BigDecimal("149.98"));
        response.setSettledAmount(new BigDecimal("150.00"));
        response.setCashRoundingAmount(new BigDecimal("0.02"));
        response.setCashIncrement(new BigDecimal("0.05"));
        when(paymentService.recordCashPayment(eq(INVOICE_ID), any(RecordCashPaymentRequest.class)))
                .thenReturn(response);

        mockMvc.perform(post("/v1/invoices/" + INVOICE_ID + "/payments/cash")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CASH_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.methodType").value("CASH"))
                .andExpect(jsonPath("$.settledAmount").value(150.00))
                .andExpect(jsonPath("$.cashRoundingAmount").value(0.02))
                .andExpect(jsonPath("$.cashIncrement").value(0.05));
    }

    @Test
    void recordCashPayment_returns400_whenTheTripleIsMissing() throws Exception {
        mockMvc.perform(post("/v1/invoices/" + INVOICE_ID + "/payments/cash")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"idempotencyKey":"cash-001","amount":149.98,"currencyCode":"CAD"}
                                """))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(paymentService);
    }

    @Test
    void recordCashPayment_returns422_withCashRoundingInvalid() throws Exception {
        when(paymentService.recordCashPayment(any(), any()))
                .thenThrow(new CashRoundingInvalidException("settledAmount 149.95 is not amount 149.98 rounded"));

        mockMvc.perform(post("/v1/invoices/" + INVOICE_ID + "/payments/cash")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CASH_BODY))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("CASH_ROUNDING_INVALID"));
    }

    @Test
    void recordCashPayment_returns409_whenIdempotencyConflict() throws Exception {
        when(paymentService.recordCashPayment(any(), any()))
                .thenThrow(new PaymentIdempotencyConflictException("idempotency conflict"));

        mockMvc.perform(post("/v1/invoices/" + INVOICE_ID + "/payments/cash")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CASH_BODY))
                .andExpect(status().isConflict());
    }

    @Test
    void initiatePayment_returns422_whenPaymentDeclined() throws Exception {
        when(paymentService.initiatePayment(any(), any())).thenThrow(new PaymentDeclinedException("declined"));

        mockMvc.perform(post("/v1/invoices/" + INVOICE_ID + "/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"paymentFlow":"SALE_CAPTURE","amount":100.00,"idempotencyKey":"key-001","paymentToken":"tok_test"}
                                """))
                .andExpect(status().is(422));
    }

    // -------------------------------------------------------------------------
    // POST /v1/invoices/{invoiceId}/payments/{paymentId}/capture — explicit capture
    // -------------------------------------------------------------------------

    /**
     * AC3: Successful explicit capture of an authorized hold must return
     * HTTP 200 OK with the updated PaymentIntent (ADR-0017).
     */
    @Test
    void capturePayment_returns200_whenSuccessful() throws Exception {
        var response = buildCapturedResponse();
        when(paymentService.capturePayment(eq(INVOICE_ID), eq(PAYMENT_INTENT_ID), any(), anyString()))
                .thenReturn(response);

        mockMvc.perform(post("/v1/invoices/{invoiceId}/payments/{paymentId}/capture", INVOICE_ID, PAYMENT_INTENT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": 200.00, \"captureIdempotencyKey\": \"capture-key-001\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CAPTURED"));
    }

    @Test
    void capturePayment_returns400_whenInvalidCaptureBody() throws Exception {
        mockMvc.perform(post("/v1/invoices/" + INVOICE_ID + "/payments/" + PAYMENT_INTENT_ID + "/capture")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    // -------------------------------------------------------------------------
    // Fixture helpers
    // -------------------------------------------------------------------------

    /**
     * Builds a minimal valid {@link InitiatePaymentRequest}.
     *
     * @param flow   the payment flow
     * @param amount the payment amount
     * @return populated request DTO
     */
    private InitiatePaymentRequest buildInitiateRequest(PaymentFlow flow, BigDecimal amount) {
        var req = new InitiatePaymentRequest();
        req.setPaymentFlow(flow);
        req.setAmount(amount);
        req.setIdempotencyKey("idem-key-001");
        req.setPaymentToken("tok_test_001"); // tokenized — no PAN stored (AC8)
        return req;
    }

    private InitiatePaymentResponse buildCapturedResponse() {
        var resp = new InitiatePaymentResponse();
        resp.setPaymentIntentId(PAYMENT_INTENT_ID);
        resp.setStatus(PaymentIntentStatus.CAPTURED);
        resp.setAuthorizedAmount(BigDecimal.valueOf(200_00, 2));
        resp.setCapturedAmount(BigDecimal.valueOf(200_00, 2));
        resp.setVoidedRemainderAmount(BigDecimal.ZERO);
        resp.setGatewayProvider("stripe");
        resp.setGatewayResponse("{\"status\":\"captured\"}");
        return resp;
    }
}
