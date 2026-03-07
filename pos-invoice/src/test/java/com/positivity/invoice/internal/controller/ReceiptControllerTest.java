package com.positivity.invoice.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.notNullValue;

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

import com.positivity.invoice.internal.enums.ReceiptDeliveryStatus;
import com.positivity.invoice.internal.enums.ReceiptStatus;
import com.positivity.invoice.internal.exception.ReprintLimitExceededException;
import com.positivity.invoice.internal.exception.ReceiptNotFoundException;
import com.positivity.invoice.service.Receipt;
import com.positivity.invoice.service.ReceiptService;

/**
 * Controller-layer unit tests for {@link ReceiptController} covering Story #7:
 * Receipt Generation.
 *
 * <p>
 * Uses standalone MockMvc (no Spring context) with Mockito for the service
 * layer. Security context and permission enforcement are validated at the
 * service layer in {@link ReceiptServiceImplTest}; this layer asserts HTTP
 * response codes per ADR-0017.
 *
 * <p>
 * ADR-0017 response codes:
 * <ul>
 * <li>201 Created — receipt generated successfully</li>
 * <li>200 OK — reprint completed</li>
 * <li>400 Bad Request — missing required fields</li>
 * <li>409 Conflict — reprint limit exceeded</li>
 * </ul>
 *
 * Issue: #7
 */
@ExtendWith(MockitoExtension.class)
class ReceiptControllerTest {

    private static final UUID INVOICE_ID = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final UUID RECEIPT_ID = UUID.fromString("00000000-0000-0000-0000-000000000030");
    private static final UUID PAYMENT_INTENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000020");

    @Mock
    private ReceiptService receiptService;

    @InjectMocks
    private ReceiptController receiptController;

    @Spy
    private Clock clock = Clock.fixed(Instant.parse("2026-01-15T14:30:22Z"), ZoneOffset.UTC);

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(receiptController)
                .setControllerAdvice(new ReceiptExceptionHandler(clock))
                .build();
    }

    // -------------------------------------------------------------------------
    // POST /v1/invoices/{invoiceId}/receipts — generate receipt
    // -------------------------------------------------------------------------

    /**
     * AC1: Successful receipt generation must return HTTP 201 Created (ADR-0017).
     * Issue: #7
     */
    @Test
    void generateReceipt_returns201() throws Exception {
        var receipt = buildGeneratedReceipt();
        when(receiptService.generateReceipt(
                eq(INVOICE_ID), eq(PAYMENT_INTENT_ID), any(), any(), any()))
                .thenReturn(receipt);

        mockMvc.perform(post("/v1/invoices/{invoiceId}/receipts", INVOICE_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "paymentIntentId": "00000000-0000-0000-0000-000000000020",
                          "terminalId": "POS-001",
                          "templateId": "default",
                          "templateVersion": "1.0"
                        }
                        """))
                                                                .andExpect(status().isCreated())
                                                                .andExpect(jsonPath("$.receiptId", notNullValue()));
    }

    /**
     * Missing required fields must return HTTP 400 Bad Request (ADR-0017).
     * Issue: #7
     */
    @Test
    void generateReceipt_withEmptyBody_returns400() throws Exception {
        mockMvc.perform(post("/v1/invoices/{invoiceId}/receipts", INVOICE_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isBadRequest());
    }

    // -------------------------------------------------------------------------
    // POST /v1/invoices/{invoiceId}/receipts/{receiptId}/reprint — reprint
    // -------------------------------------------------------------------------

    /**
     * AC5: Successful reprint must return HTTP 200 OK (ADR-0017).
     * Issue: #7
     */
    @Test
    void reprintReceipt_returns200() throws Exception {
        var receipt = buildGeneratedReceipt();
        receipt.setReprintCount(1);
        when(receiptService.reprintReceipt(eq(RECEIPT_ID), any())).thenReturn(receipt);

        // reprintedBy resolved from security context per ADR-0018 — not submitted by caller
        mockMvc.perform(post("/v1/invoices/{invoiceId}/receipts/{receiptId}/reprint",
                INVOICE_ID, RECEIPT_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "reason": "CUSTOMER_REQUEST"
                        }
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receiptId", notNullValue()));
    }

    @Test
    void recordPrintDelivery_returns200() throws Exception {
        mockMvc.perform(post("/v1/invoices/{invoiceId}/receipts/{receiptId}/print", INVOICE_ID, RECEIPT_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "status": "SUCCESS"
                        }
                        """))
                .andExpect(status().isOk());
    }

    @Test
    void sendEmailReceipt_returns200() throws Exception {
        mockMvc.perform(post("/v1/invoices/{invoiceId}/receipts/{receiptId}/email", INVOICE_ID, RECEIPT_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "emailAddress": "test@example.com",
                          "status": "SUCCESS"
                        }
                        """))
                .andExpect(status().isOk());
    }

    /**
     * AC5: When service throws ReprintLimitExceededException, controller must
     * return HTTP 409 Conflict (ADR-0017).
     * Issue: #7
     */
    @Test
    void reprintReceipt_limitExceeded_returns409() throws Exception {
        doThrow(new ReprintLimitExceededException("Reprint limit of 5 exceeded"))
                .when(receiptService).reprintReceipt(eq(RECEIPT_ID), any());

        mockMvc.perform(post("/v1/invoices/{invoiceId}/receipts/{receiptId}/reprint",
                INVOICE_ID, RECEIPT_ID)
                .contentType(MediaType.APPLICATION_JSON)
                // reprintedBy resolved from security context per ADR-0018 — not submitted by caller
                .content("""
                        {
                          "reason": "CUSTOMER_REQUEST"
                        }
                        """))
                .andExpect(status().isConflict());
    }

    @Test
    void recordPrintDelivery_receiptNotFound_returns404() throws Exception {
        doThrow(new ReceiptNotFoundException("Receipt not found"))
                                .when(receiptService).recordPrintDelivery(RECEIPT_ID, ReceiptDeliveryStatus.SUCCESS);

        mockMvc.perform(post("/v1/invoices/{invoiceId}/receipts/{receiptId}/print", INVOICE_ID, RECEIPT_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "status": "SUCCESS"
                        }
                        """))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Receipt buildGeneratedReceipt() {
        var receipt = new Receipt();
        receipt.setId(RECEIPT_ID);
        receipt.setInvoiceId(INVOICE_ID);
        receipt.setPaymentIntentId(PAYMENT_INTENT_ID);
        receipt.setStatus(ReceiptStatus.GENERATED);
        receipt.setReference("RCP-INV-12345-20260115T143022Z-001");
        receipt.setReprintCount(0);
        return receipt;
    }
}
