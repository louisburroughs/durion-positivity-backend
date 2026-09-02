package com.positivity.accounting.internal.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.accounting.BaseIntegrationTest;
import com.positivity.accounting.internal.dto.InvoiceStatusResponse;
import com.positivity.accounting.internal.enums.PaymentStatus;
import com.positivity.accounting.internal.service.BillingRulesService;
import com.positivity.accounting.internal.service.InvoicePaymentStatusService;
import com.positivity.accounting.internal.service.InvoiceRegenerationService;
import com.positivity.shared.dto.InvoiceGenerationResponse;
import jakarta.persistence.EntityNotFoundException;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;

@DisplayName("InvoicePaymentController Integration Tests")
class InvoicePaymentControllerIntegrationTest extends BaseIntegrationTest {

    private static final String API_V1_REGENERATE_INVOICE = "/v1/accounting/invoice/invoices";
    private static final String API_V1_INVOICE_STATUS = "/v1/accounting/invoice/{invoiceId}/status";

    @MockitoBean
    private InvoicePaymentStatusService invoicePaymentStatusService;

    @MockitoBean
    private BillingRulesService billingRulesService;

    @MockitoBean
    private InvoiceRegenerationService invoiceRegenerationService;

    @Test
    @DisplayName("Regenerate invoice from workorder returns 200 with payload")
    void testRegenerateInvoiceFromWorkorder_Success() throws Exception {
        UUID workorderId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID invoiceId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        String idempotencyKey = "idem-regen-001";

        InvoiceGenerationResponse response = InvoiceGenerationResponse.builder()
                .invoiceId(invoiceId)
                .workorderId(workorderId)
                .status("DRAFT")
                .build();

        when(invoiceRegenerationService.regenerateInvoiceFromWorkorder(workorderId, idempotencyKey))
                .thenReturn(response);

        String payload = """
        {
          "workorderId": "%s",
          "idempotencyKey": "%s"
        }
        """.formatted(workorderId, idempotencyKey);

        mockMvc.perform(withAuth(post(API_V1_REGENERATE_INVOICE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.invoiceId").value(invoiceId.toString()))
                .andExpect(jsonPath("$.workorderId").value(workorderId.toString()))
                .andExpect(jsonPath("$.status").value("DRAFT"));

        verify(invoiceRegenerationService).regenerateInvoiceFromWorkorder(eq(workorderId), eq(idempotencyKey));
    }

    @Test
    @DisplayName("Regenerate invoice from workorder returns 400 when workorderId is missing")
    void testRegenerateInvoiceFromWorkorder_MissingWorkorderId() throws Exception {
        String payload = """
        {
          "idempotencyKey": "idem-regen-002"
        }
        """;

        mockMvc.perform(withAuth(post(API_V1_REGENERATE_INVOICE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ARGUMENT_NOT_VALID"))
                .andExpect(jsonPath("$.message").value("workorderId is required"));
    }

    @Test
    @DisplayName("Regenerate invoice from workorder propagates 404 from service")
    void testRegenerateInvoiceFromWorkorder_NotFound() throws Exception {
        UUID workorderId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        String idempotencyKey = "idem-regen-003";

        when(invoiceRegenerationService.regenerateInvoiceFromWorkorder(workorderId, idempotencyKey))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Workorder not found"));

        String payload = """
        {
          "workorderId": "%s",
          "idempotencyKey": "%s"
        }
        """.formatted(workorderId, idempotencyKey);

        mockMvc.perform(withAuth(post(API_V1_REGENERATE_INVOICE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REQUEST_FAILED"))
                .andExpect(jsonPath("$.message").value("Workorder not found"));
    }

    /**
     * Pins the ADR-0017 error contract end-to-end: with the controller's try/catch removed, an
     * {@link EntityNotFoundException} from the service must be mapped by
     * {@code AccountingExceptionHandler} (module advice) into a 404 {@code ApiError} envelope —
     * never falling through to pos-web-common's global catch-all (which would answer 500).
     */
    @Test
    @DisplayName("Get invoice status maps EntityNotFoundException to 404 NOT_FOUND ApiError envelope")
    void testGetInvoiceStatus_UnknownInvoice_Returns404ApiError() throws Exception {
        UUID invoiceId = UUID.fromString("00000000-0000-0000-0000-00000000dead");

        when(invoicePaymentStatusService.getInvoiceStatus(invoiceId))
                .thenThrow(new EntityNotFoundException("Invoice not found: " + invoiceId));

        mockMvc.perform(withAuth(get(API_V1_INVOICE_STATUS, invoiceId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Invoice not found: " + invoiceId))
                .andExpect(jsonPath("$.status").value(HttpStatus.NOT_FOUND.value()))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());

        verify(invoicePaymentStatusService).getInvoiceStatus(eq(invoiceId));
    }

    @Test
    @DisplayName("Get invoice status returns 200 with status payload for a known invoice")
    void testGetInvoiceStatus_KnownInvoice_Returns200WithPayload() throws Exception {
        UUID invoiceId = UUID.fromString("00000000-0000-0000-0000-000000000042");

        when(invoicePaymentStatusService.getInvoiceStatus(invoiceId))
                .thenReturn(InvoiceStatusResponse.builder()
                        .invoiceId(invoiceId)
                        .status(PaymentStatus.PARTIALLY_PAID)
                        .totalPaid(new BigDecimal("75.00"))
                        .invoiceTotal(new BigDecimal("100.00"))
                        .remainingBalance(new BigDecimal("25.00"))
                        .latestTransactionReference("TXN-042")
                        .build());

        mockMvc.perform(withAuth(get(API_V1_INVOICE_STATUS, invoiceId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.invoiceId").value(invoiceId.toString()))
                .andExpect(jsonPath("$.status").value("PARTIALLY_PAID"))
                .andExpect(jsonPath("$.totalPaid").value(75.00))
                .andExpect(jsonPath("$.invoiceTotal").value(100.00))
                .andExpect(jsonPath("$.remainingBalance").value(25.00))
                .andExpect(jsonPath("$.latestTransactionReference").value("TXN-042"));
    }
}
