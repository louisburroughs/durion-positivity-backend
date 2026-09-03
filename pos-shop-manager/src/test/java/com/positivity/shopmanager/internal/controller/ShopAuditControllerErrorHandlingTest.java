package com.positivity.shopmanager.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.shopmanager.internal.exception.ShopManagerValidationException;
import com.positivity.shopmanager.internal.service.ShopAuditService;
import com.positivity.web.common.WebCommonErrorAutoConfiguration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * End-to-end proof for issue #1686/#1679, exercised through {@link ShopAuditController} to avoid
 * a request body (no JSON deserialization concerns to muddy the assertion): {@link
 * ShopManagerValidationException} keeps the genuine-client-error contract (400 {@code
 * INVALID_REQUEST}, message echoed), while a bare {@code IllegalArgumentException} — what
 * Hibernate/JPA throw for an invalid query, what {@code UUID.fromString} throws on malformed
 * stored data (#1679's actual failure) — or any other unexpected {@code RuntimeException} is no
 * longer caught by this module's {@link GlobalExceptionHandler}: it falls through to {@code
 * pos-web-common}'s platform-wide {@code GlobalApiExceptionHandler}, which answers a generic,
 * correlated 500 that never echoes the exception's own text.
 *
 * <p>{@code @WebMvcTest} does not auto-register {@code pos-web-common}'s {@code @AutoConfiguration}
 * (it is not on the curated slice-test allowlist — an unrelated {@code @AutoConfiguration} from
 * another artifact is simply not imported by the slice), so {@link WebCommonErrorAutoConfiguration}
 * is imported explicitly here to exercise the real fallback chain rather than asserting a weaker
 * substitute.
 */
@WebMvcTest(ShopAuditController.class)
@Import(WebCommonErrorAutoConfiguration.class)
class ShopAuditControllerErrorHandlingTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-09-03T12:00:00Z"), ZoneOffset.UTC);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ShopAuditService shopAuditService;

    @Test
    @WithMockUser(authorities = "shop:schedule:view")
    void aShopManagerValidationFailureAnswers400WithItsOwnMessageAndCode() throws Exception {
        when(shopAuditService.search(any()))
                .thenThrow(new ShopManagerValidationException("At least one filter criterion is required"));

        mockMvc.perform(get("/v1/shop/audit"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("At least one filter criterion is required"));
    }

    /**
     * The regression this test guards against (#1679): a bare {@code IllegalArgumentException}
     * must NOT come back as a 400 carrying its own message. It is an unexpected server-side
     * failure, so it must land on the generic, correlated 500 fallback.
     */
    @Test
    @WithMockUser(authorities = "shop:schedule:view")
    void anUnexpectedIllegalArgumentExceptionAnswers500WithoutLeakingItsMessage() throws Exception {
        String leakCanary = "org.hibernate.query.sqm.UnknownPathException: Could not resolve attribute 'mechanicId' of "
                + "'com.positivity.shopmanager.internal.entity.MechanicSkill'";
        when(shopAuditService.search(any())).thenThrow(new IllegalArgumentException(leakCanary));

        String body = mockMvc.perform(get("/v1/shop/audit").param("workorderId", "WO-123"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("Unexpected error occurred"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body)
                .doesNotContain(leakCanary)
                .doesNotContain("UnknownPathException")
                .doesNotContain("mechanicId");
    }

    /**
     * Any other unexpected {@code RuntimeException} — not just {@code IllegalArgumentException}
     * — must land on the same generic 500, never echoing its own message either.
     */
    @Test
    @WithMockUser(authorities = "shop:schedule:view")
    void anyOtherUnexpectedRuntimeExceptionAlsoAnswers500WithoutLeakingItsMessage() throws Exception {
        String leakCanary = "duplicate key value violates unique constraint \"shop_audit_entry_pkey\"";
        when(shopAuditService.search(any())).thenThrow(new IllegalStateException(leakCanary));

        String body = mockMvc.perform(get("/v1/shop/audit").param("workorderId", "WO-123"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("Unexpected error occurred"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).doesNotContain(leakCanary).doesNotContain("shop_audit_entry_pkey");
    }

    /** Clock for {@link GlobalExceptionHandler} and {@code pos-web-common}'s advice, plus method security. */
    @TestConfiguration
    @EnableMethodSecurity(prePostEnabled = true)
    static class SliceTestConfig {

        @Bean
        Clock clock() {
            return TEST_CLOCK;
        }
    }
}
