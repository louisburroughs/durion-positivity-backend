package com.positivity.invoice.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.invoice.ControllerSliceConfig;
import com.positivity.invoice.internal.exception.ExcessiveAdjustmentException;
import com.positivity.invoice.internal.exception.InvalidManagerApprovalException;
import com.positivity.invoice.internal.exception.InvoiceRequestValidationException;
import com.positivity.invoice.internal.exception.ManagerApprovalRequiredException;
import com.positivity.invoice.internal.security.InvoicePermissions;
import com.positivity.invoice.internal.service.InvoiceFinalizationService;
import com.positivity.invoice.internal.service.OrderInvoiceService;
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
 * End-to-end proof for issue #1694, exercised through {@link InvoiceController}: the blanket
 * {@code @ExceptionHandler(IllegalArgumentException.class)} that used to live in {@link
 * InvoiceExceptionHandler} (400 {@code VALIDATION_ERROR}, with a special case attaching a {@code
 * managerApprovalCode} field error whenever the message mentioned "approval code") is deleted.
 * The approval-code cases now have their own step-up authorization types at 403 ({@link
 * ManagerApprovalRequiredException}, {@link InvalidManagerApprovalException}; ADR-0017 §2
 * question 1, decided in #1725); a new-total-goes-
 * negative adjustment now has its own domain-policy type at 422 ({@link
 * ExcessiveAdjustmentException}); plain request-shape validation keeps its 400 via this module's
 * own {@link InvoiceRequestValidationException}. A bare {@code IllegalArgumentException} — what
 * Hibernate/JPA throw for an invalid query, what {@code UUID.fromString} throws on malformed
 * stored data — is no longer caught by the module advice and falls through to pos-web-common's
 * platform-wide {@code GlobalApiExceptionHandler}, which answers a generic, correlated 500 that
 * never echoes the exception's own text.
 *
 * <p>Runs as a {@code @WebMvcTest} slice wired by {@link com.positivity.invoice.ControllerSliceConfig},
 * whose Javadoc records why this module could not be sliced before #1723. Authentication goes
 * through the gateway's {@code X-User}/{@code X-Authorities} headers rather than
 * {@code @WithMockUser}.
 */
@WebMvcTest(InvoiceController.class)
@Import({GatewaySecurityConfig.class, WebCommonErrorAutoConfiguration.class, ControllerSliceConfig.class})
@DisplayName("Invoice endpoints answer the errors they document (#1694)")
class InvoiceControllerErrorHandlingTest {

    private static final UUID INVOICE_ID = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private com.positivity.invoice.internal.config.InvoiceService invoiceService;

    @MockitoBean
    private InvoiceFinalizationService invoiceFinalizationService;

    @MockitoBean
    private OrderInvoiceService orderInvoiceService;

    private MockHttpServletRequestBuilder withAuth(MockHttpServletRequestBuilder request, String authorities) {
        return request.header("X-User", "test-user").header("X-Authorities", authorities);
    }

    /** A request missing workorderId still answers its documented 400 with the module's own message. */
    @Test
    @DisplayName("a missing workorderId answers 400 with its own message and code")
    void missingWorkorderIdAnswers400WithItsOwnMessageAndCode() throws Exception {
        when(invoiceService.createInvoice(any(com.positivity.shared.dto.InvoiceCreationRequest.class)))
                .thenThrow(new InvoiceRequestValidationException("workorderId is required"));

        mockMvc.perform(withAuth(
                        post("/v1/invoices")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"),
                        InvoicePermissions.MANAGE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("workorderId is required"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(header().exists("X-Correlation-Id"));
    }

    /** A required-but-missing manager approval code above the cap answers its documented 403. */
    @Test
    @DisplayName("a required-but-missing manager approval code answers 403 with its own code and a nextAction")
    void missingManagerApprovalAnswers403WithItsOwnMessageAndCode() throws Exception {
        when(invoiceFinalizationService.completeInvoice(any(), any()))
                .thenThrow(
                        new ManagerApprovalRequiredException(
                                "Manager approval code required: cannot finalize invoices exceeding $500.00 without a manager approval code"));

        mockMvc.perform(withAuth(
                        post("/v1/invoices/{invoiceId}/finalize", INVOICE_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"),
                        InvoicePermissions.FINALIZE))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MANAGER_APPROVAL_REQUIRED"))
                .andExpect(
                        jsonPath("$.nextAction").value(org.hamcrest.Matchers.containsString("elevateManagerApproval")))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(header().exists("X-Correlation-Id"));
    }

    /** An invalid/expired manager approval token answers its documented 403. */
    @Test
    @DisplayName("an invalid manager approval token answers 403 with its own message, code, and a nextAction")
    void invalidManagerApprovalAnswers403WithItsOwnMessageAndCode() throws Exception {
        when(invoiceFinalizationService.revert(any(), any(), any()))
                .thenThrow(new InvalidManagerApprovalException(
                        "Invalid or expired manager approval code for this invoice"));

        mockMvc.perform(withAuth(
                        post("/v1/invoices/{invoiceId}/revert", INVOICE_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"managerApprovalCode\":\"not-a-valid-token\",\"reason\":\"Customer dispute\"}"),
                        InvoicePermissions.FINALIZE))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MANAGER_APPROVAL_INVALID"))
                .andExpect(jsonPath("$.message").value("Invalid or expired manager approval code for this invoice"))
                .andExpect(
                        jsonPath("$.nextAction").value(org.hamcrest.Matchers.containsString("elevateManagerApproval")))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(header().exists("X-Correlation-Id"));
    }

    /** An adjustment that would drive the invoice total negative answers its documented 422. */
    @Test
    @DisplayName("an excessive adjustment answers 422 with its own message and code")
    void excessiveAdjustmentAnswers422WithItsOwnMessageAndCode() throws Exception {
        when(invoiceService.applyAdjustment(any(), any()))
                .thenThrow(new ExcessiveAdjustmentException(
                        "invoice total cannot be negative; adjustments would require a credit memo"));

        mockMvc.perform(withAuth(
                        post("/v1/invoices/{invoiceId}/adjustments", INVOICE_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"type":"DISCOUNT","amount":9999.00,"reason":"Bulk discount","authorizedBy":"jdoe"}
                                        """),
                        InvoicePermissions.MANAGE))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("EXCESSIVE_ADJUSTMENT"))
                .andExpect(jsonPath("$.message")
                        .value("invoice total cannot be negative; adjustments would require a credit memo"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(header().exists("X-Correlation-Id"));
    }

    /**
     * The regression this test guards against: a bare {@code IllegalArgumentException} from the
     * service layer must NOT come back as a 4xx carrying its own message — it is an unexpected
     * server-side failure and must land on the generic, correlated 500 fallback.
     */
    @Test
    @DisplayName("an unexpected IllegalArgumentException answers 500 without leaking its message")
    void aBareIllegalArgumentExceptionAnswers500WithoutLeakingItsMessage() throws Exception {
        String leakCanary = "org.hibernate.query.sqm.UnknownPathException: Could not resolve attribute "
                + "'workorderId' of 'com.positivity.invoice.internal.entity.Invoice'";
        when(invoiceService.createInvoice(any(com.positivity.shared.dto.InvoiceCreationRequest.class)))
                .thenThrow(new IllegalArgumentException(leakCanary));

        String body = mockMvc.perform(withAuth(
                        post("/v1/invoices")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"workorderId\":\"" + UUID.randomUUID() + "\"}"),
                        InvoicePermissions.MANAGE))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("Unexpected error occurred"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(header().exists("X-Correlation-Id"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).doesNotContain(leakCanary).doesNotContain("UnknownPathException");
    }
}
