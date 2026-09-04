package com.positivity.supplier.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.supplier.internal.config.SecurityConfig;
import com.positivity.supplier.internal.exception.SupplierValidationException;
import com.positivity.supplier.internal.security.SupplierPermissions;
import com.positivity.supplier.internal.service.SupplierExchangeAuditService;
import com.positivity.web.common.WebCommonErrorAutoConfiguration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * End-to-end proof for #1694, exercised through {@link SupplierExchangeAuditController} to avoid
 * a request body (no JSON deserialization concerns to muddy the assertion): {@link
 * SupplierValidationException} keeps the genuine-client-error contract (400 {@code
 * VALIDATION_ERROR}, own message and code), while a bare {@code IllegalArgumentException} — what
 * Hibernate/JPA throw for an invalid query, what {@code UUID.fromString} throws on malformed
 * stored data — or any other unexpected {@code RuntimeException} is no longer caught by this
 * module's {@link com.positivity.supplier.internal.exception.SupplierExceptionHandler}: it falls
 * through to {@code pos-web-common}'s platform-wide {@code GlobalApiExceptionHandler}, which
 * answers a generic, correlated 500 that never echoes the exception's own text.
 *
 * <p>{@code @WebMvcTest} does not auto-register {@code pos-web-common}'s {@code
 * @AutoConfiguration} (it is not on the curated slice-test allowlist), so {@link
 * WebCommonErrorAutoConfiguration} is imported explicitly here to exercise the real fallback
 * chain rather than asserting a weaker substitute.
 */
@WebMvcTest(controllers = SupplierExchangeAuditController.class)
@Import({
    SecurityConfig.class,
    WebCommonErrorAutoConfiguration.class,
    SupplierExchangeAuditControllerErrorHandlingTest.FixedClockConfig.class
})
class SupplierExchangeAuditControllerErrorHandlingTest {

    /** Same shape as the other web-mvc tests on this controller: the advice needs a Clock, and
     * the gateway filter must run inside the security chain rather than ahead of it. */
    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-09-04T12:00:00Z"), ZoneOffset.UTC);
        }

        @Bean
        org.springframework.boot.web.servlet.FilterRegistrationBean<
                        com.positivity.security.common.GatewayAuthoritiesFilter>
                gatewayAuthoritiesFilterRegistration(
                        com.positivity.security.common.GatewayAuthoritiesFilter gatewayAuthoritiesFilter) {
            var registration =
                    new org.springframework.boot.web.servlet.FilterRegistrationBean<>(gatewayAuthoritiesFilter);
            registration.setEnabled(false);
            return registration;
        }
    }

    private static final String BASE = "/v1/supplier/admin/audit";
    private static final UUID PROFILE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b");
    private static final String WINDOW =
            "?vendorProfileId=" + PROFILE_ID + "&from=2026-08-01T00:00:00Z" + "&to=2026-09-01T00:00:00Z";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SupplierExchangeAuditService auditService;

    private static MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder, String... authorities) {
        return builder.header("X-User", "web-mvc-tester").header("X-Authorities", String.join(",", authorities));
    }

    @Test
    void aSupplierValidationFailureAnswers400WithItsOwnCodeAndMessage() throws Exception {
        when(auditService.listExchanges(any(), any(), any(), any(), anyInt(), anyInt()))
                .thenThrow(new SupplierValidationException(
                        SupplierValidationException.AUDIT_WINDOW_INVALID, "window end must be after its start"));

        mockMvc.perform(authed(get(BASE + "/exchanges" + WINDOW), SupplierPermissions.AUDIT_READ))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(SupplierValidationException.AUDIT_WINDOW_INVALID))
                .andExpect(jsonPath("$.message").value("window end must be after its start"));
    }

    /**
     * The regression this test guards against (#1694): a bare {@code IllegalArgumentException}
     * must NOT come back as a 400 carrying its own message. It is an unexpected server-side
     * failure, so it must land on the generic, correlated 500 fallback.
     */
    @Test
    void anUnexpectedIllegalArgumentExceptionAnswers500WithoutLeakingItsMessage() throws Exception {
        String leakCanary = "org.hibernate.query.sqm.UnknownPathException: Could not resolve attribute"
                + " 'vendorProfileId' of 'com.positivity.supplier.internal.entity.SupplierExchangeAuditEntity'";
        when(auditService.listExchanges(any(), any(), any(), any(), anyInt(), anyInt()))
                .thenThrow(new IllegalArgumentException(leakCanary));

        String body = mockMvc.perform(authed(get(BASE + "/exchanges" + WINDOW), SupplierPermissions.AUDIT_READ))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("Unexpected error occurred"))
                .andExpect(jsonPath("$.correlationId").exists())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body)
                .doesNotContain(leakCanary)
                .doesNotContain("UnknownPathException")
                .doesNotContain("vendorProfileId");
    }

    /**
     * Any other unexpected {@code RuntimeException} — not just {@code IllegalArgumentException}
     * — must land on the same generic 500, never echoing its own message either.
     */
    @Test
    void anyOtherUnexpectedRuntimeExceptionAlsoAnswers500WithoutLeakingItsMessage() throws Exception {
        String leakCanary = "duplicate key value violates unique constraint \"supplier_exchange_audit_pkey\"";
        when(auditService.listExchanges(any(), any(), any(), any(), anyInt(), anyInt()))
                .thenThrow(new IllegalStateException(leakCanary));

        String body = mockMvc.perform(authed(get(BASE + "/exchanges" + WINDOW), SupplierPermissions.AUDIT_READ))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("Unexpected error occurred"))
                .andExpect(jsonPath("$.correlationId").exists())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).doesNotContain(leakCanary).doesNotContain("supplier_exchange_audit_pkey");
    }
}
