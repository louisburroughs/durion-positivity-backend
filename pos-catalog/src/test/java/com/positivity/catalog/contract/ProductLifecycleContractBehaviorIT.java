package com.positivity.catalog.contract;

import java.time.ZoneOffset;
import java.time.Clock;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.catalog.BaseContractIntegrationTest;
import com.positivity.catalog.internal.dto.CatalogItemRequestDto;
import com.positivity.catalog.internal.service.CatalogServiceImpl;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * Contract behavioral tests for product lifecycle management.
 * These tests validate the business rules and constraints around product
 * lifecycle state transitions, effective dating, and replacement product
 * management.
 * Test cases include:
 * - Setting lifecycle to INACTIVE with an effective date
 * - Requiring override permission for DISCONTINUED state transitions
 * - Preventing reactivation of DISCONTINUED products
 * - Adding replacement products to DISCONTINUED products
 * - Rejecting updates that do not change the lifecycle state
 * 
 * Note: These tests focus on contract behavior and do not cover all edge cases
 * or validation scenarios. Additional tests may be needed for comprehensive
 * coverage.
 */
@DisplayName("Product Lifecycle Contract Behavioral Tests")
class ProductLifecycleContractBehaviorIT extends BaseContractIntegrationTest {
        private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

        private static final String LIFECYCLE_STATE = "lifecycleState";
        @Autowired
        private CatalogServiceImpl catalogService;

        @Test
        @DisplayName("CP-165-010: Set product lifecycle to INACTIVE with effective date")
        void testSetInactiveLifecycle() throws Exception {
                UUID productId = createProductAndReturnId("Lifecycle Product A");

                mockMvc.perform(withAuth(put("/v1/products/{productId}/lifecycle", productId))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of(
                                                LIFECYCLE_STATE, "INACTIVE",
                                                "effectiveDate", LocalDate.now(TEST_CLOCK).plusDays(1),
                                                "changedBy", UUID.randomUUID()))))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.productId").value(productId.toString()))
                                .andExpect(jsonPath("$.lifecycleState").value("INACTIVE"));
        }

        @Test
        @DisplayName("LC-002: Discontinue requires override permission")
        void testDiscontinueRequiresOverridePermission() throws Exception {
                UUID productId = createProductAndReturnId("Lifecycle Product B");

                mockMvc.perform(withAuth(
                                put("/v1/products/{productId}/lifecycle", productId),
                                "ROLE_ADMIN,ROLE_CATALOG_EDIT,product:lifecycle:update")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of(
                                                LIFECYCLE_STATE, "DISCONTINUED",
                                                "effectiveAt", Instant.now(TEST_CLOCK).plusSeconds(3600),
                                                "overrideReason", "End of life",
                                                "changedBy", UUID.randomUUID()))))
                                .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("LC-165-010: Prevent reactivation from DISCONTINUED")
        void testPreventReactivationAfterDiscontinued() throws Exception {
                UUID productId = createProductAndReturnId("Lifecycle Product C");

                mockMvc.perform(withAuth(put("/v1/products/{productId}/lifecycle", productId))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of(
                                                LIFECYCLE_STATE, "DISCONTINUED",
                                                "effectiveAt", Instant.now(TEST_CLOCK).plusSeconds(3600),
                                                "overrideReason", "End of life",
                                                "changedBy", UUID.randomUUID()))))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.lifecycleState").value("DISCONTINUED"));

                mockMvc.perform(withAuth(put("/v1/products/{productId}/lifecycle", productId))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of(
                                                LIFECYCLE_STATE, "ACTIVE",
                                                "effectiveAt", Instant.now(TEST_CLOCK).plusSeconds(7200),
                                                "changedBy", UUID.randomUUID()))))
                                .andExpect(status().isConflict())
                                .andExpect(result -> org.junit.jupiter.api.Assertions.assertTrue(
                                                result.getResponse().getContentAsString()
                                                                .contains(
                                                                                "Discontinued products cannot be reactivated. Specify a replacement product instead.")));
        }

        @Test
        @DisplayName("CP-165-011: Add replacement to discontinued product")
        void testAddReplacementToDiscontinuedProduct() throws Exception {
                UUID originalProductId = createProductAndReturnId("Lifecycle Product D");
                UUID replacementProductId = createProductAndReturnId("Lifecycle Product E");

                mockMvc.perform(withAuth(put("/v1/products/{productId}/lifecycle", originalProductId))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of(
                                                LIFECYCLE_STATE, "DISCONTINUED",
                                                "effectiveAt", Instant.now(TEST_CLOCK).plusSeconds(3600),
                                                "overrideReason", "End of life",
                                                "changedBy", UUID.randomUUID()))))
                                .andExpect(status().isOk());

                mockMvc.perform(withAuth(post("/v1/products/{productId}/replacements", originalProductId))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of(
                                                "replacementProductId", replacementProductId,
                                                "priorityOrder", 1,
                                                "notes", "Primary replacement",
                                                "effectiveAt", Instant.now(TEST_CLOCK).plusSeconds(3600)))))
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.replacementProductId").value(replacementProductId.toString()))
                                .andExpect(jsonPath("$.priorityOrder").value(1));

                mockMvc.perform(withAuth(get("/v1/products/{productId}/lifecycle", originalProductId)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.lifecycleState").value("DISCONTINUED"))
                                .andExpect(jsonPath("$.replacementOptions[0].replacementProductId")
                                                .value(replacementProductId.toString()));
        }

        @Test
        @DisplayName("LC-005: Reject update when lifecycle state is identical")
        void testRejectIdenticalLifecycleStateUpdate() throws Exception {
                UUID productId = createProductAndReturnId("Lifecycle Product F");

                mockMvc.perform(withAuth(put("/v1/products/{productId}/lifecycle", productId))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of(
                                                LIFECYCLE_STATE, "ACTIVE",
                                                "effectiveAt", Instant.now(TEST_CLOCK).plusSeconds(3600),
                                                "changedBy", UUID.randomUUID()))))
                                .andExpect(status().isBadRequest())
                                .andExpect(result -> org.junit.jupiter.api.Assertions.assertTrue(
                                                result.getResponse().getContentAsString()
                                                                .contains("lifecycleState is already set to ACTIVE")));
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
