package com.positivity.catalog.contract;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.catalog.BaseContractIntegrationTest;
import com.positivity.catalog.internal.dto.CatalogItemRequestDto;
import com.positivity.catalog.internal.service.CatalogServiceImpl;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

@DisplayName("Location Pricing Contract Behavioral Tests")
class LocationPricingContractBehaviorIT extends BaseContractIntegrationTest {

        @Autowired
        private CatalogServiceImpl catalogService;

        @Test
        @DisplayName("CP-101: Create override within auto-approval threshold -> ACTIVE")
        void cp101_createOverrideWithinAutoApprovalThreshold() throws Exception {
                UUID locationId = UUID.fromString("0196cf6f-c8dd-7ee0-93e7-f48a5698a535");
                UUID productId = createProductAndReturnId("CAP168-CP101-Product");
                UUID actorUserId = UUID.fromString("0196cf6f-c8dd-7ee0-93e7-f48a5698a537");

                upsertGuardrailPolicy(locationId, 15.0, 25.0, 10.0);

                mockMvc.perform(withAuth(post("/v1/products/pricing/location-overrides"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of(
                                                "locationId", locationId,
                                                "productId", productId,
                                                "basePrice", 100.00,
                                                "cost", 50.00,
                                                "overridePrice", 95.00,
                                                "createdByUserId", actorUserId))))
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.locationId").value(locationId.toString()))
                                .andExpect(jsonPath("$.productId").value(productId.toString()))
                                .andExpect(jsonPath("$.status").value("ACTIVE"));
        }

        @Test
        @DisplayName("CP-102: Create override exceeding auto-approval threshold -> PENDING_APPROVAL")
        void cp102_createOverrideExceedingAutoApprovalThreshold() throws Exception {
                UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                UUID productId = createProductAndReturnId("CAP168-CP102-Product");
                UUID actorUserId = UUID.fromString("00000000-0000-0000-0000-000000000001");

                upsertGuardrailPolicy(locationId, 15.0, 25.0, 10.0);

                mockMvc.perform(withAuth(post("/v1/products/pricing/location-overrides"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of(
                                                "locationId", locationId,
                                                "productId", productId,
                                                "basePrice", 100.00,
                                                "cost", 50.00,
                                                "overridePrice", 88.00,
                                                "createdByUserId", actorUserId))))
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                                .andExpect(jsonPath("$.assignmentStrategy").value("LOCATION_SCOPE_PRIMARY_THEN_POOL"))
                                .andExpect(jsonPath("$.assignedApproverId").isNotEmpty());
        }

        @Test
        @DisplayName("VE-101: Create override violating min margin hard limit -> 400")
        void ve101_minMarginViolation() throws Exception {
                UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                UUID productId = createProductAndReturnId("CAP168-VE101-Product");

                upsertGuardrailPolicy(locationId, 15.0, 25.0, 10.0);

                mockMvc.perform(withAuth(post("/v1/products/pricing/location-overrides"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of(
                                                "locationId", locationId,
                                                "productId", productId,
                                                "basePrice", 100.00,
                                                "cost", 90.00,
                                                "overridePrice", 92.00,
                                                "createdByUserId",
                                                UUID.fromString("00000000-0000-0000-0000-000000000001")))))
                                .andExpect(status().isBadRequest())
                                .andExpect(content().string(containsString("MIN_MARGIN_VIOLATION")));
        }

        @Test
        @DisplayName("VE-102: Create override violating max discount hard limit -> 400")
        void ve102_maxDiscountViolation() throws Exception {
                UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                UUID productId = createProductAndReturnId("CAP168-VE102-Product");

                upsertGuardrailPolicy(locationId, 15.0, 25.0, 10.0);

                mockMvc.perform(withAuth(post("/v1/products/pricing/location-overrides"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of(
                                                "locationId", locationId,
                                                "productId", productId,
                                                "basePrice", 100.00,
                                                "cost", 50.00,
                                                "overridePrice", 60.00,
                                                "createdByUserId",
                                                UUID.fromString("00000000-0000-0000-0000-000000000001")))))
                                .andExpect(status().isBadRequest())
                                .andExpect(content().string(containsString("MAX_DISCOUNT_EXCEEDED")));
        }

        @Test
        @DisplayName("LC-101: Pending override approve lifecycle -> ACTIVE and effective override price")
        void lc101_approveLifecycle() throws Exception {
                UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                UUID productId = createProductAndReturnId("CAP168-LC101-Product");
                UUID createdBy = UUID.fromString("00000000-0000-0000-0000-000000000001");
                UUID approver = UUID.fromString("00000000-0000-0000-0000-000000000001");

                upsertGuardrailPolicy(locationId, 15.0, 25.0, 10.0);

                MvcResult createResult = mockMvc.perform(withAuth(post("/v1/products/pricing/location-overrides"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of(
                                                "locationId", locationId,
                                                "productId", productId,
                                                "basePrice", 100.00,
                                                "cost", 50.00,
                                                "overridePrice", 88.00,
                                                "createdByUserId", createdBy))))
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                                .andReturn();

                @SuppressWarnings("unchecked")
                Map<String, Object> created = objectMapper.readValue(createResult.getResponse().getContentAsString(),
                                Map.class);
                String overrideId = (String) created.get("overrideId");
                Long version = ((Number) created.get("version")).longValue();

                mockMvc.perform(withAuth(
                                post("/v1/products/pricing/location-overrides/{overrideId}/approve", overrideId))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of(
                                                "version", version,
                                                "actorUserId", approver))))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.status").value("ACTIVE"))
                                .andExpect(jsonPath("$.approvedByUserId").value(approver.toString()));

                mockMvc.perform(
                                withAuth(get("/v1/products/pricing/effective-price/{locationId}/{productId}",
                                                locationId, productId)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.overrideStatus").value("ACTIVE"))
                                .andExpect(jsonPath("$.effectivePrice").value(88.0));
        }

        @Test
        @DisplayName("LC-102: Pending override reject lifecycle -> REJECTED")
        void lc102_rejectLifecycle() throws Exception {
                UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                UUID productId = createProductAndReturnId("CAP168-LC102-Product");
                UUID createdBy = UUID.fromString("00000000-0000-0000-0000-000000000001");
                UUID approver = UUID.fromString("00000000-0000-0000-0000-000000000001");

                upsertGuardrailPolicy(locationId, 15.0, 25.0, 10.0);

                MvcResult createResult = mockMvc.perform(withAuth(post("/v1/products/pricing/location-overrides"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of(
                                                "locationId", locationId,
                                                "productId", productId,
                                                "basePrice", 100.00,
                                                "cost", 50.00,
                                                "overridePrice", 88.00,
                                                "createdByUserId", createdBy))))
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                                .andReturn();

                @SuppressWarnings("unchecked")
                Map<String, Object> created = objectMapper.readValue(createResult.getResponse().getContentAsString(),
                                Map.class);
                String overrideId = (String) created.get("overrideId");
                Long version = ((Number) created.get("version")).longValue();

                mockMvc.perform(withAuth(
                                post("/v1/products/pricing/location-overrides/{overrideId}/reject", overrideId))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of(
                                                "version", version,
                                                "actorUserId", approver,
                                                "rejectionReasonCode", "PRICE_POLICY_EXCEPTION",
                                                "rejectionNotes", "Price reduction requires regional review"))))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.status").value("REJECTED"))
                                .andExpect(jsonPath("$.rejectedBy").value(approver.toString()))
                                .andExpect(jsonPath("$.rejectionReasonCode").value("PRICE_POLICY_EXCEPTION"));
        }

        @Test
        @DisplayName("ID-101: Effective price with ACTIVE override returns override price")
        void id101_effectivePriceUsesActiveOverride() throws Exception {
                UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                UUID productId = createProductAndReturnId("CAP168-ID101-Product");

                upsertGuardrailPolicy(locationId, 15.0, 25.0, 10.0);

                mockMvc.perform(withAuth(post("/v1/products/pricing/location-overrides"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of(
                                                "locationId", locationId,
                                                "productId", productId,
                                                "basePrice", 100.00,
                                                "cost", 50.00,
                                                "overridePrice", 95.00,
                                                "createdByUserId",
                                                UUID.fromString("00000000-0000-0000-0000-000000000001")))))
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.status").value("ACTIVE"));

                mockMvc.perform(
                                withAuth(get("/v1/products/pricing/effective-price/{locationId}/{productId}",
                                                locationId, productId)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.basePrice").value(100.0))
                                .andExpect(jsonPath("$.effectivePrice").value(95.0))
                                .andExpect(jsonPath("$.overrideStatus").value("ACTIVE"));
        }

        @Test
        @DisplayName("ID-102: Effective price with no override returns 404")
        void id102_effectivePriceWithoutOverrideReturns404() throws Exception {
                UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                UUID productId = createProductAndReturnId("CAP168-ID102-Product");

                mockMvc.perform(
                                withAuth(get("/v1/products/pricing/effective-price/{locationId}/{productId}",
                                                locationId, productId)))
                                .andExpect(status().isNotFound())
                                .andExpect(content().string(containsString("EFFECTIVE_PRICE_NOT_FOUND")));
        }

        private void upsertGuardrailPolicy(UUID locationId, double minMarginPercent,
                        double maxDiscountPercent, double autoApprovalThresholdPercent) throws Exception {
                mockMvc.perform(withAuth(post("/v1/products/pricing/guardrail-policies"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of(
                                                "scopeId", locationId,
                                                "minMarginPercent", minMarginPercent,
                                                "maxDiscountPercent", maxDiscountPercent,
                                                "autoApprovalThresholdPercent", autoApprovalThresholdPercent))))
                                .andExpect(status().isOk());
        }

        private UUID createProductAndReturnId(String name) {
                CatalogItemRequestDto request = new CatalogItemRequestDto();
                request.setName(name);
                request.setShortDescription("Short " + name);
                request.setLongDescription("Long " + name);
                request.setType("PHYSICAL");
                return catalogService.addCatalogItem("product", request).getId();
        }
}
