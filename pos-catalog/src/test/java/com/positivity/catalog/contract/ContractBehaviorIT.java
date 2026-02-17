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
}
