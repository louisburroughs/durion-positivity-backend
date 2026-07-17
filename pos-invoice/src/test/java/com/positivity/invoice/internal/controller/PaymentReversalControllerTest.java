package com.positivity.invoice.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.invoice.internal.enums.RefundReason;
import com.positivity.invoice.internal.enums.RefundStatus;
import com.positivity.invoice.internal.exception.InvalidPaymentStateException;
import com.positivity.invoice.internal.exception.InvoiceNotFoundException;
import com.positivity.invoice.internal.exception.PaymentWindowExpiredException;
import com.positivity.invoice.service.PaymentReversalService;
import com.positivity.invoice.service.RefundPaymentResult;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
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
 * Controller-layer unit tests for {@link PaymentReversalController} covering
 * Story #8: Void Authorization / Refund Captured Payment.
 *
 * <p>
 * Uses standalone MockMvc (no Spring context) with Mockito for the service
 * layer. Security permission enforcement is validated at the service layer in
 * {@link PaymentReversalServiceImplTest}; this layer asserts HTTP response
 * codes per ADR-0017.
 *
 * <p>
 * ADR-0017 response codes:
 * <ul>
 * <li>200 OK — void completed</li>
 * <li>201 Created — refund record created</li>
 * <li>409 Conflict — invalid payment state</li>
 * <li>422 Unprocessable Entity — window expired or insufficient refundable
 * amount</li>
 * </ul>
 *
 * Issue: #8
 */
@ExtendWith(MockitoExtension.class)
class PaymentReversalControllerTest {

    private static final UUID INVOICE_ID = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final UUID PAYMENT_INTENT_ID = UUID.fromString("00000000-0000-7000-8000-000000000002");

    @Mock
    private PaymentReversalService paymentReversalService;

    @InjectMocks
    private PaymentReversalController paymentReversalController;

    @Spy
    private Clock clock = Clock.fixed(Instant.parse("2026-01-15T14:30:22Z"), ZoneOffset.UTC);

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(paymentReversalController)
                .setControllerAdvice(new PaymentReversalExceptionHandler(clock))
                .build();
    }

    // -------------------------------------------------------------------------
    // POST /v1/invoices/{invoiceId}/payments/{paymentId}/void — void payment
    // -------------------------------------------------------------------------

    /**
     * AC1: Successful void of an AUTHORIZED payment must return HTTP 200 OK
     * (operation completed; no resource created — ADR-0017).
     */
    @Test
    void voidPayment_returns200() throws Exception {
        // paymentReversalService.voidPayment is void; no stub needed — no-op default

        mockMvc.perform(post("/v1/invoices/{invoiceId}/payments/{paymentId}/void", INVOICE_ID, PAYMENT_INTENT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"CUSTOMER_REQUEST\"}"))
                .andExpect(status().isOk());
    }

    /**
     * AC6: voidPayment when service throws InvalidPaymentStateException must
     * return HTTP 409 Conflict (ADR-0017).
     */
    @Test
    void voidPayment_invalidState_returns409() throws Exception {
        doThrow(new InvalidPaymentStateException("payment not in AUTHORIZED state"))
                .when(paymentReversalService)
                .voidPayment(any(), any(), any(), any());

        mockMvc.perform(post("/v1/invoices/{invoiceId}/payments/{paymentId}/void", INVOICE_ID, PAYMENT_INTENT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"ENTRY_ERROR\"}"))
                .andExpect(status().isConflict());
    }

    // -------------------------------------------------------------------------
    // POST /v1/invoices/{invoiceId}/payments/{paymentId}/refunds — refund payment
    // -------------------------------------------------------------------------

    /**
     * AC2: Successful refund for a CAPTURED payment must return HTTP 201 Created
     * with the RefundRecord in the response body (ADR-0017).
     */
    @Test
    void refundPayment_returns201() throws Exception {
        RefundPaymentResult saved = new RefundPaymentResult();
        saved.setRefundId(UUID.fromString("00000000-0000-7000-8000-000000000005"));
        saved.setInvoiceId(INVOICE_ID);
        saved.setPaymentIntentId(PAYMENT_INTENT_ID);
        saved.setAmount(BigDecimal.valueOf(100.00));
        saved.setReason(RefundReason.CUSTOMER_RETURN);
        saved.setStatus(RefundStatus.COMPLETED);
        saved.setExternalReference("WC-2026-000042");
        saved.setCompletedAt(Instant.parse("2026-01-15T14:30:22Z"));

        when(paymentReversalService.refundPayment(any(), any(), any(), any(), any(), any()))
                .thenReturn(saved);

        mockMvc.perform(post("/v1/invoices/{invoiceId}/payments/{paymentId}/refunds", INVOICE_ID, PAYMENT_INTENT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":100.00,\"reason\":\"CUSTOMER_RETURN\","
                                + "\"externalReference\":\"WC-2026-000042\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.refundId").value("00000000-0000-7000-8000-000000000005"))
                .andExpect(jsonPath("$.externalReference").value("WC-2026-000042"));
    }

    /**
     * AC4: refundPayment when service throws PaymentWindowExpiredException must
     * return HTTP 422 Unprocessable Entity (ADR-0017).
     */
    @Test
    void refundPayment_windowExpired_returns422() throws Exception {
        doThrow(new PaymentWindowExpiredException("refund window of 180 days has expired"))
                .when(paymentReversalService)
                .refundPayment(any(), any(), any(), any(), any(), any());

        mockMvc.perform(post("/v1/invoices/{invoiceId}/payments/{paymentId}/refunds", INVOICE_ID, PAYMENT_INTENT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":50.00,\"reason\":\"GOODWILL\"}"))
                .andExpect(status().is(422));
    }

    // -------------------------------------------------------------------------
    // GET /v1/invoices/{invoiceId}/refunds — refund list for warranty
    // reconciliation (#922). Authority (invoice:manage) is enforced by
    // @PreAuthorize, outside standalone-MockMvc scope.
    // -------------------------------------------------------------------------

    /**
     * #922: the endpoint returns a JSON array with the reconciliation contract's exact field
     * names — id, paymentIntentId, amount, status, reason, externalReference,
     * gatewayReference, requestedAt, completedAt — covering payment-anchored and standalone
     * records alike.
     */
    @Test
    void listRefunds_returns200WithContractFields() throws Exception {
        RefundPaymentResult paymentAnchored = new RefundPaymentResult();
        paymentAnchored.setRefundId(UUID.fromString("00000000-0000-7000-8000-000000000051"));
        paymentAnchored.setInvoiceId(INVOICE_ID);
        paymentAnchored.setPaymentIntentId(PAYMENT_INTENT_ID);
        paymentAnchored.setAmount(BigDecimal.valueOf(100.00));
        paymentAnchored.setStatus(RefundStatus.COMPLETED);
        paymentAnchored.setReason(RefundReason.CUSTOMER_RETURN);
        paymentAnchored.setGatewayReference("gw-txn-xyz");
        paymentAnchored.setRequestedAt(Instant.parse("2026-01-10T09:00:00Z"));
        paymentAnchored.setCompletedAt(Instant.parse("2026-01-10T09:00:05Z"));

        RefundPaymentResult standalone = new RefundPaymentResult();
        standalone.setRefundId(UUID.fromString("00000000-0000-7000-8000-000000000052"));
        standalone.setInvoiceId(INVOICE_ID);
        standalone.setAmount(BigDecimal.valueOf(50.00));
        standalone.setStatus(RefundStatus.COMPLETED);
        standalone.setReason(RefundReason.OTHER);
        standalone.setExternalReference("WC-2026-000042");
        standalone.setRequestedAt(Instant.parse("2026-01-12T10:00:00Z"));
        standalone.setCompletedAt(Instant.parse("2026-01-12T10:00:00Z"));

        when(paymentReversalService.listRefundsForInvoice(INVOICE_ID)).thenReturn(List.of(paymentAnchored, standalone));

        mockMvc.perform(get("/v1/invoices/{invoiceId}/refunds", INVOICE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value("00000000-0000-7000-8000-000000000051"))
                .andExpect(jsonPath("$[0].paymentIntentId").value(PAYMENT_INTENT_ID.toString()))
                .andExpect(jsonPath("$[0].amount").value(100.00))
                .andExpect(jsonPath("$[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$[0].reason").value("CUSTOMER_RETURN"))
                .andExpect(jsonPath("$[0].gatewayReference").value("gw-txn-xyz"))
                .andExpect(jsonPath("$[0].requestedAt").isNotEmpty())
                .andExpect(jsonPath("$[0].completedAt").isNotEmpty())
                .andExpect(jsonPath("$[1].id").value("00000000-0000-7000-8000-000000000052"))
                .andExpect(jsonPath("$[1].paymentIntentId").doesNotExist())
                .andExpect(jsonPath("$[1].externalReference").value("WC-2026-000042"));
    }

    /** #922: unknown invoice yields 404 with the standard ApiError envelope. */
    @Test
    void listRefunds_unknownInvoice_returns404WithApiError() throws Exception {
        doThrow(new InvoiceNotFoundException(INVOICE_ID))
                .when(paymentReversalService)
                .listRefundsForInvoice(INVOICE_ID);

        mockMvc.perform(get("/v1/invoices/{invoiceId}/refunds", INVOICE_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }
}
