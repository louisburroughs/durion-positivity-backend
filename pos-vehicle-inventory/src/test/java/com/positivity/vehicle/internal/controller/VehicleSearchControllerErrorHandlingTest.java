package com.positivity.vehicle.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.vehicle.config.WebMvcTestSecurityConfig;
import com.positivity.vehicle.internal.exception.VehicleValidationException;
import com.positivity.vehicle.internal.service.VehicleSearchService;
import com.positivity.web.common.WebCommonErrorAutoConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * End-to-end proof for issue #1694, exercised through {@link VehicleSearchController} to avoid a
 * request body (no JSON deserialization concerns to muddy the assertion): {@link
 * VehicleValidationException} keeps the genuine-client-error contract (400 {@code
 * VALIDATION_ERROR}, message echoed), while a bare {@code IllegalArgumentException} — what
 * Hibernate/JPA throw for an invalid query, what {@code UUID.fromString} throws on malformed
 * stored data, or what a JPA attribute converter throws on corrupt stored JSON — is no longer
 * caught by this module's {@link VehicleExceptionHandler}: it falls through to {@code
 * pos-web-common}'s platform-wide {@code GlobalApiExceptionHandler}, which answers a generic,
 * correlated 500 that never echoes the exception's own text.
 *
 * <p>{@code @WebMvcTest} does not auto-register {@code pos-web-common}'s {@code @AutoConfiguration}
 * (it is not on the curated slice-test allowlist), so {@link WebCommonErrorAutoConfiguration} is
 * imported explicitly here to exercise the real fallback chain rather than asserting a weaker
 * substitute. {@link WebMvcTestSecurityConfig} supplies the module's own test authentication
 * (auto-authenticates any request carrying an {@code Authorization} header with the module's full
 * test authority set), matching every other web-slice test in this module.
 */
@WebMvcTest(VehicleSearchController.class)
@Import({WebMvcTestSecurityConfig.class, WebCommonErrorAutoConfiguration.class})
@ActiveProfiles("test")
@SuppressWarnings({"java:S6813", "java:S100", "java:S1192"})
class VehicleSearchControllerErrorHandlingTest {

    private static final String PATH = "/v1/vehicles/search";
    private static final String AUTH = "Authorization";
    private static final String BEARER = "Bearer test";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private VehicleSearchService searchService;

    @Test
    @DisplayName("a VehicleValidationException failure answers 400 with its own message and code")
    void aVehicleValidationExceptionFailureAnswers400WithItsOwnMessageAndCode() throws Exception {
        when(searchService.search(any()))
                .thenThrow(new VehicleValidationException("Query must be at least 3 characters"));

        mockMvc.perform(get(PATH).header(AUTH, BEARER).param("q", "ab"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Query must be at least 3 characters"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(header().exists("X-Correlation-Id"));
    }

    /**
     * The regression this test guards against (issue #1694): a bare {@code
     * IllegalArgumentException} must NOT come back as a 400 carrying its own message. It is an
     * unexpected server-side failure, so it must land on the generic, correlated 500 fallback.
     */
    @Test
    @DisplayName("an unexpected IllegalArgumentException answers 500 without leaking its message")
    void anUnexpectedIllegalArgumentExceptionAnswers500WithoutLeakingItsMessage() throws Exception {
        String leakCanary = "org.hibernate.query.sqm.UnknownPathException: Could not resolve attribute 'vinNormalized' "
                + "of 'com.positivity.vehicle.internal.entity.VehicleRecord'";
        when(searchService.search(any())).thenThrow(new IllegalArgumentException(leakCanary));

        String body = mockMvc.perform(get(PATH).header(AUTH, BEARER).param("q", "1HGCM8"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("Unexpected error occurred"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(header().exists("X-Correlation-Id"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body)
                .doesNotContain(leakCanary)
                .doesNotContain("UnknownPathException")
                .doesNotContain("vinNormalized");
    }
}
