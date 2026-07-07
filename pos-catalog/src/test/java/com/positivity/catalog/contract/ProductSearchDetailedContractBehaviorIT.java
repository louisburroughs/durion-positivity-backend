package com.positivity.catalog.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.catalog.BaseContractIntegrationTest;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;

/**
 * Contract behavioral tests for the enriched (detailed=true) catalog search
 * endpoint that returns lifecycle state, effective instant, and active MSRP
 * inline so the frontend can drop its per-row getProductById + getActiveMsrp
 * fan-out.
 *
 * Issue: #828
 */
@DisplayName("Product Search (detailed) Contract Behavioral Tests")
class ProductSearchDetailedContractBehaviorIT extends BaseContractIntegrationTest {

    private static final String SEARCH_PATH = "/v1/products/search";
    private static final Clock TEST_CLOCK = Clock.systemUTC();
    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000001");

    // -----------------------------------------------------------------------
    // #828-001: detailed=true enriches each row with lifecycle + active MSRP;
    // a product with no active MSRP returns null price fields (not an error).
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("#828-001: detailed search returns lifecycle + active MSRP inline; null price when no MSRP")
    void detailedSearch_returnsLifecycleAndActiveMsrpInline() throws Exception {
        String namePrefix = "DetailedSearch-" + UUID.fromString("00000000-0000-0000-0000-000000000001");

        UUID withMsrp = createProductAndGetId(
                namePrefix + " Priced",
                "SKU-828-PRICED-" + UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "MPN-828-PRICED");
        createProductAndGetId(
                namePrefix + " Unpriced",
                "SKU-828-UNPRICED-" + UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "MPN-828-UNPRICED");

        // Give exactly one product an open-ended MSRP active as of today.
        mockMvc.perform(withAuth(post("/v1/products/{productId}/msrp", withMsrp)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "amount",
                                "129.9900",
                                "currency",
                                "USD",
                                "effectiveStartDate",
                                LocalDate.now(TEST_CLOCK).minusDays(1).toString(),
                                "createdByUserId",
                                ACTOR.toString())))))
                .andExpect(status().isCreated());

        MvcResult result = mockMvc.perform(withAuth(MockMvcRequestBuilders.get(SEARCH_PATH)
                        .param("q", namePrefix)
                        .param("detailed", "true")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andReturn();

        JsonNode data =
                objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
        assertThat(data.size()).as("Both products should be returned").isEqualTo(2);

        JsonNode priced = findByProductId(data, withMsrp);
        assertThat(priced).as("Priced product present in results").isNotNull();
        assertThat(priced.get("lifecycleState").asString()).isEqualTo("ACTIVE");
        assertThat(priced.get("msrpAmount").asString()).isEqualTo("129.9900");
        assertThat(priced.get("msrpCurrency").asString()).isEqualTo("USD");
        assertThat(priced.get("msrpEffectiveStartDate").asString())
                .isEqualTo(LocalDate.now(TEST_CLOCK).minusDays(1).toString());
        assertThat(priced.get("msrpEffectiveEndDate").isNull())
                .as("open-ended MSRP has null end date")
                .isTrue();

        // The unpriced product must have null price fields rather than erroring.
        JsonNode unpriced = null;
        for (JsonNode row : data) {
            if (!row.get("productId").asString().equals(withMsrp.toString())) {
                unpriced = row;
            }
        }
        assertThat(unpriced).as("Unpriced product present in results").isNotNull();
        assertThat(unpriced.get("lifecycleState").asString()).isEqualTo("ACTIVE");
        assertThat(unpriced.get("msrpAmount").isNull())
                .as("Product without active MSRP returns null amount")
                .isTrue();
        assertThat(unpriced.get("msrpCurrency").isNull()).isTrue();
    }

    // -----------------------------------------------------------------------
    // #828-002: default (lean) search is unchanged — enriched fields absent/null.
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("#828-002: lean search (default) does not populate enriched fields")
    void leanSearch_doesNotPopulateEnrichedFields() throws Exception {
        String namePrefix = "LeanSearch-" + UUID.fromString("00000000-0000-0000-0000-000000000001");

        UUID productId = createProductAndGetId(
                namePrefix + " Model",
                "SKU-828-LEAN-" + UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "MPN-828-LEAN");

        mockMvc.perform(withAuth(post("/v1/products/{productId}/msrp", productId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "amount",
                                "59.9900",
                                "currency",
                                "USD",
                                "effectiveStartDate",
                                LocalDate.now(TEST_CLOCK).minusDays(1).toString(),
                                "createdByUserId",
                                ACTOR.toString())))))
                .andExpect(status().isCreated());

        MvcResult result = mockMvc.perform(
                        withAuth(MockMvcRequestBuilders.get(SEARCH_PATH).param("q", namePrefix)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode data =
                objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
        JsonNode row = findByProductId(data, productId);
        assertThat(row).as("Product present in lean results").isNotNull();
        // Enriched fields are serialized as null in lean mode (backward compatible).
        assertThat(row.get("msrpAmount") == null || row.get("msrpAmount").isNull())
                .as("Lean search must not populate MSRP amount")
                .isTrue();
        assertThat(row.get("lifecycleState") == null
                        || row.get("lifecycleState").isNull())
                .as("Lean search must not populate lifecycle state")
                .isTrue();
    }

    private static JsonNode findByProductId(JsonNode data, UUID productId) {
        for (JsonNode row : data) {
            if (row.get("productId").asString().equals(productId.toString())) {
                return row;
            }
        }
        return null;
    }

    private UUID createProductAndGetId(String name, String sku, String mpn) throws Exception {
        Map<String, Object> payload = Map.of(
                "name",
                name,
                "description",
                "Product for detailed catalog search contract test",
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
                "{}");

        MvcResult result = mockMvc.perform(withAuth(post("/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload))))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        return UUID.fromString(response.get("id").asString());
    }
}
