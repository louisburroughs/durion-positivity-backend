package com.positivity.invoice.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.invoice.internal.enums.RefundReason;
import com.positivity.invoice.internal.enums.RefundStatus;
import com.positivity.invoice.internal.exception.InsufficientRefundableAmountException;
import com.positivity.invoice.internal.exception.InvoiceNotFoundException;
import com.positivity.invoice.service.PaymentReversalService;
import com.positivity.invoice.service.RefundPaymentResult;
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
 * Controller-layer unit tests for {@link StandaloneRefundController} covering issue #926:
 * standalone refunds not anchored to a captured PaymentIntent.
 *
 * <p>Uses standalone MockMvc (no Spring context) with Mockito for the service layer. Authority
 * enforcement (ISSUE_MANUAL_REFUND) is validated at the service layer in
 * {@link com.positivity.invoice.internal.service.PaymentReversalServiceImplTest}; this layer
 * asserts HTTP response codes.
 */
@ExtendWith(MockitoExtension.class)
class StandaloneRefundControllerTest {

    private static final UUID INVOICE_ID = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final UUID REFUND_ID = UUID.fromString("00000000-0000-7000-8000-000000000005");

    @Mock
    private PaymentReversalService paymentReversalService;

    @InjectMocks
    private StandaloneRefundController standaloneRefundController;

    @Spy
    private Clock clock = Clock.fixed(Instant.parse("2026-01-15T14:30:22Z"), ZoneOffset.UTC);

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(standaloneRefundController)
                .setControllerAdvice(new PaymentReversalExceptionHandler(clock))
                .build();
    }

    // -------------------------------------------------------------------------
    // POST /v1/invoices/{invoiceId}/refunds — invoice-anchored standalone refund
    // -------------------------------------------------------------------------

    @Test
    void refundInvoiceStandalone_returns201() throws Exception {
        RefundPaymentResult saved = new RefundPaymentResult();
        saved.setRefundId(REFUND_ID);
        saved.setInvoiceId(INVOICE_ID);
        saved.setPartyId("party-0001");
        saved.setAmount(BigDecimal.valueOf(120.00));
        saved.setReason(RefundReason.OTHER);
        saved.setStatus(RefundStatus.COMPLETED);
        saved.setExternalReference("WC-2026-000042");
        saved.setCompletedAt(Instant.parse("2026-01-15T14:30:22Z"));

        when(paymentReversalService.refundInvoiceStandalone(eq(INVOICE_ID), any(), any(), any(), any()))
                .thenReturn(saved);

        mockMvc.perform(post("/v1/invoices/{invoiceId}/refunds", INVOICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":120.00,\"reason\":\"OTHER\","
                                + "\"externalReference\":\"WC-2026-000042\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.refundId").value(REFUND_ID.toString()))
                .andExpect(jsonPath("$.partyId").value("party-0001"))
                .andExpect(jsonPath("$.paymentIntentId").doesNotExist())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void refundInvoiceStandalone_invoiceNotFound_returns404() throws Exception {
        doThrow(new InvoiceNotFoundException(INVOICE_ID))
                .when(paymentReversalService)
                .refundInvoiceStandalone(any(), any(), any(), any(), any());

        mockMvc.perform(post("/v1/invoices/{invoiceId}/refunds", INVOICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":50.00,\"reason\":\"OTHER\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void refundInvoiceStandalone_exceedsRefundable_returns422() throws Exception {
        doThrow(new InsufficientRefundableAmountException("Insufficient refundable amount: 50.00 < 100.00"))
                .when(paymentReversalService)
                .refundInvoiceStandalone(any(), any(), any(), any(), any());

        mockMvc.perform(post("/v1/invoices/{invoiceId}/refunds", INVOICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":100.00,\"reason\":\"OTHER\"}"))
                .andExpect(status().is(422));
    }

    // -------------------------------------------------------------------------
    // POST /v1/refunds — party-anchored standalone refund
    // -------------------------------------------------------------------------

    @Test
    void refundPartyStandalone_returns201() throws Exception {
        RefundPaymentResult saved = new RefundPaymentResult();
        saved.setRefundId(REFUND_ID);
        saved.setPartyId("party-0009");
        saved.setAmount(BigDecimal.valueOf(75.00));
        saved.setReason(RefundReason.OTHER);
        saved.setStatus(RefundStatus.COMPLETED);
        saved.setExternalReference("WC-2026-000043");

        when(paymentReversalService.refundPartyStandalone(eq("party-0009"), any(), any(), any(), any()))
                .thenReturn(saved);

        mockMvc.perform(post("/v1/refunds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"partyId\":\"party-0009\",\"amount\":75.00,\"reason\":\"OTHER\","
                                + "\"externalReference\":\"WC-2026-000043\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.refundId").value(REFUND_ID.toString()))
                .andExpect(jsonPath("$.partyId").value("party-0009"))
                .andExpect(jsonPath("$.invoiceId").doesNotExist());
    }

    @Test
    void refundPartyStandalone_missingPartyId_returns400() throws Exception {
        mockMvc.perform(post("/v1/refunds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":75.00,\"reason\":\"OTHER\"}"))
                .andExpect(status().isBadRequest());
    }
}
