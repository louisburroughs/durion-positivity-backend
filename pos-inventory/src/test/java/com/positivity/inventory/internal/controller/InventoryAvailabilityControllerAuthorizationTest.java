package com.positivity.inventory.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.inventory.config.TestSecurityConfig;
import com.positivity.inventory.internal.dto.AvailabilityView;
import com.positivity.inventory.internal.dto.LeadTimeView;
import com.positivity.inventory.internal.dto.LocationAvailabilityDto;
import com.positivity.inventory.service.InventoryAvailabilityService;
import com.positivity.inventory.service.InventoryLeadTimeService;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-slice tests for the availability permission split (ADR-0057, issue #1494).
 *
 * <p>Three properties are worth pinning, because #1494 was the failure of the third:
 *
 * <ol>
 *   <li>The scope-limited reads accept {@code inventory:availability:read}, so the roles granted it
 *       — TECHNICIAN and SERVICE_ADVISOR among them — can actually read availability.</li>
 *   <li>The per-location breakdown enumerates every location holding the SKU, so it takes the wider
 *       {@code inventory:availability:search} and {@code :read} alone is not enough.</li>
 *   <li>{@code inventory:on_hand:*} no longer reaches any availability endpoint. On-hand reads the
 *       stock record; availability reads the projection net of prior commitments. Neither family
 *       implies the other, and a permission that grants nothing it is named for is exactly the hole
 *       #1494 documented.</li>
 * </ol>
 */
@WebMvcTest(InventoryAvailabilityController.class)
@Import(TestSecurityConfig.class)
@ActiveProfiles("test")
@SuppressWarnings({"java:S6813", "java:S2699"})
class InventoryAvailabilityControllerAuthorizationTest {

    private static final String AVAILABILITY_READ = "inventory:availability:read";
    private static final String AVAILABILITY_SEARCH = "inventory:availability:search";
    private static final String ON_HAND_VIEW = "inventory:on_hand:view";
    private static final String ON_HAND_SEARCH = "inventory:on_hand:search";

    private static final String SKU = "SKU-1494";
    private static final UUID PRODUCT_ID = UUID.fromString("01960003-0000-7000-8000-000000001494");
    private static final UUID LOCATION_ID = UUID.fromString("01960003-0000-7000-8000-000000000001");

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    java.time.Clock clock;

    @MockitoBean
    InventoryAvailabilityService availabilityService;

    @MockitoBean
    InventoryLeadTimeService inventoryLeadTimeService;

    @org.junit.jupiter.api.BeforeEach
    void stubClock() {
        // The module exception advice timestamps every ApiError from the injected Clock.
        when(clock.instant()).thenReturn(java.time.Instant.parse("2026-08-24T00:00:00Z"));
    }

    private void stubAvailability() {
        when(availabilityService.queryAvailability(anyString(), any(), any(), any(), any()))
                .thenReturn(AvailabilityView.builder()
                        .productSku(SKU)
                        .locationId(LOCATION_ID)
                        .onHandQuantity(new BigDecimal("12"))
                        .allocatedQuantity(new BigDecimal("4"))
                        .availableToPromiseQuantity(new BigDecimal("8"))
                        .unitOfMeasure("EACH")
                        .build());
    }

    // ─── scope-limited reads: inventory:availability:read ─────────────────────

    @Test
    @DisplayName("by-sku accepts inventory:availability:read")
    void bySku_withAvailabilityRead_returns200() throws Exception {
        stubAvailability();

        mockMvc.perform(get("/v1/inventory/availability/by-sku")
                        .param("productSku", SKU)
                        .header("X-Authorities", AVAILABILITY_READ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableToPromiseQuantity").value(8));
    }

    @Test
    @DisplayName("by-sku rejects the on-hand family: on-hand does not imply availability")
    void bySku_withOnHandOnly_returns403() throws Exception {
        mockMvc.perform(get("/v1/inventory/availability/by-sku")
                        .param("productSku", SKU)
                        .header("X-Authorities", ON_HAND_VIEW + "," + ON_HAND_SEARCH))
                .andExpect(status().isForbidden());
        verify(availabilityService, never()).queryAvailability(anyString(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("list form accepts inventory:availability:read")
    void listForm_withAvailabilityRead_returns200() throws Exception {
        stubAvailability();

        mockMvc.perform(get("/v1/inventory/availability").param("sku", SKU).header("X-Authorities", AVAILABILITY_READ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].productSku").value(SKU));
    }

    @Test
    @DisplayName("list form rejects the on-hand family")
    void listForm_withOnHandOnly_returns403() throws Exception {
        mockMvc.perform(get("/v1/inventory/availability").param("sku", SKU).header("X-Authorities", ON_HAND_VIEW))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("lead-time accepts inventory:availability:read")
    void leadTime_withAvailabilityRead_returns200() throws Exception {
        when(inventoryLeadTimeService.queryLeadTime(any(), any(), any()))
                .thenReturn(LeadTimeView.builder()
                        .productId(PRODUCT_ID)
                        .locationId(LOCATION_ID)
                        .source("INVENTORY")
                        .minDays(2)
                        .maxDays(5)
                        .confidence("HIGH")
                        .build());

        mockMvc.perform(get("/v1/inventory/availability/lead-time")
                        .param("productId", PRODUCT_ID.toString())
                        .header("X-Authorities", AVAILABILITY_READ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confidence").value("HIGH"));
    }

    @Test
    @DisplayName("lead-time rejects the on-hand family")
    void leadTime_withOnHandOnly_returns403() throws Exception {
        mockMvc.perform(get("/v1/inventory/availability/lead-time")
                        .param("productId", PRODUCT_ID.toString())
                        .header("X-Authorities", ON_HAND_SEARCH))
                .andExpect(status().isForbidden());
        verify(inventoryLeadTimeService, never()).queryLeadTime(any(), any(), any());
    }

    // ─── per-location breakdown: inventory:availability:search ────────────────

    @Test
    @DisplayName("per-location breakdown accepts inventory:availability:search")
    void byProduct_withAvailabilitySearch_returns200() throws Exception {
        when(availabilityService.getAvailabilityByProduct(any(), any()))
                .thenReturn(List.of(LocationAvailabilityDto.builder()
                        .locationId(LOCATION_ID)
                        .locationName(LOCATION_ID.toString())
                        .onHandQuantity(new BigDecimal("12"))
                        .availableToPromiseQuantity(new BigDecimal("8"))
                        .build()));

        mockMvc.perform(get("/v1/inventory/availability/" + PRODUCT_ID).header("X-Authorities", AVAILABILITY_SEARCH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].locationId").value(LOCATION_ID.toString()));
    }

    @Test
    @DisplayName("per-location breakdown rejects :read alone — enumerating locations is the wider disclosure")
    void byProduct_withAvailabilityReadOnly_returns403() throws Exception {
        mockMvc.perform(get("/v1/inventory/availability/" + PRODUCT_ID).header("X-Authorities", AVAILABILITY_READ))
                .andExpect(status().isForbidden());
        verify(availabilityService, never()).getAvailabilityByProduct(any(), any());
    }

    @Test
    @DisplayName("per-location breakdown rejects the on-hand family")
    void byProduct_withOnHandOnly_returns403() throws Exception {
        mockMvc.perform(get("/v1/inventory/availability/" + PRODUCT_ID)
                        .header("X-Authorities", ON_HAND_VIEW + "," + ON_HAND_SEARCH))
                .andExpect(status().isForbidden());
        verify(availabilityService, never()).getAvailabilityByProduct(any(), any());
    }
}
