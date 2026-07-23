package com.positivity.inventory.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.inventory.config.TestSecurityConfig;
import com.positivity.inventory.internal.dto.sourcing.SourcingStrategyConfigResponse;
import com.positivity.inventory.internal.enums.SourcingScopeType;
import com.positivity.inventory.internal.enums.SourcingStrategy;
import com.positivity.inventory.service.SourcingStrategyConfigService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-slice tests for SourcingStrategyController (odoo-parity H1, #1037):
 * {@code inventory:location:admin} permission gating and request/response
 * mapping of the sourcing-strategy admin endpoints.
 */
@WebMvcTest(SourcingStrategyController.class)
@Import(TestSecurityConfig.class)
@ActiveProfiles("test")
@SuppressWarnings({"java:S6813", "java:S100", "java:S1192"})
class SourcingStrategyControllerTest {

    private static final UUID CONFIG_ID = UUID.fromString("00000000-0000-0000-0000-000000000060");
    private static final UUID SITE_ID = UUID.fromString("00000000-0000-0000-0000-000000000061");
    private static final String ADMIN = "inventory:location:admin";
    private static final String VIEW_ONLY = "inventory:location:view";

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    java.time.Clock clock;

    @MockitoBean
    SourcingStrategyConfigService sourcingStrategyConfigService;

    private SourcingStrategyConfigResponse sampleResponse() {
        return SourcingStrategyConfigResponse.builder()
                .configId(CONFIG_ID)
                .scopeType(SourcingScopeType.SITE)
                .scopeValue(SITE_ID.toString())
                .strategy(SourcingStrategy.PROXIMITY)
                .active(true)
                .createdAt(Instant.parse("2026-07-01T00:00:00Z"))
                .updatedAt(Instant.parse("2026-07-01T00:00:00Z"))
                .build();
    }

    // ─── GET /v1/inventory/sourcing-strategies ───────────────────────────────

    @Test
    void listConfigs_withAdminAuthority_returns200() throws Exception {
        when(sourcingStrategyConfigService.listConfigs()).thenReturn(List.of(sampleResponse()));

        mockMvc.perform(get("/v1/inventory/sourcing-strategies").header("X-Authorities", ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].configId").value(CONFIG_ID.toString()))
                .andExpect(jsonPath("$[0].strategy").value("PROXIMITY"));
    }

    @Test
    void listConfigs_missingAdminAuthority_returns403() throws Exception {
        mockMvc.perform(get("/v1/inventory/sourcing-strategies").header("X-Authorities", VIEW_ONLY))
                .andExpect(status().isForbidden());
    }

    // ─── PUT /v1/inventory/sourcing-strategies ───────────────────────────────

    @Test
    void upsertConfig_withAdminAuthority_returns200() throws Exception {
        when(sourcingStrategyConfigService.upsertConfig(any())).thenReturn(sampleResponse());

        mockMvc.perform(put("/v1/inventory/sourcing-strategies")
                        .header("X-Authorities", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"scopeType":"SITE","scopeValue":"%s","strategy":"PROXIMITY"}
                                """.formatted(SITE_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configId").value(CONFIG_ID.toString()))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void upsertConfig_missingAdminAuthority_returns403() throws Exception {
        mockMvc.perform(put("/v1/inventory/sourcing-strategies")
                        .header("X-Authorities", VIEW_ONLY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scopeType\":\"DEFAULT\",\"strategy\":\"FIFO\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void upsertConfig_missingRequiredFields_returns400() throws Exception {
        mockMvc.perform(put("/v1/inventory/sourcing-strategies")
                        .header("X-Authorities", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    // ─── DELETE /v1/inventory/sourcing-strategies/{configId} ─────────────────

    @Test
    void deactivateConfig_withAdminAuthority_returns200() throws Exception {
        SourcingStrategyConfigResponse deactivated = sampleResponse();
        deactivated.setActive(false);
        when(sourcingStrategyConfigService.deactivateConfig(CONFIG_ID)).thenReturn(deactivated);

        mockMvc.perform(delete("/v1/inventory/sourcing-strategies/{id}", CONFIG_ID)
                        .header("X-Authorities", ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        verify(sourcingStrategyConfigService).deactivateConfig(CONFIG_ID);
    }

    @Test
    void deactivateConfig_missingAdminAuthority_returns403() throws Exception {
        mockMvc.perform(delete("/v1/inventory/sourcing-strategies/{id}", CONFIG_ID)
                        .header("X-Authorities", VIEW_ONLY))
                .andExpect(status().isForbidden());
    }
}
