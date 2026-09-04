package com.positivity.people.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.people.config.TestSecurityConfig;
import com.positivity.people.internal.exception.RequestValidationException;
import com.positivity.people.internal.service.TimeEntryExceptionService;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * End-to-end proof for issue #1694, exercised through {@link TimeEntryExceptionController} to
 * avoid a request body (no JSON deserialization concerns to muddy the assertion): {@link
 * RequestValidationException} keeps the genuine-client-error contract (400 {@code
 * VALIDATION_ERROR}, message echoed, correlation id in body and header), while a bare {@code
 * IllegalArgumentException} — what Hibernate/JPA throw for an invalid query, what {@code
 * UUID.fromString} throws on malformed stored data — is no longer caught by this module's {@link
 * PeopleExceptionHandler}: it falls through to {@code pos-web-common}'s platform-wide {@code
 * GlobalApiExceptionHandler}, which answers a generic, correlated 500 that never echoes the
 * exception's own text.
 *
 * <p>{@code @WebMvcTest} does not auto-register {@code pos-web-common}'s {@code
 * @AutoConfiguration} (it is not on the curated slice-test allowlist — an unrelated {@code
 * @AutoConfiguration} from another artifact is simply not imported by the slice), so {@link
 * WebCommonErrorAutoConfiguration} is imported explicitly here to exercise the real fallback
 * chain rather than asserting a weaker substitute.
 */
@WebMvcTest(TimeEntryExceptionController.class)
@Import({TestSecurityConfig.class, WebCommonErrorAutoConfiguration.class})
@ActiveProfiles("test")
class TimeEntryExceptionControllerErrorHandlingTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-09-04T12:00:00Z"), ZoneOffset.UTC);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TimeEntryExceptionService exceptionService;

    @Test
    void aRequestValidationFailureAnswers400WithItsOwnMessageAndCode() throws Exception {
        when(exceptionService.listByEmployee(any()))
                .thenThrow(new RequestValidationException("employeeId must be a well-formed identifier"));

        mockMvc.perform(get("/v1/people/exceptions").header("X-Authorities", "people:timeException:view"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("employeeId must be a well-formed identifier"));
    }

    /**
     * The regression this test guards against (issue #1694): a bare {@code
     * IllegalArgumentException} must NOT come back as a 400 carrying its own message. It is an
     * unexpected server-side failure, so it must land on the generic, correlated 500 fallback.
     */
    @Test
    void anUnexpectedIllegalArgumentExceptionAnswers500WithoutLeakingItsMessage() throws Exception {
        String leakCanary = "org.hibernate.query.sqm.UnknownPathException: Could not resolve attribute 'employeeId' of "
                + "'com.positivity.people.internal.entity.TimeEntryException'";
        when(exceptionService.listByEmployee(any())).thenThrow(new IllegalArgumentException(leakCanary));

        String body = mockMvc.perform(get("/v1/people/exceptions").header("X-Authorities", "people:timeException:view"))
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
                .doesNotContain("employeeId");
    }

    @TestConfiguration
    static class SliceTestConfig {

        @Bean
        Clock clock() {
            return TEST_CLOCK;
        }
    }
}
