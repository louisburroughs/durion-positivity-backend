package com.positivity.invoice.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.invoice.internal.exception.InvoiceRequestValidationException;
import com.positivity.invoice.internal.security.InvoicePermissions;
import com.positivity.invoice.internal.service.BillingRulesService;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Issue #1713 (part 1): {@link BillingRulesController} was covered by none of this module's
 * {@code @RestControllerAdvice(assignableTypes = ...)} classes, so the rejection of an unknown
 * {@code paymentTermsCode} — plainly client input, and documented as such by the endpoint's own
 * {@code @Operation} prose — reached pos-web-common's platform fallback and answered
 * {@code 500 INTERNAL_ERROR}.
 *
 * <p>{@link BillingRulesExceptionHandler} now scopes the module's {@code
 * InvoiceRequestValidationException} to 400 for this controller (ADR-0017 §1) while leaving the
 * unmapped tail to the platform advice, so a genuine server-side defect still answers a generic,
 * correlated 500 that echoes nothing (ADR-0056 §1).
 *
 * <p>A {@code @WebMvcTest} slice does not work for this module: {@code PosInvoiceApplication}
 * declares {@code @EnableJpaRepositories} directly, which a web slice cannot exclude (issue
 * #1723). This follows the module's existing full-context pattern, authenticating via the
 * gateway's {@code X-User}/{@code X-Authorities} headers.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Billing-rules endpoints answer the errors they document (#1713)")
class BillingRulesControllerErrorHandlingTest {

    private static final String PARTY_ID = "01960003-0000-7000-8000-000000000002";

    private static final String BODY = """
            {"partyId":"01960003-0000-7000-8000-000000000002",
             "purchaseOrderRequired":true,
             "paymentTermsCode":"2/10 Net 30",
             "invoiceDeliveryMethod":"EMAIL",
             "invoiceGroupingStrategy":"PER_WORKORDER",
             "version":1,
             "createdAt":"2026-01-15T09:30:00Z",
             "updatedAt":"2026-01-16T11:45:00Z",
             "updatedBy":"tester"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BillingRulesService billingRulesService;

    private MockHttpServletRequestBuilder withAuth(MockHttpServletRequestBuilder request) {
        return request.header("X-User", "test-user").header("X-Authorities", InvoicePermissions.BILLING_RULES);
    }

    @Test
    @DisplayName("an unknown paymentTermsCode answers 400 with the enveloped validation error, not 500")
    void unknownPaymentTermsCodeAnswers400() throws Exception {
        when(billingRulesService.getCurrentUsername()).thenReturn("test-user");
        when(billingRulesService.getBillingRules(PARTY_ID)).thenReturn(Optional.empty());
        when(billingRulesService.saveBillingRules(any(), anyString()))
                .thenThrow(new InvoiceRequestValidationException("Unknown paymentTermsCode '2/10 Net 30'"));

        mockMvc.perform(withAuth(put("/v1/billing/rules/{partyId}", PARTY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Unknown paymentTermsCode '2/10 Net 30'"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(header().exists("X-Correlation-Id"));
    }

    /**
     * The other half of the ADR-0056 §1 guarantee: the scoped advice must not become a new
     * blanket handler. A bare {@code IllegalArgumentException} — what Hibernate/JPA and {@code
     * UUID.fromString} throw for reasons that have nothing to do with the caller — still falls
     * through to the platform advice as a generic 500 that never echoes the exception's text.
     */
    @Test
    @DisplayName("an unexpected IllegalArgumentException answers a generic correlated 500")
    void unexpectedIllegalArgumentExceptionAnswersGeneric500() throws Exception {
        when(billingRulesService.getCurrentUsername()).thenReturn("test-user");
        when(billingRulesService.getBillingRules(PARTY_ID)).thenReturn(Optional.empty());
        when(billingRulesService.saveBillingRules(any(), anyString()))
                .thenThrow(new IllegalArgumentException("could not prepare statement [SELECT br FROM ...]"));

        mockMvc.perform(withAuth(put("/v1/billing/rules/{partyId}", PARTY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.not(
                                org.hamcrest.Matchers.containsString("could not prepare statement"))))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(header().exists("X-Correlation-Id"));
    }
}
