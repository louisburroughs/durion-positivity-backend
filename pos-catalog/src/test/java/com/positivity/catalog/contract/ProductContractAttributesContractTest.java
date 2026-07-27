package com.positivity.catalog.contract;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.catalog.BaseContractIntegrationTest;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Contract behavioral tests for the #1023 (Story X1) product contract attributes: per-product UoM
 * conversions, tracking level, and substitution groups.
 */
@DisplayName("Product Contract Attributes (UoM / tracking level / substitution groups) Behavioral Tests")
class ProductContractAttributesContractTest extends BaseContractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    // ─── Tracking level ──────────────────────────────────────────────────────

    @Test
    @DisplayName("X1-010: New product defaults to trackingLevel NONE")
    void newProduct_defaultsToTrackingLevelNone() throws Exception {
        String productId = createProduct();
        mockMvc.perform(withAuth(get("/v1/products/{id}", productId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trackingLevel").value("NONE"));
    }

    @Test
    @DisplayName("X1-011: PUT tracking-level sets LOT and returns updated product")
    void updateTrackingLevel_returnsUpdatedProduct() throws Exception {
        String productId = createProduct();
        mockMvc.perform(withAuth(put("/v1/products/{id}/tracking-level", productId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("trackingLevel", "LOT")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trackingLevel").value("LOT"));
    }

    @Test
    @DisplayName("X1-012: PUT tracking-level with unknown value returns 400")
    void updateTrackingLevel_invalidValue_returnsBadRequest() throws Exception {
        String productId = createProduct();
        mockMvc.perform(withAuth(put("/v1/products/{id}/tracking-level", productId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"trackingLevel\":\"PALLET\"}")))
                .andExpect(status().isBadRequest());
    }

    // ─── Product UoM conversions ─────────────────────────────────────────────

    @Test
    @DisplayName("X1-020: Add purchase UoM returns 201 with normalized code and factor")
    void addProductUom_returnsCreated() throws Exception {
        String productId = createProduct();
        mockMvc.perform(withAuth(post("/v1/products/{id}/uoms", productId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "uomCode", "case", "uomType", "PURCHASE", "factorToBase", 12, "precisionScale", 0)))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.productId").value(productId))
                .andExpect(jsonPath("$.uomCode").value("CASE"))
                .andExpect(jsonPath("$.uomType").value("PURCHASE"))
                .andExpect(jsonPath("$.precisionScale").value(0));
    }

    @Test
    @DisplayName("X1-021: Duplicate UoM code for same product returns 409")
    void addProductUom_duplicateCode_returnsConflict() throws Exception {
        String productId = createProduct();
        Map<String, Object> payload =
                Map.of("uomCode", "CASE", "uomType", "PURCHASE", "factorToBase", 12, "precisionScale", 0);
        mockMvc.perform(withAuth(post("/v1/products/{id}/uoms", productId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload))))
                .andExpect(status().isCreated());
        mockMvc.perform(withAuth(post("/v1/products/{id}/uoms", productId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload))))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("X1-022: Zero factor returns 400; BASE UoM with factor != 1 returns 400")
    void addProductUom_invalidFactor_returnsBadRequest() throws Exception {
        String productId = createProduct();
        mockMvc.perform(withAuth(post("/v1/products/{id}/uoms", productId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "uomCode", "CASE", "uomType", "PURCHASE", "factorToBase", 0, "precisionScale", 0)))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(withAuth(post("/v1/products/{id}/uoms", productId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("uomCode", "EA", "uomType", "BASE", "factorToBase", 2, "precisionScale", 0)))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("X1-023: Update and delete product UoM round-trip")
    void updateAndDeleteProductUom_roundTrip() throws Exception {
        String productId = createProduct();
        MvcResult created = mockMvc.perform(withAuth(post("/v1/products/{id}/uoms", productId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("uomCode", "BOX", "uomType", "PACK", "factorToBase", 6, "precisionScale", 0)))))
                .andExpect(status().isCreated())
                .andReturn();
        String uomId = readId(created);

        mockMvc.perform(withAuth(put("/v1/products/{id}/uoms/{uomId}", productId, uomId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("factorToBase", 8, "precisionScale", 2)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.precisionScale").value(2));

        mockMvc.perform(withAuth(delete("/v1/products/{id}/uoms/{uomId}", productId, uomId)))
                .andExpect(status().isNoContent());

        mockMvc.perform(withAuth(get("/v1/products/{id}/uoms", productId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    @DisplayName("X1-024: UoM operations on unknown product return 404")
    void productUom_unknownProduct_returnsNotFound() throws Exception {
        mockMvc.perform(withAuth(get("/v1/products/{id}/uoms", UUID.randomUUID())))
                .andExpect(status().isNotFound());
    }

    // ─── Substitution groups ─────────────────────────────────────────────────

    @Test
    @DisplayName("X1-030: Create group, add members, read membership")
    void substitutionGroup_membershipRoundTrip() throws Exception {
        String productA = createProduct();
        String productB = createProduct();
        String groupId = createGroup("Interchangeable filters");

        mockMvc.perform(withAuth(post("/v1/products/substitution-groups/{id}/members", groupId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("productId", productA)))))
                .andExpect(status().isOk());
        mockMvc.perform(withAuth(post("/v1/products/substitution-groups/{id}/members", groupId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("productId", productB)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productIds.length()").value(2));

        mockMvc.perform(withAuth(get("/v1/products/substitution-groups/{id}", groupId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Interchangeable filters"))
                .andExpect(jsonPath("$.productIds.length()").value(2));
    }

    @Test
    @DisplayName("X1-031: A product can belong to only one substitution group (409 on second add)")
    void substitutionGroup_singleMembershipEnforced() throws Exception {
        String product = createProduct();
        String groupA = createGroup("Group A");
        String groupB = createGroup("Group B");

        mockMvc.perform(withAuth(post("/v1/products/substitution-groups/{id}/members", groupA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("productId", product)))))
                .andExpect(status().isOk());
        mockMvc.perform(withAuth(post("/v1/products/substitution-groups/{id}/members", groupB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("productId", product)))))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("X1-032: Remove member and delete group")
    void substitutionGroup_removeAndDelete() throws Exception {
        String product = createProduct();
        String groupId = createGroup("Short-lived group");

        mockMvc.perform(withAuth(post("/v1/products/substitution-groups/{id}/members", groupId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("productId", product)))))
                .andExpect(status().isOk());
        mockMvc.perform(withAuth(delete("/v1/products/substitution-groups/{id}/members/{productId}", groupId, product)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productIds").isEmpty());
        mockMvc.perform(withAuth(delete("/v1/products/substitution-groups/{id}", groupId)))
                .andExpect(status().isNoContent());
        mockMvc.perform(withAuth(get("/v1/products/substitution-groups/{id}", groupId)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("X1-033: Blank group name returns 400")
    void substitutionGroup_blankName_returnsBadRequest() throws Exception {
        mockMvc.perform(withAuth(post("/v1/products/substitution-groups")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", " ")))))
                .andExpect(status().isBadRequest());
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private String createProduct() throws Exception {
        int seq = SEQ.incrementAndGet();
        MvcResult result = mockMvc.perform(withAuth(post("/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Contract Product " + seq,
                                "description", "Contract test product",
                                "unitOfMeasure", "EA",
                                "sku", "X1-SKU-" + seq,
                                "mpn", "X1-MPN-" + seq)))))
                .andExpect(status().isCreated())
                .andReturn();
        return readId(result);
    }

    private String createGroup(String name) throws Exception {
        MvcResult result = mockMvc.perform(withAuth(post("/v1/products/substitution-groups")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", name)))))
                .andExpect(status().isCreated())
                .andReturn();
        return readId(result);
    }

    private String readId(MvcResult result) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> response =
                objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
        return (String) response.get("id");
    }
}
