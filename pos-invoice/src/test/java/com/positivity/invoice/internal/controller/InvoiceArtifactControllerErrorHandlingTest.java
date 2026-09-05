package com.positivity.invoice.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.invoice.ControllerSliceConfig;
import com.positivity.invoice.internal.exception.InvoiceRequestValidationException;
import com.positivity.invoice.internal.service.InvoiceArtifactService;
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
 * End-to-end proof for issue #1694, exercised through {@link InvoiceArtifactController}: the
 * blanket {@code @ExceptionHandler(IllegalArgumentException.class)} that used to live in {@link
 * InvoiceArtifactExceptionHandler} (400 {@code VALIDATION_ERROR}) is deleted. {@link
 * InvoiceRequestValidationException} — thrown only by {@code ArtifactRef.decode}'s own validation
 * of a caller-supplied {@code artifactRefId} — keeps the genuine-client-error contract at the
 * same 400; a bare {@code IllegalArgumentException} (what Hibernate/JPA throw for an invalid
 * query, what {@code UUID.fromString} throws on malformed stored data) is no longer caught by the
 * module advice and falls through to pos-web-common's platform-wide {@code
 * GlobalApiExceptionHandler}, which answers a generic, correlated 500 that never echoes the
 * exception's own text.
 *
 * <p>A {@code @WebMvcTest} slice does not work for this module: {@code PosInvoiceApplication}
 * declares {@code @EnableJpaRepositories} directly, which a web slice cannot exclude and which
 * then fails to find an {@code entityManagerFactory} bean. So this follows the module's existing
 * full-context pattern (see {@code OrderInvoiceContractBehaviorIT}) instead, authenticating via
 * the gateway's {@code X-User}/{@code X-Authorities} headers rather than {@code @WithMockUser}.
 */
@WebMvcTest(InvoiceArtifactController.class)
@Import({GatewaySecurityConfig.class, WebCommonErrorAutoConfiguration.class, ControllerSliceConfig.class})
@DisplayName("Invoice-artifact endpoints answer the errors they document (#1694)")
class InvoiceArtifactControllerErrorHandlingTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private InvoiceArtifactService artifactService;

    private MockHttpServletRequestBuilder withAuth(MockHttpServletRequestBuilder request) {
        return request.header("X-User", "test-user").header("X-Authorities", "*");
    }

    @Test
    @DisplayName("a malformed artifactRefId answers 400 with its own message and code")
    void aMalformedArtifactRefIdAnswers400WithItsOwnMessageAndCode() throws Exception {
        UUID invoiceId = UUID.randomUUID();
        when(artifactService.createDownloadToken(eq(invoiceId), any()))
                .thenThrow(new InvoiceRequestValidationException("Malformed artifactRefId"));

        mockMvc.perform(withAuth(
                        post("/v1/invoices/{invoiceId}/artifacts/{artifactRefId}/download-token", invoiceId, "!!!")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Malformed artifactRefId"))
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
        UUID invoiceId = UUID.randomUUID();
        String leakCanary = "org.hibernate.query.sqm.UnknownPathException: Could not resolve attribute "
                + "'artifactRefId' of 'com.positivity.invoice.internal.entity.Invoice'";
        when(artifactService.createDownloadToken(eq(invoiceId), any()))
                .thenThrow(new IllegalArgumentException(leakCanary));

        String body = mockMvc.perform(withAuth(
                        post("/v1/invoices/{invoiceId}/artifacts/{artifactRefId}/download-token", invoiceId, "abc")))
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
