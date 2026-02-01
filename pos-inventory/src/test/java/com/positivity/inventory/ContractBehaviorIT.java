package com.positivity.inventory;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.ObjectMapper;

/**
 * Contract Behavioral Integration Tests for Inventory Service
 *
 * This test suite validates the behavioral contracts defined in:
 * durion/domains/inventory/.business-rules/BACKEND_CONTRACT_GUIDE.md
 *
 * Contract Status: draft
 * Scope: Happy path, validation errors, idempotency, concurrency-safe
 * invariants
 *
 * Each test maps to a scenario defined in the contract guide's "Examples"
 * section.
 * Tests run against the actual service in test mode (not mocked).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Inventory Management Backend Contract Behavioral Tests")
class ContractBehaviorIT {

        @Autowired
        private MockMvc mockMvc;

        @Autowired
        private ObjectMapper objectMapper;

        private String inventoryPayload;
        private String existingInventoryId;

        @BeforeEach
        void setUp() throws Exception {
                existingInventoryId = "inv-" + System.currentTimeMillis();
                inventoryPayload = createInventoryPayload("PROD-001", "LOC-001", 100, 20, 80);
        }

        // ========== HAPPY PATH TESTS ==========

        @Test
        @DisplayName("CP-001: Successfully create inventory availability with valid data")
        void testCreateInventoryAvailability_HappyPath() throws Exception {
                String payload = createInventoryPayload("PROD-002", "LOC-002", 50, 10, 40);

                mockMvc.perform(post("/api/v1/inventory/availability")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload)
                                .header("X-Correlation-Id", "test-001"))
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.id").exists())
                                .andExpect(jsonPath("$.productId").value("PROD-002"))
                                .andExpect(jsonPath("$.onHandQty").value(50))
                                .andExpect(jsonPath("$.atpQty").value(40))
                                .andExpect(jsonPath("$.createdAt").exists());
        }

        @Test
        @DisplayName("CP-002: Successfully retrieve inventory availability by ID")
        void testGetInventoryAvailability_HappyPath() throws Exception {
                // Create first
                MvcResult createResult = mockMvc.perform(post("/api/v1/inventory/availability")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(inventoryPayload)
                                .header("X-Correlation-Id", "test-002"))
                                .andExpect(status().isCreated())
                                .andReturn();

                String responseBody = createResult.getResponse().getContentAsString();
                String createdId = objectMapper.readTree(responseBody).get("id").asString();

                // Retrieve
                mockMvc.perform(get("/api/v1/inventory/availability/{id}", createdId)
                                .header("X-Correlation-Id", "test-002"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.id").value(createdId))
                                .andExpect(jsonPath("$.productId").value("PROD-001"))
                                .andExpect(jsonPath("$.locationId").value("LOC-001"))
                                .andExpect(jsonPath("$.version").exists());
        }

        // ========== VALIDATION ERROR TESTS ==========

        @Test
        @DisplayName("VE-001: Reject invalid request with missing required productId field")
        void testCreateInventory_MissingProductId() throws Exception {
                String invalidPayload = "{\"locationId\":\"LOC-001\",\"onHandQty\":100,\"allocatedQty\":20}";

                mockMvc.perform(post("/api/v1/inventory/availability")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(invalidPayload)
                                .header("X-Correlation-Id", "test-ve-001"))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }

        @Test
        @DisplayName("VE-002: Reject negative onHandQty quantity")
        void testCreateInventory_NegativeQuantity() throws Exception {
                String invalidPayload = createInventoryPayload("PROD-003", "LOC-003", -5, 0, -5);

                mockMvc.perform(post("/api/v1/inventory/availability")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(invalidPayload)
                                .header("X-Correlation-Id", "test-ve-002"))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }

        @Test
        @DisplayName("VE-003: Reject allocated quantity greater than on-hand quantity")
        void testCreateInventory_AllocatedGreaterThanOnHand() throws Exception {
                String invalidPayload = createInventoryPayload("PROD-004", "LOC-004", 30, 50, -20);

                mockMvc.perform(post("/api/v1/inventory/availability")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(invalidPayload)
                                .header("X-Correlation-Id", "test-ve-003"))
                                .andExpect(status().isConflict())
                                .andExpect(jsonPath("$.code").value("CONFLICT"));
        }

        // ========== IDEMPOTENCY TEST ==========

        @Test
        @DisplayName("ID-001: Idempotent create with same Idempotency-Key returns same resource")
        void testCreateInventory_Idempotent() throws Exception {
                String idempotencyKey = "idem-key-" + System.currentTimeMillis();
                String payload = createInventoryPayload("PROD-005", "LOC-005", 75, 15, 60);

                MvcResult result1 = mockMvc.perform(post("/api/v1/inventory/availability")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload)
                                .header("X-Correlation-Id", "test-id-001")
                                .header("Idempotency-Key", idempotencyKey))
                                .andExpect(status().isCreated())
                                .andReturn();

                String id1 = objectMapper.readTree(result1.getResponse().getContentAsString()).get("id").asString();

                // Retry with same idempotency key
                MvcResult result2 = mockMvc.perform(post("/api/v1/inventory/availability")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload)
                                .header("X-Correlation-Id", "test-id-001")
                                .header("Idempotency-Key", idempotencyKey))
                                .andExpect(status().isCreated())
                                .andReturn();

                String id2 = objectMapper.readTree(result2.getResponse().getContentAsString()).get("id").asString();
                assert id1.equals(id2) : "Idempotent requests should return same resource ID";
        }

        // ========== CONCURRENCY INVARIANT TESTS ==========

        @Test
        @DisplayName("CC-001: Optimistic locking prevents concurrent updates with stale version")
        void testUpdateInventory_OptimisticLockingConflict() throws Exception {
                MvcResult createResult = mockMvc.perform(post("/api/v1/inventory/availability")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(inventoryPayload)
                                .header("X-Correlation-Id", "test-cc-001"))
                                .andExpect(status().isCreated())
                                .andReturn();

                String responseBody = createResult.getResponse().getContentAsString();
                String id = objectMapper.readTree(responseBody).get("id").asString();

                String updatePayload = "{\"onHandQty\":120,\"allocatedQty\":20,\"version\":0}";

                mockMvc.perform(patch("/api/v1/inventory/availability/{id}", id)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(updatePayload)
                                .header("X-Correlation-Id", "test-cc-001"))
                                .andExpect(status().isConflict())
                                .andExpect(jsonPath("$.code").value("CONFLICT"));
        }

        @Test
        @DisplayName("CC-002: ATP invariant maintained (ATP = On-Hand - Allocated)")
        void testInventory_ATPInvariant() throws Exception {
                String payload = createInventoryPayload("PROD-006", "LOC-006", 100, 30, 70);

                MvcResult result = mockMvc.perform(post("/api/v1/inventory/availability")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload)
                                .header("X-Correlation-Id", "test-cc-002"))
                                .andExpect(status().isCreated())
                                .andReturn();

                String responseBody = result.getResponse().getContentAsString();
                int onHandQty = objectMapper.readTree(responseBody).get("onHandQty").asInt();
                int allocatedQty = objectMapper.readTree(responseBody).get("allocatedQty").asInt();
                int atpQty = objectMapper.readTree(responseBody).get("atpQty").asInt();

                assert atpQty == (onHandQty - allocatedQty) : "ATP should equal onHandQty - allocatedQty";
        }

        // ========== FIELD FORMAT VALIDATION TESTS ==========

        @Test
        @DisplayName("FF-001: ISO 8601 timestamp format validation for createdAt")
        void testInventory_TimestampFormat() throws Exception {
                MvcResult result = mockMvc.perform(post("/api/v1/inventory/availability")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(inventoryPayload)
                                .header("X-Correlation-Id", "test-ff-001"))
                                .andExpect(status().isCreated())
                                .andReturn();

                String responseBody = result.getResponse().getContentAsString();
                String createdAt = objectMapper.readTree(responseBody).get("createdAt").asString();

                // Verify ISO 8601 UTC format: yyyy-MM-dd'T'HH:mm:ss'Z'
                assert createdAt.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,3})?Z")
                                : "createdAt must be ISO 8601 UTC format";
        }

        @Test
        @DisplayName("FF-002: Valid UOM enum value in inventory record")
        void testInventory_ValidUOMEnum() throws Exception {
                String payload = createInventoryPayloadWithUom("PROD-007", "LOC-007", 60, 10, "EACH");

                MvcResult result = mockMvc.perform(post("/api/v1/inventory/availability")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload)
                                .header("X-Correlation-Id", "test-ff-002"))
                                .andExpect(status().isCreated())
                                .andReturn();

                String responseBody = result.getResponse().getContentAsString();
                String uom = objectMapper.readTree(responseBody).get("uom").asString();
                assert uom.equals("EACH") : "UOM should be valid enum value";
        }

        // ========== Test Data Factories ==========

        private String createInventoryPayload(String productId, String locationId, int onHandQty, int allocatedQty,
                        int expectedAtp) {
                return String.format(
                                "{\"productId\":\"%s\",\"locationId\":\"%s\",\"onHandQty\":%d,\"allocatedQty\":%d,\"atpQty\":%d,\"uom\":\"EACH\",\"status\":\"ACTIVE\"}",
                                productId, locationId, onHandQty, allocatedQty, expectedAtp);
        }

        private String createInventoryPayloadWithUom(String productId, String locationId, int onHandQty,
                        int allocatedQty,
                        String uom) {
                return String.format(
                                "{\"productId\":\"%s\",\"locationId\":\"%s\",\"onHandQty\":%d,\"allocatedQty\":%d,\"atpQty\":%d,\"uom\":\"%s\",\"status\":\"ACTIVE\"}",
                                productId, locationId, onHandQty, allocatedQty, (onHandQty - allocatedQty), uom);
        }
}
