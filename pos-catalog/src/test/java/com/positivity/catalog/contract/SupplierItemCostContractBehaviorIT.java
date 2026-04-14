package com.positivity.catalog.contract;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.catalog.BaseContractIntegrationTest;
import com.positivity.catalog.internal.dto.SupplierItemCostDto;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

@DisplayName("Supplier Item Cost Contract Behavioral Tests")
class SupplierItemCostContractBehaviorIT extends BaseContractIntegrationTest {

    @Test
    @DisplayName("CP-166-001: Create valid cost tier structure -> 201 Created")
    void createValidCostTierStructure() throws Exception {
        mockMvc.perform(withAuth(post("/v1/products/supplier-costs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPayload())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.currencyCode").value("USD"))
                .andExpect(jsonPath("$.tiers[0].minQuantity").value(1));
    }

    @Test
    @DisplayName("VE-166-001: Create overlapping tiers -> 400 with INVALID_TIER_STRUCTURE")
    void createOverlappingTiers() throws Exception {
        mockMvc.perform(withAuth(post("/v1/products/supplier-costs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(overlappingPayload())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("INVALID_TIER_STRUCTURE")));
    }

    @Test
    @DisplayName("VE-166-002: Create tiers with gap -> 400 with INVALID_TIER_STRUCTURE")
    void createTiersWithGap() throws Exception {
        mockMvc.perform(withAuth(post("/v1/products/supplier-costs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(gapPayload())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("INVALID_TIER_STRUCTURE")));
    }

    @Test
    @DisplayName("CP-166-002: Retrieve cost tiers -> 200 OK with tiers ordered by minQuantity")
    void retrieveCostTiersInOrder() throws Exception {
        MvcResult createResult = mockMvc.perform(withAuth(post("/v1/products/supplier-costs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(unorderedButContiguousPayload())))
                .andExpect(status().isCreated())
                .andReturn();

        SupplierItemCostDto created =
                objectMapper.readValue(createResult.getResponse().getContentAsString(), SupplierItemCostDto.class);
        String id = created.getId().toString();

        mockMvc.perform(withAuth(get("/v1/products/supplier-costs/{id}", id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tiers[0].minQuantity").value(1))
                .andExpect(jsonPath("$.tiers[1].minQuantity").value(11))
                .andExpect(jsonPath("$.tiers[2].minQuantity").value(21));
    }

    private String validPayload() throws Exception {
        UUID supplierId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID itemId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        return objectMapper.writeValueAsString(Map.of(
                "supplierId",
                supplierId,
                "itemId",
                itemId,
                "currencyCode",
                "USD",
                "baseCost",
                7.2500,
                "tiers",
                java.util.List.of(
                        Map.of("minQuantity", 1, "maxQuantity", 10, "unitCost", 6.5000),
                        Map.of("minQuantity", 11, "maxQuantity", 20, "unitCost", 6.2500),
                        Map.of("minQuantity", 21, "unitCost", 6.0000))));
    }

    private String unorderedButContiguousPayload() throws Exception {
        UUID supplierId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID itemId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        return objectMapper.writeValueAsString(Map.of(
                "supplierId",
                supplierId,
                "itemId",
                itemId,
                "currencyCode",
                "USD",
                "baseCost",
                7.2500,
                "tiers",
                java.util.List.of(
                        Map.of("minQuantity", 11, "maxQuantity", 20, "unitCost", 6.2500),
                        Map.of("minQuantity", 1, "maxQuantity", 10, "unitCost", 6.5000),
                        Map.of("minQuantity", 21, "unitCost", 6.0000))));
    }

    private String overlappingPayload() throws Exception {
        UUID supplierId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID itemId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        return objectMapper.writeValueAsString(Map.of(
                "supplierId",
                supplierId,
                "itemId",
                itemId,
                "currencyCode",
                "USD",
                "tiers",
                java.util.List.of(
                        Map.of("minQuantity", 1, "maxQuantity", 10, "unitCost", 6.5000),
                        Map.of("minQuantity", 10, "maxQuantity", 20, "unitCost", 6.2500),
                        Map.of("minQuantity", 21, "unitCost", 6.0000))));
    }

    private String gapPayload() throws Exception {
        UUID supplierId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID itemId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        return objectMapper.writeValueAsString(Map.of(
                "supplierId",
                supplierId,
                "itemId",
                itemId,
                "currencyCode",
                "USD",
                "tiers",
                java.util.List.of(
                        Map.of("minQuantity", 1, "maxQuantity", 10, "unitCost", 6.5000),
                        Map.of("minQuantity", 12, "maxQuantity", 20, "unitCost", 6.2500),
                        Map.of("minQuantity", 21, "unitCost", 6.0000))));
    }
}
