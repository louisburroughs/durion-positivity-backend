package com.positivity.invoice.internal.controller;

import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.invoice.internal.dto.ReceiptViewResponse;
import com.positivity.invoice.internal.enums.ReceiptDeliveryMethod;
import com.positivity.invoice.internal.enums.ReceiptDeliveryStatus;
import com.positivity.invoice.internal.enums.ReceiptStatus;
import com.positivity.invoice.internal.exception.InvoiceNotFoundException;
import com.positivity.invoice.internal.exception.ReceiptNotFoundException;
import com.positivity.invoice.internal.exception.ReprintLimitExceededException;
import com.positivity.invoice.internal.service.Receipt;
import com.positivity.invoice.internal.service.ReceiptService;
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
        when(receiptService.generateReceipt(eq(INVOICE_ID), eq(PAYMENT_INTENT_ID), any(), any(), any()))
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

    @Test
    void generateReceipt_invoiceNotFound_returns404() throws Exception {
        when(receiptService.generateReceipt(eq(INVOICE_ID), eq(PAYMENT_INTENT_ID), any(), any(), any()))
                .thenThrow(new InvoiceNotFoundException(INVOICE_ID));

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
                .andExpect(status().isNotFound());
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

        // reprintedBy resolved from security context per ADR-0018 — not submitted by
        // caller
        mockMvc.perform(post("/v1/invoices/{invoiceId}/receipts/{receiptId}/reprint", INVOICE_ID, RECEIPT_ID)
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
                .when(receiptService)
                .reprintReceipt(eq(RECEIPT_ID), any());

        mockMvc.perform(post("/v1/invoices/{invoiceId}/receipts/{receiptId}/reprint", INVOICE_ID, RECEIPT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        // reprintedBy resolved from security context per ADR-0018 — not submitted by
                        // caller
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
                .when(receiptService)
                .recordPrintDelivery(INVOICE_ID, RECEIPT_ID, ReceiptDeliveryStatus.SUCCESS);

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
    // GET /v1/invoices/{invoiceId}/receipts/{receiptId} — get receipt (#2214)
    // -------------------------------------------------------------------------

    @Test
    void getReceipt_found_returns200WithFullShape() throws Exception {
        ReceiptViewResponse view = buildReceiptView();
        when(receiptService.getReceipt(INVOICE_ID, RECEIPT_ID)).thenReturn(view);

        mockMvc.perform(get("/v1/invoices/{invoiceId}/receipts/{receiptId}", INVOICE_ID, RECEIPT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receiptId").value(RECEIPT_ID.toString()))
                .andExpect(jsonPath("$.reference").value("RCP-INV-12345-20260115T143022Z-001"))
                .andExpect(jsonPath("$.status").value("GENERATED"))
                .andExpect(jsonPath("$.invoiceId").value(INVOICE_ID.toString()))
                .andExpect(jsonPath("$.invoiceNumber").value("INV-12345"))
                .andExpect(jsonPath("$.paymentIntentId").value(PAYMENT_INTENT_ID.toString()))
                .andExpect(jsonPath("$.paidAmount").value(149.99))
                .andExpect(jsonPath("$.paymentMethod").value("stripe"))
                .andExpect(jsonPath("$.gatewayReference").value("ch_3P0a1b2c3d4e5f"))
                .andExpect(jsonPath("$.cashierId").value("cashier-001"))
                .andExpect(jsonPath("$.terminalId").value("POS-001"))
                .andExpect(jsonPath("$.templateId").value("default"))
                .andExpect(jsonPath("$.templateVersion").value("1.0"))
                .andExpect(jsonPath("$.deliveryMethod").value("EMAIL"))
                .andExpect(jsonPath("$.deliveryStatus").value("SUCCESS"))
                .andExpect(jsonPath("$.deliveryEmailAddress").value("customer@example.com"))
                .andExpect(jsonPath("$.reprintCount").value(2))
                .andExpect(jsonPath("$.lastReprintReason").value("CUSTOMER_REQUEST"))
                .andExpect(jsonPath("$.lastReprintedBy").value("cashier-001"))
                .andExpect(jsonPath("$.createdAt", notNullValue()));
    }

    @Test
    void getReceipt_notFound_returns404WithApiErrorEnvelope() throws Exception {
        when(receiptService.getReceipt(INVOICE_ID, RECEIPT_ID))
                .thenThrow(new ReceiptNotFoundException("Receipt not found: " + RECEIPT_ID));

        mockMvc.perform(get("/v1/invoices/{invoiceId}/receipts/{receiptId}", INVOICE_ID, RECEIPT_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message", notNullValue()))
                .andExpect(jsonPath("$.correlationId", notNullValue()))
                .andExpect(jsonPath("$.timestamp", notNullValue()));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private ReceiptViewResponse buildReceiptView() {
        var view = new ReceiptViewResponse();
        view.setReceiptId(RECEIPT_ID);
        view.setReference("RCP-INV-12345-20260115T143022Z-001");
        view.setStatus(ReceiptStatus.GENERATED);
        view.setInvoiceId(INVOICE_ID);
        view.setInvoiceNumber("INV-12345");
        view.setPaymentIntentId(PAYMENT_INTENT_ID);
        view.setPaidAmount(new BigDecimal("149.99"));
        view.setPaymentMethod("stripe");
        view.setGatewayReference("ch_3P0a1b2c3d4e5f");
        view.setCashierId("cashier-001");
        view.setTerminalId("POS-001");
        view.setTemplateId("default");
        view.setTemplateVersion("1.0");
        view.setDeliveryMethod(ReceiptDeliveryMethod.EMAIL);
        view.setDeliveryStatus(ReceiptDeliveryStatus.SUCCESS);
        view.setDeliveryEmailAddress("customer@example.com");
        view.setReprintCount(2);
        view.setLastReprintReason("CUSTOMER_REQUEST");
        view.setLastReprintedBy("cashier-001");
        view.setCreatedAt(clock.instant());
        return view;
    }

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
