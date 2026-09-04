package com.positivity.customer.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.customer.config.WebMvcTestSecurityConfig;
import com.positivity.customer.internal.config.CrmExceptionHandler;
import com.positivity.customer.internal.exception.CrmValidationException;
import com.positivity.customer.internal.service.AccountTierService;
import com.positivity.customer.internal.service.PartyService;
import com.positivity.web.common.WebCommonErrorAutoConfiguration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * End-to-end proof for issue #1694, exercised through {@link CrmAccountsController#checkPartyDuplicates}
 * to avoid a request body (no JSON deserialization concerns to muddy the assertion): a module
 * validation failure ({@link CrmValidationException}) keeps the genuine-client-error contract
 * (400 {@code VALIDATION_ERROR}, message echoed), while a bare {@code IllegalArgumentException} —
 * what Hibernate/JPA throw for an invalid query, what {@code UUID.fromString} throws on malformed
 * stored data — is no longer caught by this module's {@link CrmExceptionHandler}: it falls
 * through to pos-web-common's platform-wide {@code GlobalApiExceptionHandler}, which answers a
 * generic, correlated 500 that never echoes the exception's own text.
 *
 * <p>{@code @WebMvcTest} does not auto-register pos-web-common's {@code @AutoConfiguration} (it is
 * not on the curated slice-test allowlist), so {@link WebCommonErrorAutoConfiguration} is imported
 * explicitly here to exercise the real fallback chain rather than asserting a weaker substitute.
 */
@WebMvcTest(CrmAccountsController.class)
@Import({WebMvcTestSecurityConfig.class, CrmExceptionHandler.class, WebCommonErrorAutoConfiguration.class})
@ActiveProfiles("test")
class CrmAccountsControllerErrorHandlingTest {

    private static final Instant NOW = Instant.parse("2026-09-04T12:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PartyService partyService;

    @MockitoBean
    private AccountTierService accountTierService;

    @MockitoBean
    private Clock clock;

    @BeforeEach
    void setUpClock() {
        lenient().when(clock.instant()).thenReturn(NOW);
        lenient().when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    }

    @Test
    @DisplayName("a module validation failure still answers 400 VALIDATION_ERROR with its own message")
    void aModuleValidationFailureAnswers400WithItsOwnMessageAndCode() throws Exception {
        // " A " passes the framework's @Size(min = 2) on the raw parameter but trims to a
        // single character, which is what makes the service raise its own validation error.
        when(partyService.checkPartyDuplicates(eq(" A ")))
                .thenThrow(new CrmValidationException("legalName must contain at least 2 non-whitespace characters"));

        mockMvc.perform(get("/v1/crm/accounts/parties/duplicate-check")
                        .param("legalName", " A ")
                        .header("X-Authorities", "crm:party:search"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("legalName must contain at least 2 non-whitespace characters"));
    }

    /**
     * The regression this test guards against (#1694): a bare {@code IllegalArgumentException}
     * must NOT come back as a 400 carrying its own message. It is an unexpected server-side
     * failure, so it must land on the generic, correlated 500 fallback.
     */
    @Test
    @DisplayName("an unexpected IllegalArgumentException answers 500 without leaking its message")
    void anUnexpectedIllegalArgumentExceptionAnswers500WithoutLeakingItsMessage() throws Exception {
        String leakCanary = "org.hibernate.query.sqm.UnknownPathException: Could not resolve attribute 'legalName' of "
                + "'com.positivity.customer.internal.entity.CommercialParty'";
        when(partyService.checkPartyDuplicates(eq("Acme"))).thenThrow(new IllegalArgumentException(leakCanary));

        String body = mockMvc.perform(get("/v1/crm/accounts/parties/duplicate-check")
                        .param("legalName", "Acme")
                        .header("X-Authorities", "crm:party:search"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("Unexpected error occurred"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body)
                .doesNotContain(leakCanary)
                .doesNotContain("UnknownPathException")
                .doesNotContain("legalName");
    }

    /**
     * Any other unexpected {@code RuntimeException} — not just {@code IllegalArgumentException}
     * — must land on the same generic 500, never echoing its own message either.
     */
    @Test
    @DisplayName("any other unexpected RuntimeException also answers 500 without leaking its message")
    void anyOtherUnexpectedRuntimeExceptionAlsoAnswers500WithoutLeakingItsMessage() throws Exception {
        String leakCanary = "duplicate key value violates unique constraint \"commercial_party_legal_name_key\"";
        when(partyService.checkPartyDuplicates(eq("Acme"))).thenThrow(new IllegalStateException(leakCanary));

        String body = mockMvc.perform(get("/v1/crm/accounts/parties/duplicate-check")
                        .param("legalName", "Acme")
                        .header("X-Authorities", "crm:party:search"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("Unexpected error occurred"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).doesNotContain(leakCanary).doesNotContain("commercial_party_legal_name_key");
    }
}
