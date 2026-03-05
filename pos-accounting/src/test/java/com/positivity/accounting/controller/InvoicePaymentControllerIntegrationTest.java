package com.positivity.accounting.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;

import com.positivity.accounting.BaseIntegrationTest;
import com.positivity.accounting.service.BillingRulesService;
import com.positivity.accounting.service.InvoicePaymentStatusService;
import com.positivity.accounting.service.InvoiceRegenerationService;
import com.positivity.shared.dto.InvoiceGenerationResponse;

@DisplayName("InvoicePaymentController Integration Tests")
class InvoicePaymentControllerIntegrationTest extends BaseIntegrationTest {

        private static final String API_V1_REGENERATE_INVOICE = "/v1/accounting/invoice/invoices";

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
                                .andExpect(jsonPath("$.error.code").value("ARGUMENT_NOT_VALID"))
                                .andExpect(jsonPath("$.error.message").value("workorderId is required"));
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
                                .andExpect(jsonPath("$.errorCode").value("REQUEST_FAILED"))
                                .andExpect(jsonPath("$.message").value("Workorder not found"));
        }
}
