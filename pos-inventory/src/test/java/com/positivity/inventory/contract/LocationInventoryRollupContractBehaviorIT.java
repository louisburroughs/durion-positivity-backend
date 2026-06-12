package com.positivity.inventory.contract;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.inventory.internal.dto.rollup.LocationInventoryRollupResponse;
import com.positivity.inventory.internal.dto.rollup.RollupQuantities;
import com.positivity.inventory.internal.dto.rollup.SiteRollupSummary;
import com.positivity.inventory.internal.dto.rollup.StorageLocationRollupNode;
import com.positivity.inventory.internal.exception.LocationNotFoundException;
import com.positivity.inventory.internal.exception.RollupExpansionTooLargeException;
import com.positivity.inventory.service.LocationInventoryRollupService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Contract behavior integration tests for the parent-location inventory
 * rollup API — CAP-218 #659.
 *
 * Issue: #659
 */
@DisplayName("Location Inventory Rollup Contract Behavior — CAP-218 #659")
class LocationInventoryRollupContractBehaviorIT extends BaseContractIntegrationTest {

    private static final UUID BUILDING_ID = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID SITE_A = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LocationInventoryRollupService locationInventoryRollupService;

    @Test
    @DisplayName("GET /v1/inventory/locations/{locationId}/inventory-rollup → 200 with site summaries")
    void contract_locationRollup_returns200Summaries() throws Exception {
        // Issue #659: summaries only by default; nodes absent from JSON
        LocationInventoryRollupResponse response = new LocationInventoryRollupResponse(
                BUILDING_ID,
                "PHYSICAL",
                RollupQuantities.of(150, 15),
                List.of(new SiteRollupSummary(SITE_A, "Main Workshop", RollupQuantities.of(150, 15), null)));
        when(locationInventoryRollupService.getLocationInventoryRollup(
                        eq(BUILDING_ID), isNull(), isNull(), eq(false), isNull(), eq(false)))
                .thenReturn(response);

        mockMvc.perform(withGatewayAuth(get("/v1/inventory/locations/{locationId}/inventory-rollup", BUILDING_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locationId").value(BUILDING_ID.toString()))
                .andExpect(jsonPath("$.parentType").value("PHYSICAL"))
                .andExpect(jsonPath("$.totals.available").value(135))
                .andExpect(jsonPath("$.sites[0].siteId").value(SITE_A.toString()))
                .andExpect(jsonPath("$.sites[0].siteName").value("Main Workshop"))
                .andExpect(jsonPath("$.sites[0].nodes").doesNotExist());
    }

    @Test
    @DisplayName("GET .../inventory-rollup?expand=tree → 200 with inlined trees")
    void contract_locationRollup_expandTree_returns200WithNodes() throws Exception {
        // Issue #659: expand=tree inlines per-site nodes
        StorageLocationRollupNode node = new StorageLocationRollupNode(
                UUID.randomUUID(),
                "Floor A",
                "FLOOR",
                "ACTIVE",
                RollupQuantities.of(150, 15),
                RollupQuantities.of(150, 15),
                List.of());
        LocationInventoryRollupResponse response = new LocationInventoryRollupResponse(
                BUILDING_ID,
                "PHYSICAL",
                RollupQuantities.of(150, 15),
                List.of(new SiteRollupSummary(SITE_A, "Main Workshop", RollupQuantities.of(150, 15), List.of(node))));
        when(locationInventoryRollupService.getLocationInventoryRollup(
                        eq(BUILDING_ID), isNull(), isNull(), eq(true), isNull(), eq(false)))
                .thenReturn(response);

        mockMvc.perform(withGatewayAuth(get("/v1/inventory/locations/{locationId}/inventory-rollup", BUILDING_ID)
                        .param("expand", "tree")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sites[0].nodes[0].name").value("Floor A"));
    }

    @Test
    @DisplayName("GET .../inventory-rollup?expand=bogus → 400")
    void contract_locationRollup_invalidExpand_returns400() throws Exception {
        // Issue #659: only expand=tree supported
        mockMvc.perform(withGatewayAuth(get("/v1/inventory/locations/{locationId}/inventory-rollup", BUILDING_ID)
                        .param("expand", "bogus")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET .../inventory-rollup unknown parentType → 400 VALIDATION_ERROR")
    void contract_locationRollup_unknownParentType_returns400() throws Exception {
        // Issue #659: parentType validated against ParentType value set
        when(locationInventoryRollupService.getLocationInventoryRollup(
                        any(), eq("BOGUS"), any(), anyBoolean(), any(), anyBoolean()))
                .thenThrow(new IllegalArgumentException("Unknown parentType 'BOGUS'"));

        mockMvc.perform(withGatewayAuth(get("/v1/inventory/locations/{locationId}/inventory-rollup", BUILDING_ID)
                        .param("parentType", "BOGUS")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("GET .../inventory-rollup?expand=tree over site cap → 422 ROLLUP_EXPANSION_TOO_LARGE")
    void contract_locationRollup_overCap_returns422() throws Exception {
        // Issue #659: fan-out guard surfaces as 422 with guidance
        when(locationInventoryRollupService.getLocationInventoryRollup(
                        any(), any(), any(), eq(true), any(), anyBoolean()))
                .thenThrow(new RollupExpansionTooLargeException(40, 25));

        mockMvc.perform(withGatewayAuth(get("/v1/inventory/locations/{locationId}/inventory-rollup", BUILDING_ID)
                        .param("expand", "tree")))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("ROLLUP_EXPANSION_TOO_LARGE"));
    }

    @Test
    @DisplayName("GET .../inventory-rollup unknown location → 404 NOT_FOUND")
    void contract_locationRollup_unknownLocation_returns404() throws Exception {
        // Issue #659: pos-location 404 surfaces as 404 ApiError
        when(locationInventoryRollupService.getLocationInventoryRollup(
                        any(), any(), any(), anyBoolean(), any(), anyBoolean()))
                .thenThrow(new LocationNotFoundException(BUILDING_ID));

        mockMvc.perform(withGatewayAuth(get("/v1/inventory/locations/{locationId}/inventory-rollup", BUILDING_ID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("GET .../inventory-rollup without on-hand view authority → 403")
    void contract_locationRollup_missingAuthority_returns403() throws Exception {
        // Issue #659: authz mirrors site rollup
        mockMvc.perform(get("/v1/inventory/locations/{locationId}/inventory-rollup", BUILDING_ID)
                        .header("X-User", "test-user")
                        .header("X-Authorities", "unrelated:authority"))
                .andExpect(status().isForbidden());
    }
}
