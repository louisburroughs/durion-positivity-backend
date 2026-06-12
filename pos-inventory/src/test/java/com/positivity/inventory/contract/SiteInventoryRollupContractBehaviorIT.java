package com.positivity.inventory.contract;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.inventory.internal.dto.rollup.RollupQuantities;
import com.positivity.inventory.internal.dto.rollup.SiteInventoryRollupResponse;
import com.positivity.inventory.internal.dto.rollup.StorageLocationRollupNode;
import com.positivity.inventory.internal.exception.LocationNotFoundException;
import com.positivity.inventory.internal.exception.LocationServiceUnavailableException;
import com.positivity.inventory.service.SiteInventoryRollupService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Contract behavior integration tests for the site inventory rollup API —
 * CAP-218 #658.
 *
 * <ul>
 * <li>ADR-0011/0014: gateway auth headers required; 403 without authority</li>
 * <li>ADR-0016: topology comes from pos-location; 404/503 surfaced</li>
 * <li>ADR-0017: 200 / 400 / 403 / 404 / 503 response codes</li>
 * <li>ADR-0023: no tenantId in request or response</li>
 * </ul>
 *
 * Issue: #658
 */
@DisplayName("Site Inventory Rollup Contract Behavior — CAP-218 #658")
class SiteInventoryRollupContractBehaviorIT extends BaseContractIntegrationTest {

    private static final UUID SITE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID FLOOR_ID = UUID.fromString("00000000-0000-0000-0000-0000000000f1");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SiteInventoryRollupService siteInventoryRollupService;

    @Test
    @DisplayName("GET /v1/inventory/sites/{siteId}/inventory-rollup → 200 with totals and tree")
    void contract_rollup_returns200WithTree() throws Exception {
        // Issue #658: happy path returns siteId, totals, nodes with own/rolledUp
        RollupQuantities own = RollupQuantities.of(0, 0);
        RollupQuantities rolledUp = RollupQuantities.of(480, 30);
        SiteInventoryRollupResponse response = new SiteInventoryRollupResponse(
                SITE_ID,
                rolledUp,
                List.of(new StorageLocationRollupNode(
                        FLOOR_ID, "Floor A", "FLOOR", "ACTIVE", own, rolledUp, List.of())));
        when(siteInventoryRollupService.getSiteInventoryRollup(eq(SITE_ID), isNull(), isNull(), eq(false)))
                .thenReturn(response);

        mockMvc.perform(withGatewayAuth(get("/v1/inventory/sites/{siteId}/inventory-rollup", SITE_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.siteId").value(SITE_ID.toString()))
                .andExpect(jsonPath("$.totals.onHand").value(480))
                .andExpect(jsonPath("$.totals.allocated").value(30))
                .andExpect(jsonPath("$.totals.available").value(450))
                .andExpect(jsonPath("$.nodes[0].storageLocationId").value(FLOOR_ID.toString()))
                .andExpect(jsonPath("$.nodes[0].type").value("FLOOR"))
                .andExpect(jsonPath("$.nodes[0].rolledUp.onHand").value(480))
                .andExpect(jsonPath("$.nodes[0].own.onHand").value(0));
    }

    @Test
    @DisplayName("GET .../inventory-rollup passes sku, depth, includeEmpty through")
    void contract_rollup_passesQueryParams() throws Exception {
        // Issue #658: query params forwarded to service
        when(siteInventoryRollupService.getSiteInventoryRollup(eq(SITE_ID), eq("SKU-1"), eq(2), eq(true)))
                .thenReturn(new SiteInventoryRollupResponse(SITE_ID, RollupQuantities.ZERO, List.of()));

        mockMvc.perform(withGatewayAuth(get("/v1/inventory/sites/{siteId}/inventory-rollup", SITE_ID)
                        .param("sku", "SKU-1")
                        .param("depth", "2")
                        .param("includeEmpty", "true")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes").isEmpty());
    }

    @Test
    @DisplayName("GET .../inventory-rollup with depth=0 → 400")
    void contract_rollup_invalidDepth_returns400() throws Exception {
        // Issue #658: depth must be >= 1
        mockMvc.perform(withGatewayAuth(get("/v1/inventory/sites/{siteId}/inventory-rollup", SITE_ID)
                        .param("depth", "0")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET .../inventory-rollup unknown site → 404 NOT_FOUND")
    void contract_rollup_unknownSite_returns404() throws Exception {
        // Issue #658: pos-location 404 surfaces as 404 ApiError
        when(siteInventoryRollupService.getSiteInventoryRollup(any(), any(), any(), eq(false)))
                .thenThrow(new LocationNotFoundException(SITE_ID));

        mockMvc.perform(withGatewayAuth(get("/v1/inventory/sites/{siteId}/inventory-rollup", SITE_ID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("GET .../inventory-rollup when pos-location is down → 503 LOCATION_SERVICE_UNAVAILABLE")
    void contract_rollup_locationServiceDown_returns503() throws Exception {
        // Issue #658: never fabricate partial topology; surface 503 with retry hint
        when(siteInventoryRollupService.getSiteInventoryRollup(any(), any(), any(), eq(false)))
                .thenThrow(new LocationServiceUnavailableException("Location service unavailable", null));

        mockMvc.perform(withGatewayAuth(get("/v1/inventory/sites/{siteId}/inventory-rollup", SITE_ID)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("LOCATION_SERVICE_UNAVAILABLE"));
    }

    @Test
    @DisplayName("GET .../inventory-rollup without on-hand view authority → 403")
    void contract_rollup_missingAuthority_returns403() throws Exception {
        // Issue #658: authz mirrors LocationInventoryInquiryController
        mockMvc.perform(get("/v1/inventory/sites/{siteId}/inventory-rollup", SITE_ID)
                        .header("X-User", "test-user")
                        .header("X-Authorities", "unrelated:authority"))
                .andExpect(status().isForbidden());
    }
}
