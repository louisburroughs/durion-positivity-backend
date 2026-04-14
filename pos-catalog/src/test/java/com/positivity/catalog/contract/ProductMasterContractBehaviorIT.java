package com.positivity.catalog.contract;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.catalog.BaseContractIntegrationTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

@DisplayName("Product Master Data Contract Behavioral Tests")
class ProductMasterContractBehaviorIT extends BaseContractIntegrationTest {

    @Test
    @DisplayName("CP-165-001: Create product returns 201")
    void createProduct_returnsCreated() throws Exception {
        mockMvc.perform(withAuth(post("/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validCreatePayload("SKU-165-001", "MPN-165-001")))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.sku").value("SKU-165-001"));
    }

    @Test
    @DisplayName("VE-165-001: Duplicate SKU returns 409")
    void duplicateSku_returnsConflict() throws Exception {
        mockMvc.perform(withAuth(post("/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validCreatePayload("SKU-165-DUP", "MPN-165-A")))))
                .andExpect(status().isCreated());

        mockMvc.perform(withAuth(post("/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validCreatePayload("SKU-165-DUP", "MPN-165-B")))))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("VE-165-002: Attempt to change SKU returns 400")
    void updateSku_returnsBadRequest() throws Exception {
        UUID productId = createProductAndGetId("SKU-165-IMM", "MPN-165-IMM");

        Map<String, Object> updatePayload = Map.of(
                "name", "Updated Product",
                "description", "Updated description",
                "unitOfMeasure", "EA",
                "manufacturerId",
                        UUID.fromString("00000000-0000-0000-0000-000000000001").toString(),
                "categoryId",
                        UUID.fromString("00000000-0000-0000-0000-000000000001").toString(),
                "sku", "SKU-CHANGED",
                "mpn", "MPN-165-IMM",
                "upc", "1000000000001",
                "attributes", "{\"size\":\"XL\"}");

        mockMvc.perform(withAuth(put("/v1/products/{productId}", productId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updatePayload))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("CP-165-002: Get product returns 200")
    void getProduct_returnsOk() throws Exception {
        UUID productId = createProductAndGetId("SKU-165-GET", "MPN-165-GET");

        mockMvc.perform(withAuth(get("/v1/products/{productId}", productId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(productId.toString()))
                .andExpect(jsonPath("$.sku").value("SKU-165-GET"));
    }

    private UUID createProductAndGetId(String sku, String mpn) throws Exception {
        MvcResult result = mockMvc.perform(withAuth(post("/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validCreatePayload(sku, mpn)))))
                .andExpect(status().isCreated())
                .andReturn();

        @SuppressWarnings("unchecked")
        Map<String, Object> response =
                objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
        return UUID.fromString((String) response.get("id"));
    }

    private Map<String, Object> validCreatePayload(String sku, String mpn) {
        return Map.of(
                "name",
                "Master Product " + sku,
                "description",
                "Product description",
                "unitOfMeasure",
                "EA",
                "manufacturerId",
                UUID.fromString("00000000-0000-0000-0000-000000000001").toString(),
                "sku",
                sku,
                "mpn",
                mpn,
                "upc",
                "1000000000000",
                "attributes",
                "{\"color\":\"black\"}");
    }
}
