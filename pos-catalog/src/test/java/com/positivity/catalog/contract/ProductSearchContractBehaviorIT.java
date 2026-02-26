package com.positivity.catalog.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.catalog.BaseContractIntegrationTest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import tools.jackson.databind.JsonNode;

import java.util.Map;
import java.util.UUID;

/**
 * Contract behavioral tests for the Catalog Product Search endpoint.
 *
 * <p>
 * Tests cover CAP-247 Story #17: cursor-based product search with optional
 * free-text query, brand, category, and SKU filters. Tests are GREEN against
 * the implemented {@code ProductSearchServiceImpl}.
 *
 * <p>
 * Affected contract assertions: CS-001 through CS-008.
 *
 * Issue: CAP-247
 */
@DisplayName("Product Search Contract Behavioral Tests")
class ProductSearchContractBehaviorIT extends BaseContractIntegrationTest {

        private static final String SEARCH_PATH = "/v1/products/search";

        // -----------------------------------------------------------------------
        // CS-001: No matching products → 200 OK, data=[], nextCursor=null
        // (Passes in scaffold — boundary case that works as-is)
        // -----------------------------------------------------------------------

        @Test
        @DisplayName("CS-001: No matching products – returns 200 OK with empty data and null nextCursor")
        void testSearch_noMatchingProducts_returnsEmptyDataWithNullNextCursor() throws Exception {
                // Issue CAP-247: search for a term guaranteed to match nothing
                String uniqueTerm = "xqznomatch-" + UUID.randomUUID();

                mockMvc.perform(withAuth(MockMvcRequestBuilders
                                .get(SEARCH_PATH)
                                .param("q", uniqueTerm)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.data").isArray())
                                .andExpect(jsonPath("$.data").isEmpty())
                                .andExpect(jsonPath("$.nextCursor").value(nullValue()));
        }

        // -----------------------------------------------------------------------
        // CS-002: Results exist and fit within limit → populated data, null cursor
        // RED: scaffold returns empty data even when matching products exist
        // -----------------------------------------------------------------------

        @Test
        @DisplayName("CS-002: Results fit within limit – returns matching items with null nextCursor")
        void testSearch_resultsWithinLimit_returnsPopulatedDataWithNullNextCursor() throws Exception {
                // Issue CAP-247: 2 products share the unique name prefix; limit=10 fits both
                String namePrefix = "SearchWrench-" + UUID.randomUUID();

                createProductAndGetId(namePrefix + " Model-A", "SKU-CS002-A-" + UUID.randomUUID(), "MPN-CS002-A");
                createProductAndGetId(namePrefix + " Model-B", "SKU-CS002-B-" + UUID.randomUUID(), "MPN-CS002-B");

                MvcResult result = mockMvc.perform(withAuth(MockMvcRequestBuilders
                                .get(SEARCH_PATH)
                                .param("q", namePrefix)
                                .param("limit", "10")))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.data").isArray())
                                // RED: scaffold returns empty; implementation should return 2 matching products
                                .andReturn();

                JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
                assertThat(body.get("data").size())
                                .as("Expected 2 products matching name prefix, scaffold returns 0")
                                .isEqualTo(2);
                assertThat(body.get("nextCursor").isNull())
                                .as("nextCursor should be null when results fit in one page")
                                .isTrue();
        }

        // -----------------------------------------------------------------------
        // CS-003: Results exceed limit → data.length == limit, nextCursor non-null
        // RED: scaffold returns empty data and null cursor
        // -----------------------------------------------------------------------

        @Test
        @DisplayName("CS-003: Results exceed limit – returns exactly limit items with non-null nextCursor")
        void testSearch_resultsExceedLimit_returnsPagedResultsWithNextCursor() throws Exception {
                // Issue CAP-247: create 3 products with limit=2 → expect 2 results + cursor
                String namePrefix = "SearchSocket-" + UUID.randomUUID();

                createProductAndGetId(namePrefix + " 1/4in", "SKU-CS003-A-" + UUID.randomUUID(), "MPN-CS003-A");
                createProductAndGetId(namePrefix + " 3/8in", "SKU-CS003-B-" + UUID.randomUUID(), "MPN-CS003-B");
                createProductAndGetId(namePrefix + " 1/2in", "SKU-CS003-C-" + UUID.randomUUID(), "MPN-CS003-C");

                MvcResult result = mockMvc.perform(withAuth(MockMvcRequestBuilders
                                .get(SEARCH_PATH)
                                .param("q", namePrefix)
                                .param("limit", "2")))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.data").isArray())
                                // RED: scaffold returns empty data and null cursor
                                .andReturn();

                JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
                assertThat(body.get("data").size())
                                .as("Expected exactly 2 results (limit), scaffold returns 0")
                                .isEqualTo(2);
                assertThat(body.get("nextCursor").isNull())
                                .as("nextCursor must be non-null when more results exist beyond the page")
                                .isFalse();
        }

        // -----------------------------------------------------------------------
        // CS-004: Cursor used for next page → correct second page returned
        // RED: scaffold returns empty for any cursor value
        // -----------------------------------------------------------------------

        @Test
        @DisplayName("CS-004: Cursor used for second page – returns correct next page items")
        void testSearch_cursorUsedForNextPage_returnsNextPageItems() throws Exception {
                // Issue CAP-247: create 3 products; first page (limit=2) returns cursor;
                // second page (cursor) should return the remaining 1 item
                String namePrefix = "SearchCaliper-" + UUID.randomUUID();

                createProductAndGetId(namePrefix + " A", "SKU-CS004-A-" + UUID.randomUUID(), "MPN-CS004-A");
                createProductAndGetId(namePrefix + " B", "SKU-CS004-B-" + UUID.randomUUID(), "MPN-CS004-B");
                createProductAndGetId(namePrefix + " C", "SKU-CS004-C-" + UUID.randomUUID(), "MPN-CS004-C");

                // Get first page to obtain cursor
                MvcResult firstPage = mockMvc.perform(withAuth(MockMvcRequestBuilders
                                .get(SEARCH_PATH)
                                .param("q", namePrefix)
                                .param("limit", "2")))
                                .andExpect(status().isOk())
                                .andReturn();

                JsonNode firstBody = objectMapper.readTree(firstPage.getResponse().getContentAsString());
                // RED: scaffold returns null cursor; cannot execute second page
                assertThat(firstBody.get("data").size())
                                .as("First page must have 2 items; scaffold returns 0 (RED)")
                                .isEqualTo(2);

                String cursor = firstBody.get("nextCursor").asString();
                assertThat(cursor).as("cursor must be non-null for second page navigation").isNotBlank();

                // Second page
                MvcResult secondPage = mockMvc.perform(withAuth(MockMvcRequestBuilders
                                .get(SEARCH_PATH)
                                .param("q", namePrefix)
                                .param("limit", "2")
                                .param("cursor", cursor)))
                                .andExpect(status().isOk())
                                .andReturn();

                JsonNode secondBody = objectMapper.readTree(secondPage.getResponse().getContentAsString());
                assertThat(secondBody.get("data").size())
                                .as("Second page should return the remaining 1 item")
                                .isEqualTo(1);
                assertThat(secondBody.get("nextCursor").isNull())
                                .as("nextCursor should be null after last page")
                                .isTrue();
        }

        // -----------------------------------------------------------------------
        // CS-005: SKU exact match present → SKU-matching product first in data
        // RED: scaffold returns empty data, cannot assert ordering
        // -----------------------------------------------------------------------

        @Test
        @DisplayName("CS-005: SKU exact match present – SKU-matching product ranked first")
        void testSearch_skuExactMatchPresent_skuMatchRankedFirst() throws Exception {
                // Issue CAP-247: create 2 products with shared name term; search by
                // skuForFirstProduct → expect that product to be data[0]
                String namePrefix = "SearchRatchet-" + UUID.randomUUID();
                String targetSku = "SKU-CS005-TARGET-" + UUID.randomUUID();

                createProductAndGetId(namePrefix + " other", "SKU-CS005-OTHER-" + UUID.randomUUID(), "MPN-CS005-OTHER");
                createProductAndGetId(namePrefix + " target", targetSku, "MPN-CS005-TARGET");

                MvcResult result = mockMvc.perform(withAuth(MockMvcRequestBuilders
                                .get(SEARCH_PATH)
                                .param("q", namePrefix)
                                .param("sku", targetSku)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.data").isArray())
                                // RED: scaffold returns empty; implementation must rank SKU match first
                                .andReturn();

                JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
                assertThat(body.get("data").size())
                                .as("Expected at least 1 result when SKU matches; scaffold returns 0")
                                .isGreaterThanOrEqualTo(1);
                assertThat(body.get("data").get(0).get("sku").asString())
                                .as("SKU-matching product must be ranked first")
                                .isEqualTo(targetSku);
        }

        // -----------------------------------------------------------------------
        // CS-006: brand filter applied → only matching brand products in data
        // RED: scaffold returns empty data regardless of brand filter
        // -----------------------------------------------------------------------

        @Test
        @DisplayName("CS-006: Brand filter applied – returns only products matching the specified brand")
        void testSearch_brandFilterApplied_returnsOnlyMatchingBrandProducts() throws Exception {
                // Issue CAP-247: create 2 products with unique name prefix; search with
                // brand="Acme" should only return the first product (after implementation sets
                // brand). Since brand cannot be set via create DTO in current API, we assert on
                // data non-emptiness with no brand filter as the RED driver.
                String namePrefix = "SearchTorque-" + UUID.randomUUID();

                createProductAndGetId(namePrefix + " Acme", "SKU-CS006-A-" + UUID.randomUUID(), "MPN-CS006-A");
                createProductAndGetId(namePrefix + " Bosch", "SKU-CS006-B-" + UUID.randomUUID(), "MPN-CS006-B");

                // Without brand filter → expect both products
                MvcResult allResult = mockMvc.perform(withAuth(MockMvcRequestBuilders
                                .get(SEARCH_PATH)
                                .param("q", namePrefix)))
                                .andExpect(status().isOk())
                                .andReturn();

                JsonNode allBody = objectMapper.readTree(allResult.getResponse().getContentAsString());
                // RED: scaffold returns empty; implementation should return both products
                assertThat(allBody.get("data").size())
                                .as("Expected 2 products without brand filter; scaffold returns 0 (RED)")
                                .isEqualTo(2);

                // With non-existent brand → expect empty
                mockMvc.perform(withAuth(MockMvcRequestBuilders
                                .get(SEARCH_PATH)
                                .param("q", namePrefix)
                                .param("brand", "ZZZ-NoSuchBrand")))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.data").isEmpty());
        }

        // -----------------------------------------------------------------------
        // CS-007: category filter applied → only matching category products in data
        // RED: scaffold returns empty data regardless of filter
        // -----------------------------------------------------------------------

        @Test
        @DisplayName("CS-007: Category filter applied – returns only products in matching category")
        void testSearch_categoryFilterApplied_returnsOnlyMatchingCategoryProducts() throws Exception {
                // Issue CAP-247: create 2 products with unique name; search without category
                // filter → expect both; search with category=ZZZ-NoSuchCategory → expect empty.
                // The second assertion tests that category filtering correctly excludes
                // products.
                String namePrefix = "SearchMallet-" + UUID.randomUUID();

                createProductAndGetId(namePrefix + " A", "SKU-CS007-A-" + UUID.randomUUID(), "MPN-CS007-A");
                createProductAndGetId(namePrefix + " B", "SKU-CS007-B-" + UUID.randomUUID(), "MPN-CS007-B");

                // Without category filter → expect both products
                MvcResult allResult = mockMvc.perform(withAuth(MockMvcRequestBuilders
                                .get(SEARCH_PATH)
                                .param("q", namePrefix)))
                                .andExpect(status().isOk())
                                .andReturn();

                JsonNode allBody = objectMapper.readTree(allResult.getResponse().getContentAsString());
                // RED: scaffold returns empty; implementation should return both products
                assertThat(allBody.get("data").size())
                                .as("Expected 2 products without category filter; scaffold returns 0 (RED)")
                                .isEqualTo(2);

                // With non-existent category → expect empty
                mockMvc.perform(withAuth(MockMvcRequestBuilders
                                .get(SEARCH_PATH)
                                .param("q", namePrefix)
                                .param("category", "ZZZ-NoSuchCategory")))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.data").isEmpty());
        }

        // -----------------------------------------------------------------------
        // CS-008: limit > 100 → 200 OK, results clamped to max 100
        // RED: scaffold returns empty data (data.length == 0, not 100 as expected)
        // -----------------------------------------------------------------------

        @Test
        @DisplayName("CS-008: limit > 100 – returns 200 OK with results clamped to 100 max")
        void testSearch_limitExceedsMax_returnsClamped100Results() throws Exception {
                // Issue CAP-247: create products with a shared name; request limit=150 →
                // endpoint must return 200 (not 400) with at most 100 results.
                // We create just 2 products and verify the CLAMPING contract (200 OK, data
                // array present and size <= 100). The empty-data failure drives implementation.
                String namePrefix = "SearchClamp-" + UUID.randomUUID();

                createProductAndGetId(namePrefix + " A", "SKU-CS008-A-" + UUID.randomUUID(), "MPN-CS008-A");
                createProductAndGetId(namePrefix + " B", "SKU-CS008-B-" + UUID.randomUUID(), "MPN-CS008-B");

                MvcResult result = mockMvc.perform(withAuth(MockMvcRequestBuilders
                                .get(SEARCH_PATH)
                                .param("q", namePrefix)
                                .param("limit", "150")))
                                .andExpect(status().isOk())
                                // RED: returns 200 OK (controller clamping correct); but scaffold returns empty
                                .andExpect(jsonPath("$.data").isArray())
                                .andReturn();

                JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
                assertThat(body.get("data").size())
                                .as("Expected 2 results (< 100 cap); scaffold returns 0 (RED)")
                                .isEqualTo(2);
                assertThat(body.get("limit").asInt())
                                .as("Effective limit must be clamped to 100 for input 150")
                                .isEqualTo(100);
        }

        // -----------------------------------------------------------------------
        // CS-009: Free-text q matches product description (not just name)
        // Validates fix for contract gap: service interface documents q as
        // "name, description" but original JPQL only matched p.name.
        // -----------------------------------------------------------------------

        @Test
        @DisplayName("CS-009: Free-text query matches product description – returns matching product")
        void testSearch_queryMatchesDescription_returnsMatchingProduct() throws Exception {
                // Issue CAP-247: create a product whose name is generic but description is
                // unique; q should find it via description.
                String uniqueDescToken = "descmatch-" + UUID.randomUUID();
                String genericName = "GenericProduct-" + UUID.randomUUID();
                String sku = "SKU-CS009-" + UUID.randomUUID();

                Map<String, Object> payload = Map.of(
                                "name", genericName,
                                "description", "This product has token " + uniqueDescToken + " in its description",
                                "unitOfMeasure", "EA",
                                "manufacturerId", UUID.randomUUID().toString(),
                                "sku", sku,
                                "mpn", "MPN-CS009-" + UUID.randomUUID(),
                                "upc", "1000000000000",
                                "attributes", "{}");

                mockMvc.perform(withAuth(post("/v1/products")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(payload))))
                                .andExpect(status().isCreated());

                MvcResult result = mockMvc.perform(withAuth(MockMvcRequestBuilders
                                .get(SEARCH_PATH)
                                .param("q", uniqueDescToken)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.data").isArray())
                                .andReturn();

                JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
                assertThat(body.get("data").size())
                                .as("q should match products by description token '%s'", uniqueDescToken)
                                .isEqualTo(1);
        }

        // -----------------------------------------------------------------------
        // Helper
        // -----------------------------------------------------------------------

        /**
         * Creates a product via the REST API and returns its generated UUID.
         *
         * @param name unique display name for the product
         * @param sku  unique SKU
         * @param mpn  manufacturer part number
         * @return the UUID assigned to the newly created product
         */
        private UUID createProductAndGetId(String name, String sku, String mpn) throws Exception {
                Map<String, Object> payload = Map.of(
                                "name", name,
                                "description", "Product for catalog search contract test",
                                "unitOfMeasure", "EA",
                                "manufacturerId", UUID.randomUUID().toString(),
                                "sku", sku,
                                "mpn", mpn,
                                "upc", "1000000000000",
                                "attributes", "{}");

                MvcResult result = mockMvc.perform(withAuth(post("/v1/products")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(payload))))
                                .andExpect(status().isCreated())
                                .andReturn();

                JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
                return UUID.fromString(response.get("id").asString());
        }
}
