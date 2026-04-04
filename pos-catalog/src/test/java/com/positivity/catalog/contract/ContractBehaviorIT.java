package com.positivity.catalog.contract;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.catalog.BaseContractIntegrationTest;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

@DisplayName("Catalog Backend Contract Behavioral Tests")
class ContractBehaviorIT extends BaseContractIntegrationTest {

        // ===============================================
        // HAPPY PATH SCENARIOS
        // ===============================================

        private static final String SUPPLIER_ID = "$.supplierId";

        @Test
        @DisplayName("CP-001: Create catalog with valid payload")
        void testCreateCatalog_HappyPath() throws Exception {
                mockMvc.perform(withAuth(post("/v1/catalogs"))
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

                mockMvc.perform(withAuth(get("/v1/catalogs/{catalogId}", catalogId)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.id").value(catalogId.toString()))
                                .andExpect(jsonPath("$.name").value("Service Catalog"));
        }

        @Test
        @DisplayName("CP-003: Update catalog and return updated payload")
        void testUpdateCatalog_HappyPath() throws Exception {
                UUID catalogId = createCatalogAndReturnId("Initial Name", "Initial description");

                mockMvc.perform(withAuth(put("/v1/catalogs/{catalogId}", catalogId))
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

                mockMvc.perform(withAuth(delete("/v1/catalogs/{catalogId}", catalogId)))
                                .andExpect(status().isNoContent());

                mockMvc.perform(withAuth(get("/v1/catalogs/{catalogId}", catalogId)))
                                .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("CP-005: Substitutes endpoint returns empty list when no replacements configured")
        void testGetSubstitutes_EmptyList() throws Exception {
                UUID productId = createProductAndReturnId("CP-005 Product");

                mockMvc.perform(withAuth(get("/v1/products/{productId}/substitutes", productId)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.length()").value(0));
        }

        @Test
        @DisplayName("CP-006: Create supplier-item cost tiers with valid contiguous ranges")
        void testCreateSupplierItemCost_HappyPath() throws Exception {
                UUID supplierId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                UUID itemId = UUID.fromString("00000000-0000-0000-0000-000000000001");

                mockMvc.perform(withAuth(post("/v1/products/supplier-costs"))
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
                UUID supplierId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                UUID itemId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                UUID supplierItemCostId = createSupplierItemCost(supplierId, itemId);

                mockMvc.perform(withAuth(get("/v1/products/supplier-costs/{id}", supplierItemCostId)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath(SUPPLIER_ID).value(supplierId.toString()))
                                .andExpect(jsonPath("$.itemId").value(itemId.toString()))
                                .andExpect(jsonPath("$.tiers.length()").value(3));
        }

        @Test
        @DisplayName("CP-008: Update supplier-item cost tiers")
        void testUpdateSupplierItemCost_HappyPath() throws Exception {
                UUID supplierId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                UUID itemId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                UUID supplierItemCostId = createSupplierItemCost(supplierId, itemId);

                mockMvc.perform(withAuth(put("/v1/products/supplier-costs/{id}", supplierItemCostId))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(updatedSupplierItemCostPayload(supplierId, itemId)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.currencyCode").value("USD"))
                                .andExpect(jsonPath("$.baseCost").value(6.50))
                                .andExpect(jsonPath("$.tiers[0].minQuantity").value(1))
                                .andExpect(jsonPath("$.tiers[1].minQuantity").value(26));
        }

        @Test
        @DisplayName("CP-009: Delete supplier-item cost tiers")
        void testDeleteSupplierItemCost_HappyPath() throws Exception {
                UUID supplierId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                UUID itemId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                UUID supplierItemCostId = createSupplierItemCost(supplierId, itemId);

                mockMvc.perform(withAuth(delete("/v1/products/supplier-costs/{id}", supplierItemCostId)))
                                .andExpect(status().isNoContent());

                mockMvc.perform(withAuth(get("/v1/products/supplier-costs/{id}", supplierItemCostId)))
                                .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("CP-010: Create active location override within auto-approval threshold")
        void testCreateLocationOverride_ActiveWithinThreshold() throws Exception {
                UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                UUID productId = createProductAndReturnId("CP-010 Product");
                UUID actorUserId = UUID.fromString("00000000-0000-0000-0000-000000000001");

                upsertGuardrailPolicy(locationId, BigDecimal.valueOf(15), BigDecimal.valueOf(25),
                                BigDecimal.valueOf(10));

                mockMvc.perform(withAuth(post("/v1/products/pricing/location-overrides"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(locationOverridePayload(locationId, productId, actorUserId, 100.00, 50.00,
                                                95.00)))
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.status").value("ACTIVE"))
                                .andExpect(jsonPath("$.overridePrice").value(95.0))
                                .andExpect(jsonPath("$.discountPercent").value(5.0));

                mockMvc.perform(withAuth(get("/v1/products/pricing/effective-price/{locationId}/{productId}",
                                locationId, productId)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.overrideStatus").value("ACTIVE"))
                                .andExpect(jsonPath("$.basePrice").value(100.0))
                                .andExpect(jsonPath("$.effectivePrice").value(95.0));
        }

        @Test
        @DisplayName("CP-011: Create pending location override above auto-approval threshold")
        void testCreateLocationOverride_PendingApproval() throws Exception {
                UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                UUID productId = createProductAndReturnId("CP-011 Product");
                UUID actorUserId = UUID.fromString("00000000-0000-0000-0000-000000000001");

                upsertGuardrailPolicy(locationId, BigDecimal.valueOf(15), BigDecimal.valueOf(25),
                                BigDecimal.valueOf(10));

                mockMvc.perform(withAuth(post("/v1/products/pricing/location-overrides"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(locationOverridePayload(locationId, productId, actorUserId, 100.00, 50.00,
                                                88.00)))
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                                .andExpect(jsonPath("$.overridePrice").value(88.0))
                                .andExpect(jsonPath("$.assignedApproverId").exists())
                                .andExpect(jsonPath("$.assignmentStrategy").value("LOCATION_SCOPE_PRIMARY_THEN_POOL"));

                mockMvc.perform(withAuth(get("/v1/products/pricing/effective-price/{locationId}/{productId}",
                                locationId, productId)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.overrideStatus").value("PENDING_APPROVAL"))
                                .andExpect(jsonPath("$.basePrice").value(100.0))
                                .andExpect(jsonPath("$.effectivePrice").value(100.0));
        }

        @Test
        @DisplayName("CP-012: Approve pending location override and activate effective price")
        void testApproveLocationOverride_HappyPath() throws Exception {
                UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                UUID productId = createProductAndReturnId("CP-012 Product");
                UUID createdByUserId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                UUID approverUserId = UUID.fromString("00000000-0000-0000-0000-000000000001");

                upsertGuardrailPolicy(locationId, BigDecimal.valueOf(15), BigDecimal.valueOf(25),
                                BigDecimal.valueOf(10));
                Map<String, Object> createdOverride = createLocationOverrideAndReturnResponse(
                                locationId, productId, createdByUserId, 100.00, 50.00, 88.00, "ROLE_ADMIN");

                UUID overrideId = UUID.fromString((String) createdOverride.get("overrideId"));
                Long version = ((Number) createdOverride.get("version")).longValue();

                mockMvc.perform(withAuth(
                                post("/v1/products/pricing/location-overrides/{overrideId}/approve", overrideId),
                                "ROLE_ADMIN")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(approvalDecisionPayload(version, approverUserId)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.status").value("ACTIVE"))
                                .andExpect(jsonPath("$.approvedByUserId").value(approverUserId.toString()));

                mockMvc.perform(withAuth(get("/v1/products/pricing/effective-price/{locationId}/{productId}",
                                locationId, productId)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.overrideStatus").value("ACTIVE"))
                                .andExpect(jsonPath("$.effectivePrice").value(88.0));
        }

        @Test
        @DisplayName("CP-013: Reject pending location override and persist rejection metadata")
        void testRejectLocationOverride_HappyPath() throws Exception {
                UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                UUID productId = createProductAndReturnId("CP-013 Product");
                UUID createdByUserId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                UUID approverUserId = UUID.fromString("00000000-0000-0000-0000-000000000001");

                upsertGuardrailPolicy(locationId, BigDecimal.valueOf(15), BigDecimal.valueOf(25),
                                BigDecimal.valueOf(10));
                Map<String, Object> createdOverride = createLocationOverrideAndReturnResponse(
                                locationId, productId, createdByUserId, 100.00, 50.00, 88.00, "ROLE_ADMIN");

                UUID overrideId = UUID.fromString((String) createdOverride.get("overrideId"));
                Long version = ((Number) createdOverride.get("version")).longValue();

                mockMvc.perform(withAuth(
                                post("/v1/products/pricing/location-overrides/{overrideId}/reject", overrideId),
                                "ROLE_ADMIN")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(rejectionDecisionPayload(version, approverUserId, "THRESHOLD_EXCEEDED",
                                                "Needs regional approval review")))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.status").value("REJECTED"))
                                .andExpect(jsonPath("$.rejectedBy").value(approverUserId.toString()))
                                .andExpect(jsonPath("$.rejectionReasonCode").value("THRESHOLD_EXCEEDED"));
        }

        // ===============================================
        // VALIDATION ERROR SCENARIOS
        // ===============================================

        @Test
        @DisplayName("VE-001: Unknown catalog id returns 404")
        void testGetCatalogById_NotFound() throws Exception {
                mockMvc.perform(withAuth(get("/v1/catalogs/{catalogId}",
                                UUID.fromString("00000000-0000-0000-0000-000000000001"))))
                                .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("VE-002: Update non-existent catalog returns 404")
        void testUpdateCatalog_NotFound() throws Exception {
                mockMvc.perform(withAuth(put("/v1/catalogs/{catalogId}",
                                UUID.fromString("00000000-0000-0000-0000-000000000001")))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(catalogPayload("Missing", "Missing")))
                                .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("VE-003: Delete non-existent catalog returns 404")
        void testDeleteCatalog_NotFound() throws Exception {
                mockMvc.perform(withAuth(delete("/v1/catalogs/{catalogId}",
                                UUID.fromString("00000000-0000-0000-0000-000000000001"))))
                                .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("VE-004: Unsupported item type returns 400")
        void testUnsupportedType_BadRequest() throws Exception {
                mockMvc.perform(withAuth(
                                delete("/v1/catalog-items/{type}/{catalogId}", "unsupported",
                                                UUID.fromString("00000000-0000-0000-0000-000000000001"))))
                                .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("VE-005: Reject overlapping supplier-item cost tiers")
        void testCreateSupplierItemCost_OverlappingTiers() throws Exception {
                UUID supplierId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                UUID itemId = UUID.fromString("00000000-0000-0000-0000-000000000001");

                mockMvc.perform(withAuth(post("/v1/products/supplier-costs"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(overlappingTierPayload(supplierId, itemId)))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.message")
                                                .value(org.hamcrest.Matchers.containsString("INVALID_TIER_STRUCTURE")));
        }

        @Test
        @DisplayName("VE-006: Reject supplier-item cost tiers with quantity gap")
        void testCreateSupplierItemCost_GapTiers() throws Exception {
                UUID supplierId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                UUID itemId = UUID.fromString("00000000-0000-0000-0000-000000000001");

                mockMvc.perform(withAuth(post("/v1/products/supplier-costs"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(gapTierPayload(supplierId, itemId)))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.message")
                                                .value(org.hamcrest.Matchers
                                                                .containsString("tier ranges must be contiguous")));
        }

        @Test
        @DisplayName("VE-007: Reject supplier-item cost tiers with non-positive unit cost")
        void testCreateSupplierItemCost_InvalidUnitCost() throws Exception {
                UUID supplierId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                UUID itemId = UUID.fromString("00000000-0000-0000-0000-000000000001");

                mockMvc.perform(withAuth(post("/v1/products/supplier-costs"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(invalidUnitCostPayload(supplierId, itemId)))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.message")
                                                .value(org.hamcrest.Matchers
                                                                .containsString("unitCost must be positive.")));
        }

        @Test
        @DisplayName("VE-008: Missing supplier-item cost returns 404")
        void testGetSupplierItemCost_NotFound() throws Exception {
                mockMvc.perform(withAuth(get("/v1/products/supplier-costs/{id}",
                                UUID.fromString("00000000-0000-0000-0000-000000000001"))))
                                .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("VE-009: Create supplier-item cost forbidden without edit role")
        void testCreateSupplierItemCost_Forbidden() throws Exception {
                UUID supplierId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                UUID itemId = UUID.fromString("00000000-0000-0000-0000-000000000001");

                mockMvc.perform(withAuth(post("/v1/products/supplier-costs"), "ROLE_CATALOG_VIEW")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(validSupplierItemCostPayload(supplierId, itemId)))
                                .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("VE-010: Hard guardrail minimum margin violation returns 400")
        void testCreateLocationOverride_MinMarginViolation() throws Exception {
                UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                UUID productId = createProductAndReturnId("VE-010 Product");

                upsertGuardrailPolicy(locationId, BigDecimal.valueOf(15), BigDecimal.valueOf(25),
                                BigDecimal.valueOf(10));

                mockMvc.perform(withAuth(post("/v1/products/pricing/location-overrides"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(locationOverridePayload(locationId, productId,
                                                UUID.fromString("00000000-0000-0000-0000-000000000001"), 100.00,
                                                70.00, 75.00)))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.message")
                                                .value(org.hamcrest.Matchers.containsString("MIN_MARGIN_VIOLATION")));
        }

        @Test
        @DisplayName("VE-011: Hard guardrail max discount violation returns 400")
        void testCreateLocationOverride_MaxDiscountViolation() throws Exception {
                UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                UUID productId = createProductAndReturnId("VE-011 Product");

                upsertGuardrailPolicy(locationId, BigDecimal.valueOf(15), BigDecimal.valueOf(25),
                                BigDecimal.valueOf(10));

                mockMvc.perform(withAuth(post("/v1/products/pricing/location-overrides"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(locationOverridePayload(locationId, productId,
                                                UUID.fromString("00000000-0000-0000-0000-000000000001"), 100.00,
                                                50.00, 70.00)))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.message")
                                                .value(org.hamcrest.Matchers.containsString("MAX_DISCOUNT_EXCEEDED")));
        }

        @Test
        @DisplayName("VE-012: Invalid product for location override returns 404")
        void testCreateLocationOverride_ProductNotFound() throws Exception {
                UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");

                upsertGuardrailPolicy(locationId, BigDecimal.valueOf(15), BigDecimal.valueOf(25),
                                BigDecimal.valueOf(10));

                mockMvc.perform(withAuth(post("/v1/products/pricing/location-overrides"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(locationOverridePayload(locationId,
                                                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                                                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                                                100.00, 50.00, 95.00)))
                                .andExpect(status().isNotFound())
                                .andExpect(jsonPath("$.message")
                                                .value(org.hamcrest.Matchers.containsString("PRODUCT_NOT_FOUND")));
        }

        @Test
        @DisplayName("VE-013: Unauthorized role cannot create location override")
        void testCreateLocationOverride_Forbidden() throws Exception {
                UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                UUID productId = createProductAndReturnId("VE-013 Product");

                upsertGuardrailPolicy(locationId, BigDecimal.valueOf(15), BigDecimal.valueOf(25),
                                BigDecimal.valueOf(10));

                mockMvc.perform(withAuth(post("/v1/products/pricing/location-overrides"), "ROLE_CATALOG_VIEW")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(locationOverridePayload(locationId, productId,
                                                UUID.fromString("00000000-0000-0000-0000-000000000001"), 100.00,
                                                50.00, 95.00)))
                                .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("VE-014: Reject override requires rejection notes")
        void testRejectLocationOverride_MissingNotes() throws Exception {
                UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                UUID productId = createProductAndReturnId("VE-014 Product");
                UUID createdByUserId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                UUID approverUserId = UUID.fromString("00000000-0000-0000-0000-000000000001");

                upsertGuardrailPolicy(locationId, BigDecimal.valueOf(15), BigDecimal.valueOf(25),
                                BigDecimal.valueOf(10));
                Map<String, Object> createdOverride = createLocationOverrideAndReturnResponse(
                                locationId, productId, createdByUserId, 100.00, 50.00, 88.00, "ROLE_ADMIN");

                UUID overrideId = UUID.fromString((String) createdOverride.get("overrideId"));
                Long version = ((Number) createdOverride.get("version")).longValue();

                mockMvc.perform(withAuth(
                                post("/v1/products/pricing/location-overrides/{overrideId}/reject", overrideId),
                                "ROLE_ADMIN")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(rejectionDecisionPayload(version, approverUserId, "THRESHOLD_EXCEEDED", "")))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.message")
                                                .value(org.hamcrest.Matchers.containsString("rejectionNotes")));
        }

        // ===============================================
        // IDEMPOTENCY SCENARIOS
        // ===============================================

        @Test
        @DisplayName("ID-001: Repeated GET for same catalog returns stable response")
        void testGetCatalog_IdempotentRead() throws Exception {
                UUID catalogId = createCatalogAndReturnId("Stable Catalog", "Stable payload");

                mockMvc.perform(withAuth(get("/v1/catalogs/{catalogId}", catalogId)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.id").value(catalogId.toString()))
                                .andExpect(jsonPath("$.name").value("Stable Catalog"));

                mockMvc.perform(withAuth(get("/v1/catalogs/{catalogId}", catalogId)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.id").value(catalogId.toString()))
                                .andExpect(jsonPath("$.name").value("Stable Catalog"));
        }

        @Test
        @DisplayName("ID-002: Repeated DELETE keeps resource absent")
        void testDeleteCatalog_RepeatedRequest() throws Exception {
                UUID catalogId = createCatalogAndReturnId("Delete Twice", "Delete scenario");

                mockMvc.perform(withAuth(delete("/v1/catalogs/{catalogId}", catalogId)))
                                .andExpect(status().isNoContent());

                mockMvc.perform(withAuth(delete("/v1/catalogs/{catalogId}", catalogId)))
                                .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("ID-003: Repeated GET for supplier-item cost returns stable response")
        void testGetSupplierItemCost_IdempotentRead() throws Exception {
                UUID supplierId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                UUID itemId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                UUID supplierItemCostId = createSupplierItemCost(supplierId, itemId);

                mockMvc.perform(withAuth(get("/v1/products/supplier-costs/{id}", supplierItemCostId)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath(SUPPLIER_ID).value(supplierId.toString()))
                                .andExpect(jsonPath("$.itemId").value(itemId.toString()));

                mockMvc.perform(withAuth(get("/v1/products/supplier-costs/{id}", supplierItemCostId)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath(SUPPLIER_ID).value(supplierId.toString()))
                                .andExpect(jsonPath("$.itemId").value(itemId.toString()));
        }

        @Test
        @DisplayName("ID-004: Repeated effective price lookup returns stable payload")
        void testEffectivePrice_IdempotentRead() throws Exception {
                UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                UUID productId = createProductAndReturnId("ID-004 Product");

                upsertGuardrailPolicy(locationId, BigDecimal.valueOf(15), BigDecimal.valueOf(25),
                                BigDecimal.valueOf(10));
                createLocationOverrideAndReturnResponse(locationId, productId,
                                UUID.fromString("00000000-0000-0000-0000-000000000001"), 100.00, 50.00, 95.00,
                                "ROLE_ADMIN");

                mockMvc.perform(withAuth(get("/v1/products/pricing/effective-price/{locationId}/{productId}",
                                locationId, productId)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.overrideStatus").value("ACTIVE"))
                                .andExpect(jsonPath("$.effectivePrice").value(95.0));

                mockMvc.perform(withAuth(get("/v1/products/pricing/effective-price/{locationId}/{productId}",
                                locationId, productId)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.overrideStatus").value("ACTIVE"))
                                .andExpect(jsonPath("$.effectivePrice").value(95.0));
        }

        // ===============================================
        // CONCURRENCY-SAFE INVARIANTS
        // ===============================================

        @Test
        @DisplayName("CC-001: Sequential updates preserve catalog identity")
        void testCatalogSequentialUpdates_PreserveIdentity() throws Exception {
                UUID catalogId = createCatalogAndReturnId("Version A", "First state");

                mockMvc.perform(withAuth(put("/v1/catalogs/{catalogId}", catalogId))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(catalogPayload("Version B", "Second state")))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.id").value(catalogId.toString()));

                mockMvc.perform(withAuth(put("/v1/catalogs/{catalogId}", catalogId))
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
                UUID supplierId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                UUID itemId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                UUID supplierItemCostId = createSupplierItemCost(supplierId, itemId);

                mockMvc.perform(withAuth(put("/v1/products/supplier-costs/{id}", supplierItemCostId))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(updatedSupplierItemCostPayload(supplierId, itemId)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath(SUPPLIER_ID).value(supplierId.toString()))
                                .andExpect(jsonPath("$.itemId").value(itemId.toString()));

                mockMvc.perform(withAuth(put("/v1/products/supplier-costs/{id}", supplierItemCostId))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(validUpdatePayloadWithDifferentValues(supplierId, itemId)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath(SUPPLIER_ID).value(supplierId.toString()))
                                .andExpect(jsonPath("$.itemId").value(itemId.toString()));
        }

        @Test
        @DisplayName("CC-004: Approve override with stale version returns 409")
        void testApproveLocationOverride_StaleVersionConflict() throws Exception {
                UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                UUID productId = createProductAndReturnId("CC-004 Product");
                UUID createdByUserId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                UUID approverUserId = UUID.fromString("00000000-0000-0000-0000-000000000001");

                upsertGuardrailPolicy(locationId, BigDecimal.valueOf(15), BigDecimal.valueOf(25),
                                BigDecimal.valueOf(10));
                Map<String, Object> createdOverride = createLocationOverrideAndReturnResponse(
                                locationId, productId, createdByUserId, 100.00, 50.00, 88.00, "ROLE_ADMIN");

                UUID overrideId = UUID.fromString((String) createdOverride.get("overrideId"));
                Long version = ((Number) createdOverride.get("version")).longValue();

                mockMvc.perform(withAuth(
                                post("/v1/products/pricing/location-overrides/{overrideId}/approve", overrideId),
                                "ROLE_ADMIN")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(approvalDecisionPayload(version + 1, approverUserId)))
                                .andExpect(status().isConflict());
        }

        private UUID createProductAndReturnId(String name) throws Exception {
                MvcResult result = mockMvc.perform(withAuth(post("/v1/catalog-items/{type}", "product"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(catalogProductPayload(name)))
                                .andExpect(status().isCreated())
                                .andReturn();

                @SuppressWarnings("unchecked")
                Map<String, Object> response = objectMapper.readValue(result.getResponse().getContentAsString(),
                                Map.class);
                return UUID.fromString((String) response.get("id"));
        }

        private void upsertGuardrailPolicy(UUID locationId, BigDecimal minMarginPercent, BigDecimal maxDiscountPercent,
                        BigDecimal autoApprovalThresholdPercent) throws Exception {
                mockMvc.perform(withAuth(post("/v1/products/pricing/guardrail-policies"), "ROLE_ADMIN")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(guardrailPolicyPayload(locationId, minMarginPercent, maxDiscountPercent,
                                                autoApprovalThresholdPercent)))
                                .andExpect(status().isOk());
        }

        private Map<String, Object> createLocationOverrideAndReturnResponse(UUID locationId, UUID productId,
                        UUID createdByUserId, double basePrice, double cost, double overridePrice, String authorities)
                        throws Exception {
                MvcResult result = mockMvc
                                .perform(withAuth(post("/v1/products/pricing/location-overrides"), authorities)
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(locationOverridePayload(locationId, productId, createdByUserId,
                                                                basePrice, cost,
                                                                overridePrice)))
                                .andExpect(status().isCreated())
                                .andReturn();

                @SuppressWarnings("unchecked")
                Map<String, Object> response = objectMapper.readValue(result.getResponse().getContentAsString(),
                                Map.class);
                return response;
        }

        private UUID createCatalogAndReturnId(String name, String description) throws Exception {
                MvcResult result = mockMvc.perform(withAuth(post("/v1/catalogs"))
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

        private String catalogProductPayload(String name) throws Exception {
                return objectMapper.writeValueAsString(Map.of(
                                "name", name,
                                "shortDescription", name + " short description",
                                "longDescription", name + " long description",
                                "sku", "SKU-" + UUID.randomUUID()));
        }

        private String guardrailPolicyPayload(UUID locationId, BigDecimal minMarginPercent,
                        BigDecimal maxDiscountPercent,
                        BigDecimal autoApprovalThresholdPercent) throws Exception {
                return objectMapper.writeValueAsString(Map.of(
                                "scopeId", locationId,
                                "minMarginPercent", minMarginPercent,
                                "maxDiscountPercent", maxDiscountPercent,
                                "autoApprovalThresholdPercent", autoApprovalThresholdPercent));
        }

        private String locationOverridePayload(UUID locationId, UUID productId, UUID createdByUserId,
                        double basePrice, double cost, double overridePrice) throws Exception {
                return objectMapper.writeValueAsString(Map.of(
                                "locationId", locationId,
                                "productId", productId,
                                "basePrice", basePrice,
                                "cost", cost,
                                "overridePrice", overridePrice,
                                "createdByUserId", createdByUserId));
        }

        private String approvalDecisionPayload(Long version, UUID actorUserId) throws Exception {
                return objectMapper.writeValueAsString(Map.of(
                                "version", version,
                                "actorUserId", actorUserId));
        }

        private String rejectionDecisionPayload(Long version, UUID actorUserId,
                        String rejectionReasonCode, String rejectionNotes) throws Exception {
                return objectMapper.writeValueAsString(Map.of(
                                "version", version,
                                "actorUserId", actorUserId,
                                "rejectionReasonCode", rejectionReasonCode,
                                "rejectionNotes", rejectionNotes));
        }

        private UUID createSupplierItemCost(UUID supplierId, UUID itemId) throws Exception {
                MvcResult result = mockMvc.perform(withAuth(post("/v1/products/supplier-costs"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(validSupplierItemCostPayload(supplierId, itemId)))
                                .andExpect(status().isCreated())
                                .andReturn();

                @SuppressWarnings("unchecked")
                Map<String, Object> response = objectMapper.readValue(result.getResponse().getContentAsString(),
                                Map.class);
                return UUID.fromString((String) response.get("id"));
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

        private String updatedSupplierItemCostPayload(UUID supplierId, UUID itemId) throws Exception {
                return objectMapper.writeValueAsString(Map.of(
                                "supplierId", supplierId,
                                "itemId", itemId,
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

        private String validUpdatePayloadWithDifferentValues(UUID supplierId, UUID itemId) throws Exception {
                return objectMapper.writeValueAsString(Map.of(
                                "supplierId", supplierId,
                                "itemId", itemId,
                                "currencyCode", "USD",
                                "baseCost", 6.80,
                                "tiers", java.util.List.of(
                                                Map.of("minQuantity", 1, "maxQuantity", 30, "unitCost", 5.10),
                                                Map.of("minQuantity", 31, "unitCost", 4.55))));
        }
}
