package com.positivity.workorder.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.web.common.WebCommonErrorAutoConfiguration;
import com.positivity.workorder.internal.exception.WorkorderRequestValidationException;
import com.positivity.workorder.internal.service.WorkorderDetailService;
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
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * End-to-end proof for issue #1694, exercised through {@link WorkorderDetailController} to avoid
 * a request body (no JSON deserialization concerns to muddy the assertion): {@link
 * WorkorderRequestValidationException} keeps the genuine-client-error contract (400 {@code
 * INVALID_ARGUMENT}, message echoed), while a bare {@code IllegalArgumentException} — what
 * Hibernate/JPA throw for an invalid query, what {@code UUID.fromString} throws on malformed
 * stored data — is no longer caught by this module's {@link
 * com.positivity.workorder.internal.config.GlobalExceptionHandler}: it falls through to {@code
 * pos-web-common}'s platform-wide {@code GlobalApiExceptionHandler}, which answers a generic,
 * correlated 500 that never echoes the exception's own text.
 *
 * <p>{@code @WebMvcTest} does not auto-register {@code pos-web-common}'s {@code @AutoConfiguration}
 * (it is not on the curated slice-test allowlist — an unrelated {@code @AutoConfiguration} from
 * another artifact is simply not imported by the slice), so {@link WebCommonErrorAutoConfiguration}
 * is imported explicitly here to exercise the real fallback chain rather than asserting a weaker
 * substitute.
 */
@WebMvcTest(WorkorderDetailController.class)
@Import(WebCommonErrorAutoConfiguration.class)
class WorkorderDetailControllerErrorHandlingTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-09-04T12:00:00Z"), ZoneOffset.UTC);
    private static final UUID WORKORDER_ID = UUID.fromString("019200aa-0000-7000-8000-000000000101");
    private static final String URL = "/v1/workorders/{workorderId}/detail";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WorkorderDetailService workorderDetailService;

    @Test
    @WithMockUser
    void aWorkorderRequestValidationFailureAnswers400WithItsOwnMessageAndCode() throws Exception {
        when(workorderDetailService.getWorkorderDetail(any(UUID.class), any()))
                .thenThrow(new WorkorderRequestValidationException("workorderId must reference an existing record"));

        mockMvc.perform(get(URL, WORKORDER_ID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"))
                .andExpect(jsonPath("$.message").value("workorderId must reference an existing record"));
    }

    /**
     * The regression this test guards against (#1694): a bare {@code IllegalArgumentException}
     * must NOT come back as a 400 carrying its own message. It is an unexpected server-side
     * failure, so it must land on the generic, correlated 500 fallback.
     */
    @Test
    @WithMockUser
    void anUnexpectedIllegalArgumentExceptionAnswers500WithoutLeakingItsMessage() throws Exception {
        String leakCanary = "org.hibernate.query.sqm.UnknownPathException: Could not resolve attribute 'mechanicId' "
                + "of 'com.positivity.workorder.internal.entity.TechnicianAssignment'";
        when(workorderDetailService.getWorkorderDetail(any(UUID.class), any()))
                .thenThrow(new IllegalArgumentException(leakCanary));

        String body = mockMvc.perform(get(URL, WORKORDER_ID))
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
                .doesNotContain("mechanicId")
                .contains("correlationId");
    }

    /** Clock for {@code GlobalExceptionHandler} and {@code pos-web-common}'s advice, plus method security. */
    @TestConfiguration
    @EnableMethodSecurity(prePostEnabled = true)
    static class SliceTestConfig {

        @Bean
        Clock clock() {
            return TEST_CLOCK;
        }
    }
}
