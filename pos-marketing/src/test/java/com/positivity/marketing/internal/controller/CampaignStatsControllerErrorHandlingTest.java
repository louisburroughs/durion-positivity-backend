package com.positivity.marketing.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.marketing.config.TestSecurityConfig;
import com.positivity.marketing.internal.exception.MarketingResourceNotFoundException;
import com.positivity.marketing.internal.service.CampaignStatsService;
import com.positivity.web.common.WebCommonErrorAutoConfiguration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
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
 * End-to-end proof for issue #1694, exercised through {@link CampaignStatsController} to avoid a
 * request body (no JSON deserialization concerns to muddy the assertion): a genuine module error
 * ({@link MarketingResourceNotFoundException}) keeps its documented contract (404 {@code
 * RESOURCE_NOT_FOUND}, message echoed), while a bare {@code IllegalArgumentException} — what
 * Hibernate/JPA throw for an invalid query, what {@code UUID.fromString} throws on malformed
 * stored data — or any other unexpected {@code RuntimeException} is no longer caught by this
 * module's {@link com.positivity.marketing.internal.config.MarketingExceptionHandler}: it falls
 * through to {@code pos-web-common}'s platform-wide {@code GlobalApiExceptionHandler}, which
 * answers a generic, correlated 500 that never echoes the exception's own text.
 *
 * <p>Before this issue, pos-marketing had zero {@code new IllegalArgumentException} throw sites
 * of its own — the module's blanket {@code @ExceptionHandler(IllegalArgumentException.class)}
 * existed purely to catch faults from elsewhere (Hibernate, {@code UUID.fromString}, JDK library
 * calls) and misreport them as 400s. Deleting it is therefore the whole fix for this module; this
 * test pins the resulting behavior.
 *
 * <p>{@code @WebMvcTest} does not auto-register {@code pos-web-common}'s {@code
 * @AutoConfiguration} (it is not on the curated slice-test allowlist — an unrelated {@code
 * @AutoConfiguration} from another artifact is simply not imported by the slice), so {@link
 * WebCommonErrorAutoConfiguration} is imported explicitly here to exercise the real fallback
 * chain rather than asserting a weaker substitute.
 */
@WebMvcTest(CampaignStatsController.class)
@Import({
    TestSecurityConfig.class,
    WebCommonErrorAutoConfiguration.class,
    CampaignStatsControllerErrorHandlingTest.SliceTestConfig.class
})
@ActiveProfiles("test")
@SuppressWarnings({"java:S6813", "java:S100"})
@DisplayName("CampaignStatsController — bare IllegalArgumentException no longer answers 400 (#1694)")
class CampaignStatsControllerErrorHandlingTest {

    private static final UUID CAMPAIGN_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final String PATH = "/v1/marketing/campaigns/" + CAMPAIGN_ID + "/stats";
    private static final String AUTHORITIES = "X-Authorities";
    private static final String STATS_VIEW = "marketing:stats:view";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CampaignStatsService campaignStatsService;

    @Test
    @DisplayName("a missing campaign still answers its documented 404 RESOURCE_NOT_FOUND with its own message")
    void aResourceNotFoundFailureAnswers404WithItsOwnMessageAndCode() throws Exception {
        when(campaignStatsService.campaignStats(any()))
                .thenThrow(new MarketingResourceNotFoundException("Campaign", CAMPAIGN_ID));

        mockMvc.perform(get(PATH).header(AUTHORITIES, STATS_VIEW))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Campaign not found: " + CAMPAIGN_ID))
                .andExpect(jsonPath("$.correlationId").exists());
    }

    /**
     * The regression this test guards against (#1694): a bare {@code IllegalArgumentException}
     * must NOT come back as a 400 carrying its own message. It is an unexpected server-side
     * failure, so it must land on the generic, correlated 500 fallback.
     */
    @Test
    @DisplayName("an unexpected IllegalArgumentException answers 500 without leaking its message")
    void anUnexpectedIllegalArgumentExceptionAnswers500WithoutLeakingItsMessage() throws Exception {
        String leakCanary = "org.hibernate.query.sqm.UnknownPathException: Could not resolve attribute "
                + "'campaignProgramId' of 'com.positivity.marketing.internal.entity.Campaign'";
        when(campaignStatsService.campaignStats(any())).thenThrow(new IllegalArgumentException(leakCanary));

        String body = mockMvc.perform(get(PATH).header(AUTHORITIES, STATS_VIEW))
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
                .doesNotContain("campaignProgramId");
    }

    /**
     * Any other unexpected {@code RuntimeException} — not just {@code IllegalArgumentException}
     * — must land on the same generic 500, never echoing its own message either.
     */
    @Test
    @DisplayName("any other unexpected RuntimeException also answers 500 without leaking its message")
    void anyOtherUnexpectedRuntimeExceptionAlsoAnswers500WithoutLeakingItsMessage() throws Exception {
        String leakCanary = "duplicate key value violates unique constraint \"campaign_pkey\"";
        when(campaignStatsService.campaignStats(any())).thenThrow(new IllegalStateException(leakCanary));

        String body = mockMvc.perform(get(PATH).header(AUTHORITIES, STATS_VIEW))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("Unexpected error occurred"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).doesNotContain(leakCanary).doesNotContain("campaign_pkey");
    }

    /** Clock for {@code MarketingExceptionHandler} and {@code pos-web-common}'s advice. */
    @TestConfiguration
    static class SliceTestConfig {

        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-09-04T12:00:00Z"), ZoneOffset.UTC);
        }
    }
}
