package com.positivity.catalog.contract;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.catalog.BaseIntegrationTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

@DisplayName("Catalog Backend Contract Behavioral Tests")
class ContractBehaviorIT extends BaseIntegrationTest {

    // ===============================================
    // HAPPY PATH SCENARIOS
    // ===============================================

    private static final String SUPPLIER_ID = "$.supplierId";

    @Test
    @DisplayName("CP-001: Create catalog with valid payload")
    void testCreateCatalog_HappyPath() throws Exception {
        mockMvc.perform(withAuth(post("/v1/products/catalog"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(catalogPayload("Retail Catalog", "Primary retail catalog")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.name").value("Retail Catalog"))
                .andExpect(jsonPath("$.description").value("Primary retail catalog"));
    }

    @Test
    @DisplayName("CP-002: Retrieve catalog by id after creation")
    void testGetCatalogById_HappyPath() throws Exception {
        UUID catalogId = createCatalogAndReturnId("Service Catalog", "Catalog for services");

        mockMvc.perform(withAuth(get("/v1/products/catalog/{catalogId}", catalogId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(catalogId.toString()))
                .andExpect(jsonPath("$.name").value("Service Catalog"));
    }

    @Test
    @DisplayName("CP-003: Update catalog and return updated payload")
    void testUpdateCatalog_HappyPath() throws Exception {
        UUID catalogId = createCatalogAndReturnId("Initial Name", "Initial description");

        mockMvc.perform(withAuth(put("/v1/products/catalog/{catalogId}", catalogId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(catalogPayload("Updated Name", "Updated description")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(catalogId.toString()))
                .andExpect(jsonPath("$.name").value("Updated Name"))
                .andExpect(jsonPath("$.description").value("Updated description"));
    }

    @Test
    @DisplayName("CP-004: Delete existing catalog")
    void testDeleteCatalog_HappyPath() throws Exception {
        UUID catalogId = createCatalogAndReturnId("Delete Me", "To be deleted");

        mockMvc.perform(withAuth(delete("/v1/products/catalog/{catalogId}", catalogId)))
                .andExpect(status().isNoContent());

        mockMvc.perform(withAuth(get("/v1/products/catalog/{catalogId}", catalogId)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("CP-005: Substitutes endpoint returns not implemented")
    void testGetSubstitutes_NotImplemented() throws Exception {
        mockMvc.perform(withAuth(get("/v1/products/substitutes/{productId}", UUID.randomUUID())))
                .andExpect(status().isNotImplemented());
    }

    @Test
    @DisplayName("CP-006: Create supplier-item cost tiers with valid contiguous ranges")
    void testCreateSupplierItemCost_HappyPath() throws Exception {
        UUID supplierId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();

        mockMvc.perform(withAuth(post("/v1/products/costs/supplier-item"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(validSupplierItemCostPayload(supplierId, itemId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath(SUPPLIER_ID).value(supplierId.toString()))
                .andExpect(jsonPath("$.itemId").value(itemId.toString()))
                .andExpect(jsonPath("$.currencyCode").value("USD"))
                .andExpect(jsonPath("$.tiers[0].minQuantity").value(1))
                .andExpect(jsonPath("$.tiers[0].maxQuantity").value(10))
                .andExpect(jsonPath("$.tiers[0].unitCost").value(5.00))
                .andExpect(jsonPath("$.tiers[2].minQuantity").value(51))
                .andExpect(jsonPath("$.tiers[2].maxQuantity").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.tiers[2].unitCost").value(4.00));
    }

    @Test
    @DisplayName("CP-007: Retrieve supplier-item cost tiers by supplier and item")
    void testGetSupplierItemCost_HappyPath() throws Exception {
        UUID supplierId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        createSupplierItemCost(supplierId, itemId);

        mockMvc.perform(withAuth(get("/v1/products/costs/supplier-item/{supplierId}/{itemId}", supplierId, itemId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath(SUPPLIER_ID).value(supplierId.toString()))
                .andExpect(jsonPath("$.itemId").value(itemId.toString()))
                .andExpect(jsonPath("$.tiers.length()").value(3));
    }

    @Test
    @DisplayName("CP-008: Update supplier-item cost tiers")
    void testUpdateSupplierItemCost_HappyPath() throws Exception {
        UUID supplierId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        createSupplierItemCost(supplierId, itemId);
        Long currentVersion = getSupplierItemCostVersion(supplierId, itemId);

        mockMvc.perform(withAuth(put("/v1/products/costs/supplier-item/{supplierId}/{itemId}", supplierId, itemId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(updatedSupplierItemCostPayload(currentVersion)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currencyCode").value("USD"))
                .andExpect(jsonPath("$.baseCost").value(6.50))
                .andExpect(jsonPath("$.tiers[0].minQuantity").value(1))
                .andExpect(jsonPath("$.tiers[1].minQuantity").value(26));
    }

    @Test
    @DisplayName("CP-009: Delete supplier-item cost tiers")
    void testDeleteSupplierItemCost_HappyPath() throws Exception {
        UUID supplierId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        createSupplierItemCost(supplierId, itemId);

        mockMvc.perform(withAuth(delete("/v1/products/costs/supplier-item/{supplierId}/{itemId}", supplierId, itemId)))
                .andExpect(status().isNoContent());

        mockMvc.perform(withAuth(get("/v1/products/costs/supplier-item/{supplierId}/{itemId}", supplierId, itemId)))
                .andExpect(status().isNotFound());
    }

    // ===============================================
    // VALIDATION ERROR SCENARIOS
    // ===============================================

    @Test
    @DisplayName("VE-001: Unknown catalog id returns 404")
    void testGetCatalogById_NotFound() throws Exception {
        mockMvc.perform(withAuth(get("/v1/products/catalog/{catalogId}", UUID.randomUUID())))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("VE-002: Update non-existent catalog returns 404")
    void testUpdateCatalog_NotFound() throws Exception {
        mockMvc.perform(withAuth(put("/v1/products/catalog/{catalogId}", UUID.randomUUID()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(catalogPayload("Missing", "Missing")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("VE-003: Delete non-existent catalog returns 404")
    void testDeleteCatalog_NotFound() throws Exception {
        mockMvc.perform(withAuth(delete("/v1/products/catalog/{catalogId}", UUID.randomUUID())))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("VE-004: Unsupported item type returns 400")
    void testUnsupportedType_BadRequest() throws Exception {
        mockMvc.perform(withAuth(delete("/v1/products/{type}/{catalogId}", "unsupported", UUID.randomUUID())))
                .andExpect(status().isBadRequest());
    }

        @Test
        @DisplayName("VE-005: Reject overlapping supplier-item cost tiers")
        void testCreateSupplierItemCost_OverlappingTiers() throws Exception {
                UUID supplierId = UUID.randomUUID();
                UUID itemId = UUID.randomUUID();

                mockMvc.perform(withAuth(post("/v1/products/costs/supplier-item"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(overlappingTierPayload(supplierId, itemId)))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$").value(org.hamcrest.Matchers.containsString("INVALID_TIER_STRUCTURE")));
        }

        @Test
        @DisplayName("VE-006: Reject supplier-item cost tiers with quantity gap")
        void testCreateSupplierItemCost_GapTiers() throws Exception {
                UUID supplierId = UUID.randomUUID();
                UUID itemId = UUID.randomUUID();

                mockMvc.perform(withAuth(post("/v1/products/costs/supplier-item"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(gapTierPayload(supplierId, itemId)))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$").value(org.hamcrest.Matchers.containsString("INVALID_TIER_STRUCTURE")));
        }

        @Test
        @DisplayName("VE-007: Reject supplier-item cost tiers with non-positive unit cost")
        void testCreateSupplierItemCost_InvalidUnitCost() throws Exception {
                UUID supplierId = UUID.randomUUID();
                UUID itemId = UUID.randomUUID();

                mockMvc.perform(withAuth(post("/v1/products/costs/supplier-item"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(invalidUnitCostPayload(supplierId, itemId)))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$").value(org.hamcrest.Matchers.containsString("INVALID_DATA")));
        }

        @Test
        @DisplayName("VE-008: Missing supplier-item cost returns 404")
        void testGetSupplierItemCost_NotFound() throws Exception {
                mockMvc.perform(withAuth(get("/v1/products/costs/supplier-item/{supplierId}/{itemId}", UUID.randomUUID(), UUID.randomUUID())))
                                .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("VE-009: Create supplier-item cost forbidden without edit role")
        void testCreateSupplierItemCost_Forbidden() throws Exception {
                UUID supplierId = UUID.randomUUID();
                UUID itemId = UUID.randomUUID();

                mockMvc.perform(withAuth(post("/v1/products/costs/supplier-item"), "ROLE_CATALOG_VIEW")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(validSupplierItemCostPayload(supplierId, itemId)))
                                .andExpect(status().isForbidden());
        }

    // ===============================================
    // IDEMPOTENCY SCENARIOS
    // ===============================================

    @Test
    @DisplayName("ID-001: Repeated GET for same catalog returns stable response")
    void testGetCatalog_IdempotentRead() throws Exception {
        UUID catalogId = createCatalogAndReturnId("Stable Catalog", "Stable payload");

        mockMvc.perform(withAuth(get("/v1/products/catalog/{catalogId}", catalogId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(catalogId.toString()))
                .andExpect(jsonPath("$.name").value("Stable Catalog"));

        mockMvc.perform(withAuth(get("/v1/products/catalog/{catalogId}", catalogId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(catalogId.toString()))
                .andExpect(jsonPath("$.name").value("Stable Catalog"));
    }

    @Test
    @DisplayName("ID-002: Repeated DELETE keeps resource absent")
    void testDeleteCatalog_RepeatedRequest() throws Exception {
        UUID catalogId = createCatalogAndReturnId("Delete Twice", "Delete scenario");

        mockMvc.perform(withAuth(delete("/v1/products/catalog/{catalogId}", catalogId)))
                .andExpect(status().isNoContent());

        mockMvc.perform(withAuth(delete("/v1/products/catalog/{catalogId}", catalogId)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("ID-003: Repeated GET for supplier-item cost returns stable response")
    void testGetSupplierItemCost_IdempotentRead() throws Exception {
        UUID supplierId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        createSupplierItemCost(supplierId, itemId);

        mockMvc.perform(withAuth(get("/v1/products/costs/supplier-item/{supplierId}/{itemId}", supplierId, itemId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath(SUPPLIER_ID).value(supplierId.toString()))
                .andExpect(jsonPath("$.itemId").value(itemId.toString()));

        mockMvc.perform(withAuth(get("/v1/products/costs/supplier-item/{supplierId}/{itemId}", supplierId, itemId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath(SUPPLIER_ID).value(supplierId.toString()))
                .andExpect(jsonPath("$.itemId").value(itemId.toString()));
    }

    // ===============================================
    // CONCURRENCY-SAFE INVARIANTS
    // ===============================================

    @Test
    @DisplayName("CC-001: Sequential updates preserve catalog identity")
    void testCatalogSequentialUpdates_PreserveIdentity() throws Exception {
        UUID catalogId = createCatalogAndReturnId("Version A", "First state");

        mockMvc.perform(withAuth(put("/v1/products/catalog/{catalogId}", catalogId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(catalogPayload("Version B", "Second state")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(catalogId.toString()));

        mockMvc.perform(withAuth(put("/v1/products/catalog/{catalogId}", catalogId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(catalogPayload("Version C", "Third state")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(catalogId.toString()))
                .andExpect(jsonPath("$.name").value("Version C"));
    }

    @Test
    @DisplayName("CC-002: Independent creates generate distinct catalog ids")
    void testCatalogCreates_GenerateDistinctIds() throws Exception {
        UUID firstId = createCatalogAndReturnId("Catalog One", "Payload one");
        UUID secondId = createCatalogAndReturnId("Catalog Two", "Payload two");

        assertNotNull(firstId);
        assertNotNull(secondId);
        assertNotEquals(firstId, secondId);
    }

        @Test
        @DisplayName("CC-003: Sequential supplier-item cost updates preserve supplier-item identity")
        void testSupplierItemCostSequentialUpdates_PreserveIdentity() throws Exception {
                UUID supplierId = UUID.randomUUID();
                UUID itemId = UUID.randomUUID();
                createSupplierItemCost(supplierId, itemId);
                Long versionAfterCreate = getSupplierItemCostVersion(supplierId, itemId);

                mockMvc.perform(withAuth(put("/v1/products/costs/supplier-item/{supplierId}/{itemId}", supplierId, itemId))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(updatedSupplierItemCostPayload(versionAfterCreate)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath(SUPPLIER_ID).value(supplierId.toString()))
                                .andExpect(jsonPath("$.itemId").value(itemId.toString()));

                Long versionAfterFirstUpdate = getSupplierItemCostVersion(supplierId, itemId);

                mockMvc.perform(withAuth(put("/v1/products/costs/supplier-item/{supplierId}/{itemId}", supplierId, itemId))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(validUpdatePayloadWithDifferentValues(versionAfterFirstUpdate)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath(SUPPLIER_ID).value(supplierId.toString()))
                                .andExpect(jsonPath("$.itemId").value(itemId.toString()));
        }

    private UUID createCatalogAndReturnId(String name, String description) throws Exception {
        MvcResult result = mockMvc.perform(withAuth(post("/v1/products/catalog"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(catalogPayload(name, description)))
                .andExpect(status().isCreated())
                .andReturn();

        @SuppressWarnings("unchecked")
        Map<String, Object> response = objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
        return UUID.fromString((String) response.get("id"));
    }

    private String catalogPayload(String name, String description) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "name", name,
                "description", description));
    }

    private void createSupplierItemCost(UUID supplierId, UUID itemId) throws Exception {
        mockMvc.perform(withAuth(post("/v1/products/costs/supplier-item"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(validSupplierItemCostPayload(supplierId, itemId)))
                .andExpect(status().isCreated());
    }

    private String validSupplierItemCostPayload(UUID supplierId, UUID itemId) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "supplierId", supplierId,
                "itemId", itemId,
                "currencyCode", "USD",
                "baseCost", 6.25,
                "tiers", java.util.List.of(
                        Map.of("minQuantity", 1, "maxQuantity", 10, "unitCost", 5.00),
                        Map.of("minQuantity", 11, "maxQuantity", 50, "unitCost", 4.50),
                        Map.of("minQuantity", 51, "unitCost", 4.00))));
    }

    private String updatedSupplierItemCostPayload(Long version) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "version", version,
                "currencyCode", "USD",
                "baseCost", 6.50,
                "tiers", java.util.List.of(
                        Map.of("minQuantity", 1, "maxQuantity", 25, "unitCost", 5.25),
                        Map.of("minQuantity", 26, "unitCost", 4.75))));
    }

    private String overlappingTierPayload(UUID supplierId, UUID itemId) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "supplierId", supplierId,
                "itemId", itemId,
                "currencyCode", "USD",
                "tiers", java.util.List.of(
                        Map.of("minQuantity", 1, "maxQuantity", 10, "unitCost", 5.00),
                        Map.of("minQuantity", 5, "maxQuantity", 15, "unitCost", 4.75),
                        Map.of("minQuantity", 16, "unitCost", 4.50))));
    }

    private String gapTierPayload(UUID supplierId, UUID itemId) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "supplierId", supplierId,
                "itemId", itemId,
                "currencyCode", "USD",
                "tiers", java.util.List.of(
                        Map.of("minQuantity", 1, "maxQuantity", 10, "unitCost", 5.00),
                        Map.of("minQuantity", 12, "maxQuantity", 20, "unitCost", 4.60),
                        Map.of("minQuantity", 21, "unitCost", 4.20))));
    }

    private String invalidUnitCostPayload(UUID supplierId, UUID itemId) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "supplierId", supplierId,
                "itemId", itemId,
                "currencyCode", "USD",
                "tiers", java.util.List.of(
                        Map.of("minQuantity", 1, "maxQuantity", 10, "unitCost", 0.00),
                        Map.of("minQuantity", 11, "unitCost", 4.10))));
    }

    private String validUpdatePayloadWithDifferentValues(Long version) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "version", version,
                "currencyCode", "USD",
                "baseCost", 6.80,
                "tiers", java.util.List.of(
                        Map.of("minQuantity", 1, "maxQuantity", 30, "unitCost", 5.10),
                        Map.of("minQuantity", 31, "unitCost", 4.55))));
    }

    private Long getSupplierItemCostVersion(UUID supplierId, UUID itemId) throws Exception {
        MvcResult result = mockMvc.perform(withAuth(get("/v1/products/costs/supplier-item/{supplierId}/{itemId}", supplierId, itemId)))
                .andExpect(status().isOk())
                .andReturn();

        @SuppressWarnings("unchecked")
        Map<String, Object> response = objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
        return ((Number) response.get("version")).longValue();
    }
}
