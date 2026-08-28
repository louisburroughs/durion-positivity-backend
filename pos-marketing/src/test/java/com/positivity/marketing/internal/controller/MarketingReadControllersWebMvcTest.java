package com.positivity.marketing.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.marketing.config.TestSecurityConfig;
import com.positivity.marketing.internal.dto.MessageTemplateResponse;
import com.positivity.marketing.internal.service.CampaignStatsService;
import com.positivity.marketing.internal.service.MessageTemplateService;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-slice tests for {@link MessageTemplateController} and
 * {@link CampaignStatsController}.
 *
 * <p>
 * Templates split into {@code template:view} and {@code template:manage}, and
 * the split matters more than it looks: a message template is the text that goes
 * out to customers under the company's name, so editing one is a content-
 * publishing act, while reading one is not. Statistics sit behind their own
 * {@code stats:view} rather than riding on {@code campaign:view}, because
 * campaign statistics are per-recipient delivery outcomes — who opened, who
 * bounced, who complained — and that is a narrower audience than whoever may
 * look at a campaign's configuration.
 */
@WebMvcTest({MessageTemplateController.class, CampaignStatsController.class})
@Import(TestSecurityConfig.class)
@ActiveProfiles("test")
@SuppressWarnings({"java:S6813", "java:S100", "java:S1192"})
@DisplayName("Marketing template and stats controllers — web slice")
class MarketingReadControllersWebMvcTest {

    private static final UUID TEMPLATE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID CAMPAIGN_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final String TEMPLATES = "/v1/marketing/templates";
    private static final String AUTHORITIES = "X-Authorities";
    private static final String TEMPLATE_VIEW = "marketing:template:view";
    private static final String TEMPLATE_MANAGE = "marketing:template:manage";
    private static final String STATS_VIEW = "marketing:stats:view";
    private static final String CAMPAIGN_VIEW = "marketing:campaign:view";
    private static final String VALID_TEMPLATE_BODY = """
            {"name":"Spring reminder","channel":"EMAIL","audienceType":"COMMERCIAL",
             "subject":"Time for your spring service","body":"Hello {{firstName}}"}""";

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    java.time.Clock clock;

    @MockitoBean
    MessageTemplateService messageTemplateService;

    @MockitoBean
    CampaignStatsService campaignStatsService;

    private static MessageTemplateResponse template() {
        return new MessageTemplateResponse(
                TEMPLATE_ID,
                "Spring reminder",
                "EMAIL",
                "COMMERCIAL",
                "Time for your spring service",
                "Hello {{firstName}}",
                Set.of("firstName"),
                Instant.parse("2026-08-01T00:00:00Z"),
                Instant.parse("2026-08-01T00:00:00Z"));
    }

    @Test
    @DisplayName("GET lists templates for a holder of template:view")
    void listTemplates() throws Exception {
        when(messageTemplateService.list(null, null)).thenReturn(List.of(template()));

        mockMvc.perform(get(TEMPLATES).header(AUTHORITIES, TEMPLATE_VIEW))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].templateId").value(TEMPLATE_ID.toString()))
                .andExpect(jsonPath("$[0].availableTokens[0]").value("firstName"));
    }

    @Test
    @DisplayName("GET one template exposes the token list a caller needs to fill it in")
    void getTemplate() throws Exception {
        when(messageTemplateService.get(TEMPLATE_ID)).thenReturn(template());

        mockMvc.perform(get(TEMPLATES + "/" + TEMPLATE_ID).header(AUTHORITIES, TEMPLATE_VIEW))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value("Time for your spring service"))
                .andExpect(jsonPath("$.body").value("Hello {{firstName}}"));
    }

    @Test
    @DisplayName("editing a template needs template:manage — reading it is not enough")
    void editingRequiresManage() throws Exception {
        // Changing a template changes what real customers receive, under the company's name.
        // Read access to the template library must not carry that.
        mockMvc.perform(post(TEMPLATES)
                        .header(AUTHORITIES, TEMPLATE_VIEW)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_TEMPLATE_BODY))
                .andExpect(status().isForbidden());

        verify(messageTemplateService, never()).create(any());
    }

    @Test
    @DisplayName("deleting a template needs template:manage and answers 204")
    void deleteRequiresManage() throws Exception {
        mockMvc.perform(delete(TEMPLATES + "/" + TEMPLATE_ID).header(AUTHORITIES, TEMPLATE_VIEW))
                .andExpect(status().isForbidden());
        verify(messageTemplateService, never()).delete(any());

        mockMvc.perform(delete(TEMPLATES + "/" + TEMPLATE_ID).header(AUTHORITIES, TEMPLATE_MANAGE))
                .andExpect(status().isNoContent());
        verify(messageTemplateService).delete(TEMPLATE_ID);
    }

    @Test
    @DisplayName("campaign statistics need stats:view, which campaign:view does not grant")
    void statsAreGatedSeparatelyFromCampaigns() throws Exception {
        // Delivery outcomes are per-recipient: who opened, who bounced, who complained. Being
        // allowed to read a campaign's configuration is not the same as being allowed to read
        // that, so the two permissions are deliberately distinct.
        mockMvc.perform(get("/v1/marketing/campaigns/" + CAMPAIGN_ID + "/stats").header(AUTHORITIES, CAMPAIGN_VIEW))
                .andExpect(status().isForbidden());

        verifyNoInteractions(campaignStatsService);
    }

    @Test
    @DisplayName("program statistics are gated on the same stats:view")
    void programStatsUseTheSameAuthority() throws Exception {
        mockMvc.perform(get("/v1/marketing/programs/" + CAMPAIGN_ID + "/stats").header(AUTHORITIES, TEMPLATE_VIEW))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/v1/marketing/programs/" + CAMPAIGN_ID + "/stats").header(AUTHORITIES, STATS_VIEW))
                .andExpect(status().isOk());

        verify(campaignStatsService).programStats(CAMPAIGN_ID);
    }
}
