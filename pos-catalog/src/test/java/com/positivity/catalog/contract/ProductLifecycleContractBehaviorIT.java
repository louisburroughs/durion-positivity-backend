package com.positivity.catalog.contract;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.catalog.BaseIntegrationTest;
import com.positivity.catalog.internal.dto.CatalogItemRequestDto;
import com.positivity.catalog.service.CatalogService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

@DisplayName("Product Lifecycle Contract Behavioral Tests")
class ProductLifecycleContractBehaviorIT extends BaseIntegrationTest {

    @Autowired
    private CatalogService catalogService;

    @Test
    @DisplayName("LC-001: Set product lifecycle to INACTIVE with effective date")
    void testSetInactiveLifecycle() throws Exception {
        UUID productId = createProductAndReturnId("Lifecycle Product A");

        mockMvc.perform(withAuth(put("/v1/products/{productId}/lifecycle", productId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "lifecycleState", "INACTIVE",
                        "effectiveDate", LocalDate.now().plusDays(1),
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
                        "lifecycleState", "DISCONTINUED",
                        "effectiveAt", Instant.now().plusSeconds(3600),
                        "overrideReason", "End of life",
                        "changedBy", UUID.randomUUID()))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("LC-003: Prevent reactivation from DISCONTINUED")
    void testPreventReactivationAfterDiscontinued() throws Exception {
        UUID productId = createProductAndReturnId("Lifecycle Product C");

        mockMvc.perform(withAuth(put("/v1/products/{productId}/lifecycle", productId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "lifecycleState", "DISCONTINUED",
                        "effectiveAt", Instant.now().plusSeconds(3600),
                        "overrideReason", "End of life",
                        "changedBy", UUID.randomUUID()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycleState").value("DISCONTINUED"));

        mockMvc.perform(withAuth(put("/v1/products/{productId}/lifecycle", productId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "lifecycleState", "ACTIVE",
                        "effectiveAt", Instant.now().plusSeconds(7200),
                        "changedBy", UUID.randomUUID()))))
                .andExpect(status().isBadRequest())
                .andExpect(result -> org.junit.jupiter.api.Assertions.assertTrue(
                        result.getResponse().getContentAsString()
                                .contains(
                                        "Discontinued products cannot be reactivated. Specify a replacement product instead.")));
    }

    @Test
    @DisplayName("LC-004: Add replacement to discontinued product")
    void testAddReplacementToDiscontinuedProduct() throws Exception {
        UUID originalProductId = createProductAndReturnId("Lifecycle Product D");
        UUID replacementProductId = createProductAndReturnId("Lifecycle Product E");

        mockMvc.perform(withAuth(put("/v1/products/{productId}/lifecycle", originalProductId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "lifecycleState", "DISCONTINUED",
                        "effectiveAt", Instant.now().plusSeconds(3600),
                        "overrideReason", "End of life",
                        "changedBy", UUID.randomUUID()))))
                .andExpect(status().isOk());

        mockMvc.perform(withAuth(post("/v1/products/{productId}/replacements", originalProductId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "replacementProductId", replacementProductId,
                        "priorityOrder", 1,
                        "notes", "Primary replacement",
                        "effectiveAt", Instant.now().plusSeconds(3600)))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replacementProductId").value(replacementProductId.toString()))
                .andExpect(jsonPath("$.priorityOrder").value(1));

        mockMvc.perform(withAuth(get("/v1/products/{productId}/lifecycle", originalProductId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycleState").value("DISCONTINUED"))
                .andExpect(jsonPath("$.replacementOptions[0].replacementProductId")
                        .value(replacementProductId.toString()));
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
