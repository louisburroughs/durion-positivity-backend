package com.positivity.mcp.internal.orchestration.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Unit tests for {@link CatalogFacadeTool}. Expected verbs and URIs derive from
 * {@code facade-contract.yaml} (#1519 WS-0.3), never from literals duplicating the configuration.
 */
class CatalogFacadeToolTest {

    private static final String BASE_URL = "http://api-gateway";
    private static final String PRODUCT_ID = "01960003-0000-7000-8000-000000000010";

    private MockRestServiceServer mockServer;
    private CatalogFacadeTool tool;

    private static FacadeContractManifest.Entry contract(String toolMethod) {
        return FacadeContractManifest.entry("CatalogFacadeTool." + toolMethod);
    }

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        tool = new CatalogFacadeTool(
                builder,
                BASE_URL,
                contract("getProduct").template(),
                contract("searchCatalog").template(),
                contract("getCatalogByCategory").template());
    }

    @Test
    @DisplayName("getProduct sends GET /products/{productId} and returns body")
    void getProduct_sendsGetToProductEndpoint() {
        FacadeContractManifest.Entry entry = contract("getProduct");
        mockServer
                .expect(requestTo(BASE_URL + entry.expand(Map.of("productId", PRODUCT_ID))))
                .andExpect(method(entry.httpMethod()))
                .andRespond(withSuccess("{\"id\":\"" + PRODUCT_ID + "\"}", MediaType.APPLICATION_JSON));

        String result = tool.getProduct(PRODUCT_ID);

        mockServer.verify();
        assertThat(result).isNotEmpty().contains(PRODUCT_ID);
    }

    @Test
    @DisplayName("searchCatalog sends GET /products/search?q={query} and returns body")
    void searchCatalog_sendsGetToSearchEndpoint() {
        FacadeContractManifest.Entry entry = contract("searchCatalog");
        mockServer
                .expect(requestTo(BASE_URL + entry.expand(Map.of("query", "brakes"))))
                .andExpect(method(entry.httpMethod()))
                .andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));

        String result = tool.searchCatalog("brakes");

        mockServer.verify();
        assertThat(result).isNotEmpty();
    }

    @Test
    @DisplayName("getCatalogByCategory sends GET /products/search?category={category} and returns body")
    void getCatalogByCategory_sendsGetToCategorySearch() {
        FacadeContractManifest.Entry entry = contract("getCatalogByCategory");
        mockServer
                .expect(requestTo(BASE_URL + entry.expand(Map.of("category", "TIRES"))))
                .andExpect(method(entry.httpMethod()))
                .andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));

        String result = tool.getCatalogByCategory("TIRES");

        mockServer.verify();
        assertThat(result).isNotEmpty();
    }
}
