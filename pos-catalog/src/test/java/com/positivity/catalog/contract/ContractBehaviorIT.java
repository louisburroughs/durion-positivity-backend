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
import java.math.BigDecimal;
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

        @Test
        @DisplayName("CP-006: Auto-approval creates ACTIVE location override")
        void testCreateLocationOverride_AutoApproved() throws Exception {
                UUID locationId = UUID.randomUUID();
                UUID productId = UUID.randomUUID();
                UUID actorId = UUID.randomUUID();

                upsertLocationGuardrailPolicy(locationId, new BigDecimal("15.0"), new BigDecimal("25.0"),
                                new BigDecimal("10.0"));

                mockMvc.perform(withAuth(post("/v1/products/pricing/location-overrides"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(locationOverridePayload(locationId, productId, actorId, "100.00", "50.00",
                                                "95.00")))
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.status").value("ACTIVE"))
                                .andExpect(jsonPath("$.overridePrice").value(95.00));

                mockMvc.perform(withAuth(get("/v1/products/pricing/effective-price/{locationId}/{productId}",
                                locationId, productId)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.effectivePrice").value(95.00))
                                .andExpect(jsonPath("$.overrideStatus").value("ACTIVE"));
        }

        @Test
        @DisplayName("CP-007: Override beyond threshold creates PENDING_APPROVAL and keeps base as effective")
        void testCreateLocationOverride_PendingApproval() throws Exception {
                UUID locationId = UUID.randomUUID();
                UUID productId = UUID.randomUUID();
                UUID actorId = UUID.randomUUID();

                upsertLocationGuardrailPolicy(locationId, new BigDecimal("15.0"), new BigDecimal("25.0"),
                                new BigDecimal("10.0"));

                mockMvc.perform(withAuth(post("/v1/products/pricing/location-overrides"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(locationOverridePayload(locationId, productId, actorId, "100.00", "50.00",
                                                "88.00")))
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                                .andExpect(jsonPath("$.assignedApproverId").exists())
                                .andExpect(jsonPath("$.assignmentStrategy").value("LOCATION_SCOPE_PRIMARY_THEN_POOL"));

                mockMvc.perform(withAuth(get("/v1/products/pricing/effective-price/{locationId}/{productId}",
                                locationId, productId)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.effectivePrice").value(100.00))
                                .andExpect(jsonPath("$.overrideStatus").value("PENDING_APPROVAL"));
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
        @DisplayName("VE-005: Reject override below minimum margin hard guardrail")
        void testCreateLocationOverride_BelowMinMarginRejected() throws Exception {
                UUID locationId = UUID.randomUUID();
                UUID productId = UUID.randomUUID();
                UUID actorId = UUID.randomUUID();

                upsertLocationGuardrailPolicy(locationId, new BigDecimal("15.0"), new BigDecimal("60.0"),
                                new BigDecimal("10.0"));

                mockMvc.perform(withAuth(post("/v1/products/pricing/location-overrides"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(locationOverridePayload(locationId, productId, actorId, "100.00", "50.00",
                                                "55.00")))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$")
                                                .value(org.hamcrest.Matchers.containsString("MIN_MARGIN_VIOLATION")));
        }

        @Test
        @DisplayName("VE-006: Create override forbidden without edit role")
        void testCreateLocationOverride_ForbiddenWithoutEditRole() throws Exception {
                UUID locationId = UUID.randomUUID();
                UUID productId = UUID.randomUUID();
                UUID actorId = UUID.randomUUID();

                upsertLocationGuardrailPolicy(locationId, new BigDecimal("15.0"), new BigDecimal("25.0"),
                                new BigDecimal("10.0"));

                mockMvc.perform(withAuth(post("/v1/products/pricing/location-overrides"), "ROLE_CATALOG_VIEW")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(locationOverridePayload(locationId, productId, actorId, "100.00", "50.00",
                                                "95.00")))
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
        @DisplayName("ID-003: Repeated effective price GET returns stable result")
        void testGetEffectivePrice_IdempotentRead() throws Exception {
                UUID locationId = UUID.randomUUID();
                UUID productId = UUID.randomUUID();
                UUID actorId = UUID.randomUUID();

                upsertLocationGuardrailPolicy(locationId, new BigDecimal("15.0"), new BigDecimal("25.0"),
                                new BigDecimal("10.0"));

                mockMvc.perform(withAuth(post("/v1/products/pricing/location-overrides"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(locationOverridePayload(locationId, productId, actorId, "100.00", "50.00",
                                                "95.00")))
                                .andExpect(status().isCreated());

                mockMvc.perform(withAuth(get("/v1/products/pricing/effective-price/{locationId}/{productId}",
                                locationId, productId)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.effectivePrice").value(95.00));

                mockMvc.perform(withAuth(get("/v1/products/pricing/effective-price/{locationId}/{productId}",
                                locationId, productId)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.effectivePrice").value(95.00));
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
        @DisplayName("CC-003: Reject pending override enforces optimistic locking version")
        void testRejectOverride_OptimisticLock() throws Exception {
                UUID locationId = UUID.randomUUID();
                UUID productId = UUID.randomUUID();
                UUID creatorId = UUID.randomUUID();
                UUID approverId = UUID.randomUUID();

                upsertLocationGuardrailPolicy(locationId, new BigDecimal("15.0"), new BigDecimal("25.0"),
                                new BigDecimal("10.0"));

                MvcResult createResult = mockMvc.perform(withAuth(post("/v1/products/pricing/location-overrides"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(locationOverridePayload(locationId, productId, creatorId, "100.00", "50.00",
                                                "88.00")))
                                .andExpect(status().isCreated())
                                .andReturn();

                @SuppressWarnings("unchecked")
                Map<String, Object> created = objectMapper.readValue(createResult.getResponse().getContentAsString(),
                                Map.class);
                UUID overrideId = UUID.fromString((String) created.get("overrideId"));

                mockMvc.perform(withAuth(
                                post("/v1/products/pricing/location-overrides/{overrideId}/reject", overrideId),
                                "ROLE_ADMIN,pricing:override:approve")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(rejectDecisionPayload(approverId, 99)))
                                .andExpect(status().isConflict());
        }

        private UUID createCatalogAndReturnId(String name, String description) throws Exception {
                MvcResult result = mockMvc.perform(withAuth(post("/v1/products/catalog"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(catalogPayload(name, description)))
                                .andExpect(status().isCreated())
                                .andReturn();

                @SuppressWarnings("unchecked")
                Map<String, Object> response = objectMapper.readValue(result.getResponse().getContentAsString(),
                                Map.class);
                return UUID.fromString((String) response.get("id"));
        }

        private String catalogPayload(String name, String description) throws Exception {
                return objectMapper.writeValueAsString(Map.of(
                                "name", name,
                                "description", description));
        }

        private void upsertLocationGuardrailPolicy(UUID locationId, BigDecimal minMarginPercent,
                        BigDecimal maxDiscountPercent, BigDecimal autoApprovalThresholdPercent) throws Exception {
                mockMvc.perform(withAuth(post("/v1/products/pricing/guardrail-policies"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of(
                                                "scopeId", locationId,
                                                "minMarginPercent", minMarginPercent,
                                                "maxDiscountPercent", maxDiscountPercent,
                                                "autoApprovalThresholdPercent", autoApprovalThresholdPercent))))
                                .andExpect(status().isOk());
        }

        private String locationOverridePayload(UUID locationId, UUID productId, UUID actorId, String basePrice,
                        String cost,
                        String overridePrice) throws Exception {
                return objectMapper.writeValueAsString(Map.of(
                                "locationId", locationId,
                                "productId", productId,
                                "basePrice", new BigDecimal(basePrice),
                                "cost", new BigDecimal(cost),
                                "overridePrice", new BigDecimal(overridePrice),
                                "createdByUserId", actorId));
        }

        private String rejectDecisionPayload(UUID actorId, int version) throws Exception {
                return objectMapper.writeValueAsString(Map.of(
                                "version", version,
                                "actorUserId", actorId,
                                "rejectionReasonCode", "OUTSIDE_POLICY",
                                "rejectionNotes", "Rejected in contract test"));
        }
}
