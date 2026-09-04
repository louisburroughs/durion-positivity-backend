package com.positivity.invoice.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.invoice.internal.security.InvoicePermissions;
import com.positivity.invoice.internal.service.InvoiceAnalyticsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * End-to-end proof for issue #1694, exercised through {@link InvoiceAnalyticsController}: the
 * blanket {@code @ExceptionHandler(IllegalArgumentException.class)} that used to live in {@link
 * InvoiceAnalyticsExceptionHandler} (400 {@code VALIDATION_ERROR}) is deleted. This module's own
 * {@code InvoiceRequestValidationException} keeps the genuine-client-error contract at the same
 * 400; a bare {@code IllegalArgumentException} from the service layer — what Hibernate/JPA throw
 * for an invalid query — is no longer caught by the module advice and falls through to
 * pos-web-common's platform-wide {@code GlobalApiExceptionHandler}, which answers a generic,
 * correlated 500 that never echoes the exception's own text.
 *
 * <p>A {@code @WebMvcTest} slice does not work for this module: {@code PosInvoiceApplication}
 * declares {@code @EnableJpaRepositories} directly, which a web slice cannot exclude and which
 * then fails to find an {@code entityManagerFactory} bean. So this follows the module's existing
 * full-context pattern (see {@code OrderInvoiceContractBehaviorIT}) instead, authenticating via
 * the gateway's {@code X-User}/{@code X-Authorities} headers rather than {@code @WithMockUser}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Invoice-analytics endpoints answer the errors they document (#1694)")
class InvoiceAnalyticsControllerErrorHandlingTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private InvoiceAnalyticsService invoiceAnalyticsService;

    private MockHttpServletRequestBuilder withAuth(MockHttpServletRequestBuilder request) {
        return request.header("X-User", "test-user").header("X-Authorities", InvoicePermissions.ANALYTICS_VIEW);
    }

    /** The bad-date-range rejection happens in the controller itself, before the service is called. */
    @Test
    @DisplayName("endDate before startDate answers 400 with its own message and code")
    void endDateBeforeStartDateAnswers400WithItsOwnMessageAndCode() throws Exception {
        mockMvc.perform(withAuth(get("/v1/invoices/analytics/revenue-by-customer")
                        .param("startDate", "2026-06-30")
                        .param("endDate", "2026-06-01")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("End date cannot be before start date"))
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
        when(invoiceAnalyticsService.revenueByCustomer(any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenThrow(new IllegalArgumentException(leakCanary));

        String body = mockMvc.perform(withAuth(get("/v1/invoices/analytics/revenue-by-customer")
                        .param("startDate", "2026-06-01")
                        .param("endDate", "2026-06-30")))
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
