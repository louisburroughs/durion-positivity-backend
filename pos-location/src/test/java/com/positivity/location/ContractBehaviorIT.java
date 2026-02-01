package com.positivity.location;

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
 * Contract Behavioral Integration Tests for Location Service
 *
 * This test suite validates the behavioral contracts defined in:
 * durion/domains/location/.business-rules/BACKEND_CONTRACT_GUIDE.md
 *
 * Contract Status: draft
 * Scope: Happy path, validation errors, idempotency, concurrency-safe
 * invariants
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Location Backend Contract Behavioral Tests")
class ContractBehaviorIT {

        @Autowired
        private MockMvc mockMvc;

        @Autowired
        private ObjectMapper objectMapper;

        @BeforeEach
        void setUp() {
                // Test setup
        }

        // ========== HAPPY PATH TESTS ==========

        @Test
        @DisplayName("CP-001: Successfully create location with valid data")
        void testCreateLocation_HappyPath() throws Exception {
                String payload = createLocationPayload("Main Store", "123 Main St", "ACTIVE");

                mockMvc.perform(post("/api/v1/locations")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload)
                                .header("X-Correlation-Id", "test-001"))
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.id").exists())
                                .andExpect(jsonPath("$.name").value("Main Store"))
                                .andExpect(jsonPath("$.address").value("123 Main St"))
                                .andExpect(jsonPath("$.status").value("ACTIVE"))
                                .andExpect(jsonPath("$.createdAt").exists());
        }

        @Test
        @DisplayName("CP-002: Successfully retrieve location by ID")
        void testGetLocation_HappyPath() throws Exception {
                String payload = createLocationPayload("Secondary Store", "456 Oak Ave", "ACTIVE");

                MvcResult createResult = mockMvc.perform(post("/api/v1/locations")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload)
                                .header("X-Correlation-Id", "test-002"))
                                .andExpect(status().isCreated())
                                .andReturn();

                String responseBody = createResult.getResponse().getContentAsString();
                String locationId = objectMapper.readTree(responseBody).get("id").asString();

                mockMvc.perform(get("/api/v1/locations/{id}", locationId)
                                .header("X-Correlation-Id", "test-002"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.id").value(locationId))
                                .andExpect(jsonPath("$.name").value("Secondary Store"))
                                .andExpect(jsonPath("$.status").value("ACTIVE"));
        }

        // ========== VALIDATION ERROR TESTS ==========

        @Test
        @DisplayName("VE-001: Reject location with missing required name field")
        void testCreateLocation_MissingName() throws Exception {
                String invalidPayload = "{\"address\":\"789 Elm St\",\"status\":\"ACTIVE\"}";

                mockMvc.perform(post("/api/v1/locations")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(invalidPayload)
                                .header("X-Correlation-Id", "test-ve-001"))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }

        @Test
        @DisplayName("VE-002: Reject duplicate location name")
        void testCreateLocation_DuplicateName() throws Exception {
                String payload = createLocationPayload("Unique Store", "999 Test Rd", "ACTIVE");

                // Create first location
                mockMvc.perform(post("/api/v1/locations")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload)
                                .header("X-Correlation-Id", "test-ve-002a"))
                                .andExpect(status().isCreated());

                // Try to create duplicate
                mockMvc.perform(post("/api/v1/locations")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload)
                                .header("X-Correlation-Id", "test-ve-002b"))
                                .andExpect(status().isConflict())
                                .andExpect(jsonPath("$.code").value("CONFLICT"));
        }

        @Test
        @DisplayName("VE-003: Reject invalid status enum value")
        void testCreateLocation_InvalidStatus() throws Exception {
                String invalidPayload = "{\"name\":\"Bad Store\",\"address\":\"111 Bad St\",\"status\":\"INVALID_STATUS\"}";

                mockMvc.perform(post("/api/v1/locations")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(invalidPayload)
                                .header("X-Correlation-Id", "test-ve-003"))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }

        // ========== IDEMPOTENCY TEST ==========

        @Test
        @DisplayName("ID-001: Idempotent create with same Idempotency-Key returns same resource")
        void testCreateLocation_Idempotent() throws Exception {
                String idempotencyKey = "idem-loc-" + System.currentTimeMillis();
                String payload = createLocationPayload("Idempotent Store", "222 Same St", "ACTIVE");

                MvcResult result1 = mockMvc.perform(post("/api/v1/locations")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload)
                                .header("X-Correlation-Id", "test-id-001")
                                .header("Idempotency-Key", idempotencyKey))
                                .andExpect(status().isCreated())
                                .andReturn();

                String id1 = objectMapper.readTree(result1.getResponse().getContentAsString()).get("id").asString();

                // Retry with same key
                MvcResult result2 = mockMvc.perform(post("/api/v1/locations")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload)
                                .header("X-Correlation-Id", "test-id-001")
                                .header("Idempotency-Key", idempotencyKey))
                                .andExpect(status().isCreated())
                                .andReturn();

                String id2 = objectMapper.readTree(result2.getResponse().getContentAsString()).get("id").asString();
                assert id1.equals(id2) : "Idempotent requests should return same location ID";
        }

        // ========== CONCURRENCY INVARIANT TESTS ==========

        @Test
        @DisplayName("CC-001: Optimistic locking prevents concurrent updates with stale version")
        void testUpdateLocation_OptimisticLockingConflict() throws Exception {
                String payload = createLocationPayload("Concurrency Store", "333 Concurrent Ave", "ACTIVE");

                MvcResult createResult = mockMvc.perform(post("/api/v1/locations")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload)
                                .header("X-Correlation-Id", "test-cc-001"))
                                .andExpect(status().isCreated())
                                .andReturn();

                String responseBody = createResult.getResponse().getContentAsString();
                String locationId = objectMapper.readTree(responseBody).get("id").asString();

                String updatePayload = "{\"name\":\"Updated Store\",\"status\":\"ACTIVE\",\"version\":0}";

                mockMvc.perform(patch("/api/v1/locations/{id}", locationId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(updatePayload)
                                .header("X-Correlation-Id", "test-cc-001"))
                                .andExpect(status().isConflict())
                                .andExpect(jsonPath("$.code").value("CONFLICT"));
        }

        @Test
        @DisplayName("CC-002: Location state cannot transition to invalid status")
        void testUpdateLocation_InvalidStateTransition() throws Exception {
                String payload = createLocationPayload("State Store", "444 State Rd", "ACTIVE");

                MvcResult createResult = mockMvc.perform(post("/api/v1/locations")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload)
                                .header("X-Correlation-Id", "test-cc-002"))
                                .andExpect(status().isCreated())
                                .andReturn();

                String locationId = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id")
                                .asString();

                // Try invalid state transition
                String invalidUpdatePayload = "{\"name\":\"State Store\",\"status\":\"INVALID\"}";

                mockMvc.perform(patch("/api/v1/locations/{id}", locationId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(invalidUpdatePayload)
                                .header("X-Correlation-Id", "test-cc-002"))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }

        // ========== FIELD FORMAT VALIDATION TESTS ==========

        @Test
        @DisplayName("FF-001: ISO 8601 timestamp format validation for createdAt")
        void testLocation_TimestampFormat() throws Exception {
                String payload = createLocationPayload("Timestamp Store", "555 Time Blvd", "ACTIVE");

                MvcResult result = mockMvc.perform(post("/api/v1/locations")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload)
                                .header("X-Correlation-Id", "test-ff-001"))
                                .andExpect(status().isCreated())
                                .andReturn();

                String responseBody = result.getResponse().getContentAsString();
                String createdAt = objectMapper.readTree(responseBody).get("createdAt").asString();

                assert createdAt.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,3})?Z")
                                : "createdAt must be ISO 8601 UTC format";
        }

        @Test
        @DisplayName("FF-002: Valid status enum values (ACTIVE, INACTIVE, PENDING_APPROVAL)")
        void testLocation_ValidStatusEnums() throws Exception {
                String[] validStatuses = { "ACTIVE", "INACTIVE", "PENDING_APPROVAL" };

                for (String status : validStatuses) {
                        String payload = createLocationPayload("Status Store " + status, "666 Status Way", status);

                        mockMvc.perform(post("/api/v1/locations")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(payload)
                                        .header("X-Correlation-Id", "test-ff-002-" + status))
                                        .andExpect(status().isCreated())
                                        .andExpect(jsonPath("$.status").value(status));
                }
        }

        // ========== Test Data Factories ==========

        private String createLocationPayload(String name, String address, String status) {
                return String.format(
                                "{\"name\":\"%s\",\"address\":\"%s\",\"status\":\"%s\"}",
                                name, address, status);
        }
}
