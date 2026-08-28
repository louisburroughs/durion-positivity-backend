package com.positivity.marketing.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.marketing.config.TestSecurityConfig;
import com.positivity.marketing.internal.dto.CampaignResponse;
import com.positivity.marketing.internal.service.CampaignSendService;
import com.positivity.marketing.internal.service.CampaignService;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-slice tests for {@link CampaignController}.
 *
 * <p>
 * The campaign lifecycle is split across five separate permissions —
 * {@code schedule}, {@code manage} (pause/resume/cancel), {@code send},
 * {@code create} and {@code edit} — and that split is the whole point of the
 * design: whoever may pause a running campaign is deliberately not whoever may
 * push it to real customers. Nothing about the endpoints themselves enforces
 * that; it lives entirely in one {@code @PreAuthorize} string per method, where
 * a copy-paste between two adjacent lifecycle actions is both easy and
 * invisible. These tests check each action against a neighbouring authority that
 * must <em>not</em> open it.
 */
@WebMvcTest(CampaignController.class)
@Import(TestSecurityConfig.class)
@ActiveProfiles("test")
@SuppressWarnings({"java:S6813", "java:S100", "java:S1192"})
@DisplayName("CampaignController — web slice")
class CampaignControllerWebMvcTest {

    private static final UUID CAMPAIGN_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final String PATH = "/v1/marketing/campaigns";
    private static final String AUTHORITIES = "X-Authorities";
    private static final String VIEW = "marketing:campaign:view";
    private static final String MANAGE = "marketing:campaign:manage";
    private static final String SEND = "marketing:campaign:send";
    private static final String SCHEDULE = "marketing:campaign:schedule";
    private static final String VALID_CREATE_BODY = """
            {"code":"SPRING-FLEET-2026","name":"Spring fleet service",
             "audienceType":"COMMERCIAL","channels":["EMAIL"]}""";

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    java.time.Clock clock;

    @MockitoBean
    CampaignService campaignService;

    @MockitoBean
    CampaignSendService campaignSendService;

    private static CampaignResponse campaign(String status) {
        return new CampaignResponse(
                CAMPAIGN_ID,
                "SPRING-FLEET-2026",
                "Spring fleet service",
                "Seasonal reminder",
                "COMMERCIAL",
                null,
                status,
                Set.of("EMAIL"),
                null,
                null,
                null,
                null,
                null,
                "IMMEDIATE",
                null,
                null,
                null,
                Instant.parse("2026-08-01T00:00:00Z"),
                Instant.parse("2026-08-01T00:00:00Z"));
    }

    @Test
    @DisplayName("GET lists campaigns for a holder of campaign:view")
    void listReturnsCampaigns() throws Exception {
        when(campaignService.list(null, null)).thenReturn(List.of(campaign("DRAFT")));

        mockMvc.perform(get(PATH).header(AUTHORITIES, VIEW))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].campaignId").value(CAMPAIGN_ID.toString()))
                .andExpect(jsonPath("$[0].status").value("DRAFT"));
    }

    @ParameterizedTest(name = "{0} requires {1}, and {2} does not open it")
    @CsvSource({
        "schedule, marketing:campaign:schedule, marketing:campaign:manage",
        "pause,    marketing:campaign:manage,   marketing:campaign:schedule",
        "resume,   marketing:campaign:manage,   marketing:campaign:view",
        "cancel,   marketing:campaign:manage,   marketing:campaign:send",
    })
    @DisplayName("each lifecycle action is gated on its own authority")
    void lifecycleActionsAreGatedIndividually(String action, String required, String insufficient) throws Exception {
        when(campaignService.schedule(CAMPAIGN_ID)).thenReturn(campaign("SCHEDULED"));
        when(campaignService.pause(CAMPAIGN_ID)).thenReturn(campaign("PAUSED"));
        when(campaignService.resume(CAMPAIGN_ID)).thenReturn(campaign("SENDING"));
        when(campaignService.cancel(CAMPAIGN_ID)).thenReturn(campaign("CANCELLED"));

        String url = PATH + "/" + CAMPAIGN_ID + "/" + action;

        mockMvc.perform(post(url).header(AUTHORITIES, required)).andExpect(status().isOk());

        // The negative case is the one that matters. Every one of these authorities is a
        // plausible thing for the same operator to hold, so a mistyped @PreAuthorize would let
        // the action through and no positive test would notice.
        mockMvc.perform(post(url).header(AUTHORITIES, insufficient)).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("sending is gated on campaign:send, which managing a campaign does not grant")
    void sendRequiresItsOwnAuthority() throws Exception {
        when(campaignSendService.dispatch(CAMPAIGN_ID)).thenReturn(42);

        // Dispatch is the only action here that reaches real customers, which is why it has an
        // authority of its own rather than riding on :manage.
        mockMvc.perform(post(PATH + "/" + CAMPAIGN_ID + "/send").header(AUTHORITIES, MANAGE))
                .andExpect(status().isForbidden());
        verify(campaignSendService, never()).dispatch(any());

        mockMvc.perform(post(PATH + "/" + CAMPAIGN_ID + "/send").header(AUTHORITIES, SEND))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.queued").value(42))
                .andExpect(jsonPath("$.campaignId").value(CAMPAIGN_ID.toString()));
    }

    @Test
    @DisplayName("dispatch answers 202, not 200 — the sends are queued, not completed")
    void dispatchIsAccepted() throws Exception {
        when(campaignSendService.dispatch(CAMPAIGN_ID)).thenReturn(0);

        // 202 is the honest status: the endpoint hands work to the send worker and returns. A
        // 200 would tell the caller the messages had gone out.
        mockMvc.perform(post(PATH + "/" + CAMPAIGN_ID + "/send").header(AUTHORITIES, SEND))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.queued").value(0));
    }

    @Test
    @DisplayName("audience preview is a read, gated on campaign:view rather than a send authority")
    void audiencePreviewIsGatedAsARead() throws Exception {
        // Previewing must not require the authority to actually send — checking who would
        // receive a campaign is exactly what someone does before asking for approval.
        mockMvc.perform(post(PATH + "/" + CAMPAIGN_ID + "/audience-preview").header(AUTHORITIES, SEND))
                .andExpect(status().isForbidden());

        verifyNoInteractions(campaignService);
    }

    @Test
    @DisplayName("creating a campaign is refused to a holder of schedule alone")
    void createRequiresCreateAuthority() throws Exception {
        mockMvc.perform(post(PATH)
                        .header(AUTHORITIES, SCHEDULE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CREATE_BODY))
                .andExpect(status().isForbidden());

        verify(campaignService, never()).create(any());
    }

    @Test
    @DisplayName("an invalid body is rejected as 400 before authorization is even considered")
    void bodyValidationRunsBeforeAuthorization() throws Exception {
        // Argument resolution happens before the method-security interceptor, so a caller with
        // no relevant authority at all still gets 400 rather than 403 when the body is
        // malformed. Worth pinning because it is the opposite of what the annotation order on
        // the method suggests, and because it means the shape of a rejection tells an
        // unauthorized caller whether their payload was well-formed.
        mockMvc.perform(post(PATH)
                        .header(AUTHORITIES, VIEW)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"SPRING\"}"))
                .andExpect(status().isBadRequest());

        verify(campaignService, never()).create(any());
    }
}
