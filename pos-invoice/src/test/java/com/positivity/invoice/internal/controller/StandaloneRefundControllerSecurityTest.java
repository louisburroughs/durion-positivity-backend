package com.positivity.invoice.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.invoice.ControllerSliceConfig;
import com.positivity.invoice.internal.security.InvoicePermissions;
import com.positivity.invoice.internal.service.PaymentReversalService;
import com.positivity.invoice.internal.service.RefundPaymentResult;
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
 * Security-gate proof for #2226: both standalone-refund endpoints now carry
 * {@code @PreAuthorize(invoice:refund:issue_manual)} rather than relying solely on the
 * service-layer {@code requireAuthority} check. {@link StandaloneRefundControllerTest} runs
 * standalone MockMvc (no method security in effect); this slice runs the real gateway-header
 * authentication so the annotation is actually exercised, mirroring {@code
 * PaymentReversalControllerErrorHandlingTest}.
 */
@WebMvcTest(StandaloneRefundController.class)
@Import({GatewaySecurityConfig.class, WebCommonErrorAutoConfiguration.class, ControllerSliceConfig.class})
@DisplayName("Standalone refund endpoints require invoice:refund:issue_manual (#2226)")
class StandaloneRefundControllerSecurityTest {

    private static final UUID INVOICE_ID = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PaymentReversalService paymentReversalService;

    private MockHttpServletRequestBuilder withAuthorities(MockHttpServletRequestBuilder request, String authorities) {
        return request.header("X-User", "test-user").header("X-Authorities", authorities);
    }

    @Test
    @DisplayName("createStandaloneInvoiceRefund: 403 without invoice:refund:issue_manual")
    void refundInvoiceStandalone_missingPermission_returns403() throws Exception {
        mockMvc.perform(withAuthorities(
                        post("/v1/invoices/{invoiceId}/refunds", INVOICE_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"amount\":25.00,\"reason\":\"CUSTOMER_RETURN\"}"),
                        "invoice:manage"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("createStandaloneInvoiceRefund: 201 with invoice:refund:issue_manual")
    void refundInvoiceStandalone_withPermission_returns201() throws Exception {
        RefundPaymentResult saved = new RefundPaymentResult();
        saved.setRefundId(UUID.randomUUID());
        saved.setInvoiceId(INVOICE_ID);
        when(paymentReversalService.refundInvoiceStandalone(any(), any(), any(), any(), any()))
                .thenReturn(saved);

        mockMvc.perform(withAuthorities(
                        post("/v1/invoices/{invoiceId}/refunds", INVOICE_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"amount\":25.00,\"reason\":\"CUSTOMER_RETURN\"}"),
                        InvoicePermissions.REFUND_ISSUE_MANUAL))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("createStandalonePartyRefund: 403 without invoice:refund:issue_manual")
    void refundPartyStandalone_missingPermission_returns403() throws Exception {
        mockMvc.perform(withAuthorities(
                        post("/v1/refunds")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"partyId\":\"party-0001\",\"amount\":25.00,\"reason\":\"CUSTOMER_RETURN\"}"),
                        "invoice:manage"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("createStandalonePartyRefund: 201 with invoice:refund:issue_manual")
    void refundPartyStandalone_withPermission_returns201() throws Exception {
        RefundPaymentResult saved = new RefundPaymentResult();
        saved.setRefundId(UUID.randomUUID());
        saved.setPartyId("party-0001");
        when(paymentReversalService.refundPartyStandalone(any(), any(), any(), any(), any()))
                .thenReturn(saved);

        mockMvc.perform(withAuthorities(
                        post("/v1/refunds")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"partyId\":\"party-0001\",\"amount\":25.00,\"reason\":\"CUSTOMER_RETURN\"}"),
                        InvoicePermissions.REFUND_ISSUE_MANUAL))
                .andExpect(status().isCreated());
    }
}
