package com.positivity.invoice.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.invoice.ControllerSliceConfig;
import com.positivity.invoice.internal.enums.ReceiptStatus;
import com.positivity.invoice.internal.security.InvoicePermissions;
import com.positivity.invoice.internal.service.Receipt;
import com.positivity.invoice.internal.service.ReceiptService;
import com.positivity.security.common.GatewaySecurityConfig;
import com.positivity.web.common.WebCommonErrorAutoConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Security-gate proof for #2226: {@code generateReceipt} now carries its own
 * {@code @PreAuthorize(invoice:receipt:generate)} rather than relying solely on the
 * service-layer {@code requireAuthority} check. {@link ReceiptControllerTest} runs standalone
 * MockMvc (no method security in effect); this slice runs the real gateway-header authentication
 * so the annotation is actually exercised, mirroring {@code PaymentReversalControllerErrorHandlingTest}.
 *
 * <p>{@code reprintReceipt} carries no new {@code @PreAuthorize} (kept under the class-level
 * {@code isAuthenticated()}); its reprint-cap override is a location-scoped service-layer check
 * covered by {@code ReceiptServiceImplTest}.
 */
@WebMvcTest(ReceiptController.class)
@Import({GatewaySecurityConfig.class, WebCommonErrorAutoConfiguration.class, ControllerSliceConfig.class})
@DisplayName("generateReceipt requires invoice:receipt:generate (#2226)")
class ReceiptControllerSecurityTest {

    private static final UUID INVOICE_ID = UUID.randomUUID();
    private static final String VALID_BODY = """
            {"paymentIntentId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a60","terminalId":"TERM-001",
             "templateId":"RECEIPT_DEFAULT","templateVersion":"1"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReceiptService receiptService;

    private MockHttpServletRequestBuilder withAuthorities(MockHttpServletRequestBuilder request, String authorities) {
        return request.header("X-User", "test-user").header("X-Authorities", authorities);
    }

    @Test
    @DisplayName("generateReceipt: 403 without invoice:receipt:generate")
    void generateReceipt_missingPermission_returns403() throws Exception {
        mockMvc.perform(withAuthorities(
                        post("/v1/invoices/{invoiceId}/receipts", INVOICE_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(VALID_BODY),
                        "invoice:manage"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("generateReceipt: 201 with invoice:receipt:generate")
    void generateReceipt_withPermission_returns201() throws Exception {
        Receipt receipt = new Receipt();
        receipt.setId(UUID.randomUUID());
        receipt.setReference("RCP-INV-1-20260101T000000Z-001");
        receipt.setStatus(ReceiptStatus.GENERATED);
        when(receiptService.generateReceipt(any(), any(), any(), any(), any())).thenReturn(receipt);

        mockMvc.perform(withAuthorities(
                        post("/v1/invoices/{invoiceId}/receipts", INVOICE_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(VALID_BODY),
                        InvoicePermissions.RECEIPT_GENERATE))
                .andExpect(status().isCreated());
    }
}
