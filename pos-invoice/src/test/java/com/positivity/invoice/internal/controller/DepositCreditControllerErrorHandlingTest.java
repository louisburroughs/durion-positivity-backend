package com.positivity.invoice.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.invoice.ControllerSliceConfig;
import com.positivity.invoice.internal.security.InvoicePermissions;
import com.positivity.invoice.internal.service.DepositCreditService;
import com.positivity.security.common.GatewaySecurityConfig;
import com.positivity.web.common.WebCommonErrorAutoConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * End-to-end proof for issue #1694, exercised through {@link DepositCreditController}: the
 * blanket {@code @ExceptionHandler(IllegalArgumentException.class)} that used to live in {@link
 * DepositCreditExceptionHandler} (422 {@code DEPOSIT_CREDIT_INVALID_ARGUMENT}) is deleted. This
 * module's own {@link com.positivity.invoice.internal.exception.InvoiceRequestValidationException}
 * keeps the genuine-client-error contract, now at 400 (ADR-0017 §1: request-shape/field
 * validation, not a state-dependent domain-policy 422); a bare {@code IllegalArgumentException} —
 * what Hibernate/JPA throw for an invalid query, what {@code UUID.fromString} throws on malformed
 * stored data — is no longer caught by the module advice and falls through to pos-web-common's
 * platform-wide {@code GlobalApiExceptionHandler}, which answers a generic, correlated 500 that
 * never echoes the exception's own text.
 *
 * <p>Runs as a {@code @WebMvcTest} slice wired by {@link com.positivity.invoice.ControllerSliceConfig},
 * whose Javadoc records why this module could not be sliced before #1723. Authentication goes
 * through the gateway's {@code X-User}/{@code X-Authorities} headers rather than
 * {@code @WithMockUser}.
 */
@WebMvcTest(DepositCreditController.class)
@Import({GatewaySecurityConfig.class, WebCommonErrorAutoConfiguration.class, ControllerSliceConfig.class})
@DisplayName("Deposit-credit endpoints answer the errors they document (#1694)")
class DepositCreditControllerErrorHandlingTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DepositCreditService depositCreditService;

    private MockHttpServletRequestBuilder withAuth(MockHttpServletRequestBuilder request, String authorities) {
        return request.header("X-User", "test-user").header("X-Authorities", authorities);
    }

    /**
     * The unknown-sourceType rejection happens in the controller itself (before the service is
     * called), via {@code DepositCreditController#parseSourceType}, which now throws {@link
     * com.positivity.invoice.internal.exception.InvoiceRequestValidationException} instead of a
     * bare {@code IllegalArgumentException}.
     */
    @Test
    @DisplayName("an unknown sourceType answers 400 with its own message and code")
    void unknownSourceTypeAnswers400WithItsOwnMessageAndCode() throws Exception {
        mockMvc.perform(withAuth(
                        get("/v1/invoices/deposits")
                                .param("sourceType", "NOT_A_SOURCE_TYPE")
                                .param("sourceId", UUID.randomUUID().toString()),
                        InvoicePermissions.VIEW))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DEPOSIT_CREDIT_INVALID_ARGUMENT"))
                .andExpect(jsonPath("$.message").value("Unknown deposit source type: NOT_A_SOURCE_TYPE"))
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
        UUID depositCreditId = UUID.randomUUID();
        String leakCanary = "org.hibernate.query.sqm.UnknownPathException: Could not resolve attribute "
                + "'sourceId' of 'com.positivity.invoice.internal.entity.DepositCredit'";
        when(depositCreditService.getDeposit(depositCreditId)).thenThrow(new IllegalArgumentException(leakCanary));

        String body = mockMvc.perform(withAuth(
                        get("/v1/invoices/deposits/{depositCreditId}", depositCreditId), InvoicePermissions.VIEW))
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
