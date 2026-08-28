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
import com.positivity.inventory.internal.config.SkuCategoryCutoverService;
import com.positivity.inventory.internal.costing.service.CostingMethodConfigService;
import com.positivity.inventory.internal.dto.costing.CostingMethodConfigResponse;
import com.positivity.inventory.internal.dto.costing.SkuCategoryImpactResponse;
import com.positivity.inventory.internal.dto.costing.SkuCategoryImpactRow;
import com.positivity.inventory.internal.enums.CostingMethod;
import com.positivity.inventory.internal.enums.CostingScopeType;
import com.positivity.inventory.internal.exception.ResourceNotFoundException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-slice tests for CostingMethodController (odoo-parity J1, #1048; cut-over
 * audit #1535): {@code inventory:location:admin} gating and request/response
 * mapping of the costing-method admin endpoints.
 *
 * <p>Both new endpoints are guarded by the same single authority the controller
 * already used. A second authority here would let a caller list the config rows
 * but not see what changing them does, which is the wrong way round.
 */
@WebMvcTest(CostingMethodController.class)
@Import(TestSecurityConfig.class)
@ActiveProfiles("test")
@SuppressWarnings({"java:S6813", "java:S100", "java:S1192"})
class CostingMethodControllerTest {

    private static final UUID CONFIG_ID = UUID.fromString("00000000-0000-0000-0000-000000000070");
    private static final UUID SKU_ID = UUID.fromString("00000000-0000-0000-0000-000000000071");
    private static final String ADMIN = "inventory:location:admin";
    private static final String VIEW_ONLY = "inventory:location:view";
    private static final String BASE = "/v1/inventory/valuation/methods";

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    java.time.Clock clock;

    /**
     * {@link com.positivity.inventory.internal.controller.InventoryGlobalExceptionHandler} stamps
     * {@code Instant.now(clock)} onto every ApiError. An unstubbed mock returns null there, the
     * handler throws, and Spring rethrows the ORIGINAL exception — so the 400 and 404 cases would
     * fail while looking like a routing bug rather than a missing stub.
     */
    @BeforeEach
    void stubClock() {
        when(clock.instant()).thenReturn(Instant.parse("2026-08-27T00:00:00Z"));
        when(clock.getZone()).thenReturn(java.time.ZoneOffset.UTC);
    }

    @MockitoBean
    CostingMethodConfigService costingMethodConfigService;

    @MockitoBean
    SkuCategoryCutoverService skuCategoryCutoverService;

    private CostingMethodConfigResponse sampleResponse() {
        return CostingMethodConfigResponse.builder()
                .configId(CONFIG_ID)
                .scopeType(CostingScopeType.SKU)
                .scopeValue(SKU_ID.toString())
                .method(CostingMethod.STANDARD)
                .active(true)
                .createdAt(Instant.parse("2026-07-01T00:00:00Z"))
                .updatedAt(Instant.parse("2026-07-01T00:00:00Z"))
                .build();
    }

    // ─── GET /v1/inventory/valuation/methods ─────────────────────────────────

    @Test
    void listConfigs_withAdminAuthority_returnsRows() throws Exception {
        when(costingMethodConfigService.listConfigs()).thenReturn(List.of(sampleResponse()));

        mockMvc.perform(get(BASE).header("X-Authorities", ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].configId").value(CONFIG_ID.toString()))
                .andExpect(jsonPath("$[0].method").value("STANDARD"));
    }

    @Test
    void listConfigs_withoutAdminAuthority_returnsForbidden() throws Exception {
        mockMvc.perform(get(BASE).header("X-Authorities", VIEW_ONLY)).andExpect(status().isForbidden());
    }

    // ─── PUT /v1/inventory/valuation/methods ─────────────────────────────────

    @Test
    void upsertConfig_withAdminAuthority_returnsUpsertedRow() throws Exception {
        when(costingMethodConfigService.upsertConfig(any())).thenReturn(sampleResponse());

        mockMvc.perform(put(BASE)
                        .header("X-Authorities", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"scopeType":"SKU","scopeValue":"%s","method":"STANDARD"}
                                """.formatted(SKU_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configId").value(CONFIG_ID.toString()))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void upsertConfig_defaultScopeWithScopeValue_returnsBadRequest() throws Exception {
        when(costingMethodConfigService.upsertConfig(any()))
                .thenThrow(new IllegalArgumentException("scopeValue must be omitted for DEFAULT scope"));

        mockMvc.perform(put(BASE)
                        .header("X-Authorities", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scopeType\":\"DEFAULT\",\"scopeValue\":\"nope\",\"method\":\"AVERAGE\"}"))
                .andExpect(status().isBadRequest());
    }

    // ─── DELETE /v1/inventory/valuation/methods/{configId} ───────────────────

    @Test
    void deactivateConfig_withAdminAuthority_returnsDeactivatedRow() throws Exception {
        CostingMethodConfigResponse deactivated = sampleResponse();
        deactivated.setActive(false);
        when(costingMethodConfigService.deactivateConfig(CONFIG_ID)).thenReturn(deactivated);

        mockMvc.perform(delete(BASE + "/{id}", CONFIG_ID).header("X-Authorities", ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        verify(costingMethodConfigService).deactivateConfig(CONFIG_ID);
    }

    @Test
    void deactivateConfig_unknownId_returnsNotFound() throws Exception {
        when(costingMethodConfigService.deactivateConfig(CONFIG_ID))
                .thenThrow(new ResourceNotFoundException("Costing method config", CONFIG_ID.toString()));

        mockMvc.perform(delete(BASE + "/{id}", CONFIG_ID).header("X-Authorities", ADMIN))
                .andExpect(status().isNotFound());
    }

    @Test
    void deactivateConfig_withoutAdminAuthority_returnsForbidden() throws Exception {
        mockMvc.perform(delete(BASE + "/{id}", CONFIG_ID).header("X-Authorities", VIEW_ONLY))
                .andExpect(status().isForbidden());
    }

    // ─── GET /v1/inventory/valuation/methods/sku-category-impact ─────────────

    @Test
    void skuCategoryImpact_withAdminAuthority_returnsReport() throws Exception {
        when(skuCategoryCutoverService.impact())
                .thenReturn(SkuCategoryImpactResponse.builder()
                        .resolveFromReplicaEnabled(false)
                        .deploymentDefaultMethod(CostingMethod.AVERAGE)
                        .activeSkuCategoryConfigCount(1)
                        .categoriesWithNoReplicatedProducts(List.of("Zzz Nonexistent"))
                        .evaluatedSkuCount(1)
                        .impactedSkuCount(1)
                        .impactedSkuWithCostStateCount(0)
                        .impactedSkus(List.of(SkuCategoryImpactRow.builder()
                                .stockItemId(SKU_ID.toString())
                                .productId(SKU_ID)
                                .categoryName("Electrical System")
                                .configId(CONFIG_ID)
                                .currentMethod(CostingMethod.AVERAGE)
                                .projectedMethod(CostingMethod.STANDARD)
                                .hasCostState(false)
                                .build()))
                        .impactedSourcingSkus(List.of())
                        .build());

        mockMvc.perform(get(BASE + "/sku-category-impact").header("X-Authorities", ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolveFromReplicaEnabled").value(false))
                .andExpect(jsonPath("$.impactedSkuCount").value(1))
                .andExpect(jsonPath("$.categoriesWithNoReplicatedProducts[0]").value("Zzz Nonexistent"))
                .andExpect(jsonPath("$.impactedSkus[0].stockItemId").value(SKU_ID.toString()))
                .andExpect(jsonPath("$.impactedSkus[0].projectedMethod").value("STANDARD"))
                .andExpect(jsonPath("$.impactedSkus[0].hasCostState").value(false));
    }

    @Test
    void skuCategoryImpact_withoutAdminAuthority_returnsForbidden() throws Exception {
        mockMvc.perform(get(BASE + "/sku-category-impact").header("X-Authorities", VIEW_ONLY))
                .andExpect(status().isForbidden());
    }
}
