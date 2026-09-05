package com.positivity.bulkloader.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.bulkloader.config.TestSecurityConfig;
import com.positivity.bulkloader.internal.service.BulkLoadJobService;
import com.positivity.web.common.WebCommonErrorAutoConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * End-to-end proof for issue #1694, exercised through {@link BulkLoadJobController}: an
 * {@code IllegalStateException} raised by this module's own service (its pre-existing conflict
 * guard, e.g. "job cannot be transitioned...") keeps its genuine-conflict contract (409, message
 * echoed, correlation id in both body and header), while a bare {@code IllegalArgumentException}
 * — what Hibernate/JPA throw for an invalid query, what {@code UUID.fromString} throws on
 * malformed stored data — is no longer caught by this module's {@link BulkLoaderExceptionHandler}:
 * it falls through to {@code pos-web-common}'s platform-wide {@code GlobalApiExceptionHandler},
 * which answers a generic, correlated 500 that never echoes the exception's own text.
 *
 * <p>{@code @WebMvcTest} does not auto-register {@code pos-web-common}'s {@code @AutoConfiguration}
 * (it is not on the curated slice-test allowlist), so {@link WebCommonErrorAutoConfiguration} is
 * imported explicitly here to exercise the real fallback chain rather than a weaker substitute.
 * {@code pos-bulk-loader}'s {@code PosBlkLoaderApplication} does not declare
 * {@code @EnableJpaRepositories} directly, so the plain {@code @WebMvcTest} slice (as already used
 * by {@link BulkLoadJobControllerTest}) works without falling back to a full-context test base.
 */
@WebMvcTest(BulkLoadJobController.class)
@Import({TestSecurityConfig.class, WebCommonErrorAutoConfiguration.class})
@ActiveProfiles("test")
@SuppressWarnings({"java:S6813", "java:S100", "java:S1192"})
class BulkLoadJobControllerErrorHandlingTest {

    private static final UUID JOB_ID = UUID.fromString("00000000-0000-0000-0000-000000000099");

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    BulkLoadJobService bulkLoadJobService;

    @Test
    @WithMockUser(authorities = "bulkImport:status:read")
    void aModuleConflictGuardAnswers409WithItsOwnMessageAndCorrelationId() throws Exception {
        when(bulkLoadJobService.getJob(eq(JOB_ID), any()))
                .thenThrow(new IllegalStateException("Job cannot be processed before a locationId is assigned"));

        // #1716: the ApiError envelope (code/message/status/timestamp/correlationId), not a bare
        // ProblemDetail. ADR-0017 §3 makes ApiError the contract for every non-2xx body.
        mockMvc.perform(get("/v1/bulk-jobs/{jobId}", JOB_ID).header("X-Correlation-Id", "corr-conflict-1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BULK_JOB_INVALID_STATE"))
                .andExpect(jsonPath("$.message").value("Job cannot be processed before a locationId is assigned"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.correlationId").value("corr-conflict-1"))
                .andExpect(jsonPath("$.detail").doesNotExist())
                .andExpect(header().string("X-Correlation-Id", "corr-conflict-1"));
    }

    /**
     * The regression this test guards against (#1694): a bare {@code IllegalArgumentException}
     * must NOT come back as a 400 carrying its own message. It is an unexpected server-side
     * failure, so it must land on the generic, correlated 500 fallback.
     */
    @Test
    @WithMockUser(authorities = "bulkImport:status:read")
    void anUnexpectedIllegalArgumentExceptionAnswers500WithoutLeakingItsMessage() throws Exception {
        String leakCanary = "org.hibernate.query.sqm.UnknownPathException: Could not resolve attribute 'jobId' of "
                + "'com.positivity.bulkloader.internal.entity.BulkLoadJob'";
        when(bulkLoadJobService.getJob(eq(JOB_ID), any())).thenThrow(new IllegalArgumentException(leakCanary));

        String body = mockMvc.perform(get("/v1/bulk-jobs/{jobId}", JOB_ID))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("Unexpected error occurred"))
                .andExpect(jsonPath("$.correlationId").exists())
                .andExpect(header().exists("X-Correlation-Id"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body)
                .doesNotContain(leakCanary)
                .doesNotContain("UnknownPathException")
                .doesNotContain("jobId");
    }
}
