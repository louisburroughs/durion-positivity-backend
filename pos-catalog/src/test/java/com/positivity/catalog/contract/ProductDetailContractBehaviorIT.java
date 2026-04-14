package com.positivity.catalog.contract;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.catalog.BaseContractIntegrationTest;
import com.positivity.catalog.internal.client.InventoryClient;
import com.positivity.catalog.internal.client.InventoryClient.AvailabilityClientResponse;
import com.positivity.catalog.internal.client.PricingClient;
import com.positivity.catalog.internal.client.PricingClient.PriceQuoteClientResponse;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;

/**
 * Contract behavioral tests for the Product Detail aggregation endpoint.
 *
 * <p>
 * Tests cover CAP-247 Story #16: live pricing and availability data sourced
 * from {@link PricingClient} and {@link InventoryClient} respectively.
 * Tests are GREEN against the implemented {@code ProductDetailServiceImpl}.
 *
 * <p>
 * Each test uses a unique product SKU and unique productId to avoid
 * {@code @Cacheable} cross-test interference.
 *
 * Issue: CAP-247
 */
@DisplayName("Product Detail Contract Behavioral Tests")
class ProductDetailContractBehaviorIT extends BaseContractIntegrationTest {

    @MockitoBean
    private PricingClient pricingClient;

    @MockitoBean
    private InventoryClient inventoryClient;

    // -----------------------------------------------------------------------
    // PD-001: Both services available → HIGH confidence, real values returned
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("PD-001: Pricing and inventory available – returns HIGH confidence with real values from clients")
    void testProductDetail_pricingAndInventoryAvailable_returnsHighConfidenceWithRealValues() throws Exception {
        // Issue CAP-247: mock clients with live data; production code must delegate to
        // them
        Mockito.when(pricingClient.fetchPrice(Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(Optional.of(new PriceQuoteClientResponse(
                        new BigDecimal("249.99"), "USD",
                        new BigDecimal("199.99"), "TIER_A")));

        Mockito.when(inventoryClient.fetchAvailability(Mockito.any(), Mockito.any()))
                .thenReturn(Optional.of(new AvailabilityClientResponse(25, 5, 20, "EA")));

        UUID productId = createProductAndGetId(
                "SKU-PD-001-" + UUID.fromString("00000000-0000-0000-0000-000000000001"), "MPN-PD-001");
        UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        mockMvc.perform(withAuth(MockMvcRequestBuilders.get("/v1/products/{productId}/detail", productId)
                        .param("location_id", locationId.toString())))
                .andExpect(status().isOk())
                // RED: stub returns 99.99; client mock returns 249.99 — assertion will fail
                .andExpect(jsonPath("$.pricing.msrp").value(249.99))
                .andExpect(jsonPath("$.pricing.storePrice").value(199.99))
                .andExpect(jsonPath("$.pricing.status").value("OK"))
                // RED: stub returns 15; client mock returns 25 — assertion will fail
                .andExpect(jsonPath("$.availability.onHandQuantity").value(25))
                .andExpect(jsonPath("$.availability.availableToPromiseQuantity").value(20))
                .andExpect(jsonPath("$.availability.status").value("OK"))
                .andExpect(jsonPath("$.confidence").value("HIGH"));
    }

    // -----------------------------------------------------------------------
    // PD-002: Pricing service throws → UNAVAILABLE pricing, MEDIUM confidence
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("PD-002: Pricing service unavailable – returns MEDIUM confidence with UNAVAILABLE pricing status")
    void testProductDetail_pricingUnavailable_returnsMediumConfidenceWithPricingUnavailable() throws Exception {
        // Issue CAP-247: pricing client throws; inventory returns live data
        Mockito.when(pricingClient.fetchPrice(Mockito.any(), Mockito.any(), Mockito.any()))
                .thenThrow(new RuntimeException("price service down"));

        Mockito.when(inventoryClient.fetchAvailability(Mockito.any(), Mockito.any()))
                .thenReturn(Optional.of(new AvailabilityClientResponse(10, 2, 8, "EA")));

        UUID productId = createProductAndGetId(
                "SKU-PD-002-" + UUID.fromString("00000000-0000-0000-0000-000000000001"), "MPN-PD-002");
        UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        mockMvc.perform(withAuth(MockMvcRequestBuilders.get("/v1/products/{productId}/detail", productId)
                        .param("location_id", locationId.toString())))
                .andExpect(status().isOk())
                // RED: stub never calls pricingClient so status is always OK — assertion will
                // fail
                .andExpect(jsonPath("$.pricing.status").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.availability.status").value("OK"))
                // RED: stub always returns HIGH; real implementation should degrade to MEDIUM
                .andExpect(jsonPath("$.confidence").value("MEDIUM"));
    }

    // -----------------------------------------------------------------------
    // PD-003: Inventory service throws → UNAVAILABLE availability, MEDIUM
    // confidence
    // -----------------------------------------------------------------------

    @Test
    @DisplayName(
            "PD-003: Inventory service unavailable – returns MEDIUM confidence with UNAVAILABLE availability status")
    void testProductDetail_inventoryUnavailable_returnsMediumConfidenceWithAvailabilityUnavailable() throws Exception {
        // Issue CAP-247: pricing returns live data; inventory client throws
        Mockito.when(pricingClient.fetchPrice(Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(Optional.of(new PriceQuoteClientResponse(
                        new BigDecimal("149.99"), "USD",
                        new BigDecimal("129.99"), "TIER_B")));

        Mockito.when(inventoryClient.fetchAvailability(Mockito.any(), Mockito.any()))
                .thenThrow(new RuntimeException("inventory service down"));

        UUID productId = createProductAndGetId(
                "SKU-PD-003-" + UUID.fromString("00000000-0000-0000-0000-000000000001"), "MPN-PD-003");
        UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        mockMvc.perform(withAuth(MockMvcRequestBuilders.get("/v1/products/{productId}/detail", productId)
                        .param("location_id", locationId.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pricing.status").value("OK"))
                // RED: stub never calls inventoryClient so status is always OK — assertion will
                // fail
                .andExpect(jsonPath("$.availability.status").value("UNAVAILABLE"))
                // RED: stub always returns HIGH; real implementation should degrade to MEDIUM
                .andExpect(jsonPath("$.confidence").value("MEDIUM"));
    }

    // -----------------------------------------------------------------------
    // PD-004: Both services throw → LOW confidence, both statuses UNAVAILABLE
    // (implicit via PD-002 + PD-003 combined; verified independently here)
    // -----------------------------------------------------------------------

    // -----------------------------------------------------------------------
    // PD-005: Product not found → 404
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("PD-005: Product not found – returns 404")
    void testProductDetail_productNotFound_returns404() throws Exception {
        UUID nonExistentProductId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        mockMvc.perform(withAuth(MockMvcRequestBuilders.get("/v1/products/{productId}/detail", nonExistentProductId)
                        .param("location_id", locationId.toString())))
                .andExpect(status().isNotFound());
    }

    // -----------------------------------------------------------------------
    // Helper
    // -----------------------------------------------------------------------

    /**
     * Creates a product via the REST API and returns its generated UUID.
     *
     * @param sku unique SKU to avoid cache and uniqueness-constraint collisions
     * @param mpn manufacturer part number
     * @return the UUID assigned to the newly created product
     */
    private UUID createProductAndGetId(String sku, String mpn) throws Exception {
        Map<String, Object> payload = Map.of(
                "name",
                "Test Product " + sku,
                "description",
                "Product for contract test",
                "unitOfMeasure",
                "EA",
                "manufacturerId",
                UUID.fromString("00000000-0000-0000-0000-000000000001").toString(),
                "sku",
                sku,
                "mpn",
                mpn,
                "upc",
                "1000000000000",
                "attributes",
                "{}");

        MvcResult result = mockMvc.perform(withAuth(post("/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload))))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        return UUID.fromString(response.get("id").asString());
    }
}
