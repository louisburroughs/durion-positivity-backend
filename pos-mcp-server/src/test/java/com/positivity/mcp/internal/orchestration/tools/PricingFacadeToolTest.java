package com.positivity.mcp.internal.orchestration.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Unit tests for {@link PricingFacadeTool}: verifies RestClient call shapes.
 */
class PricingFacadeToolTest {

    private static final String BASE_URL = "http://api-gateway";

    private MockRestServiceServer mockServer;
    private PricingFacadeTool tool;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        tool = new PricingFacadeTool(builder, BASE_URL,
                "/price/v1/pricing/sku/{sku}",
                "/price/v1/pricing/search?q={query}",
                "/price/v1/pricing/lists/{priceListId}");
    }

    @Test
    @DisplayName("getPriceForSku sends GET /sku/{sku} and returns body")
    void getPriceForSku_sendsGetToSkuEndpoint() {
        mockServer
                .expect(requestTo(BASE_URL + "/price/v1/pricing/sku/SKU-001"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"sku\":\"SKU-001\",\"price\":9.99}", MediaType.APPLICATION_JSON));

        String result = tool.getPriceForSku("SKU-001");

        mockServer.verify();
        assertThat(result).isNotEmpty().contains("SKU-001");
    }

    @Test
    @DisplayName("searchPricing sends GET /search?q={query} and returns body")
    void searchPricing_sendsGetToSearchEndpoint() {
        mockServer
                .expect(requestTo(BASE_URL + "/price/v1/pricing/search?q=oil%20filter"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));

        String result = tool.searchPricing("oil filter");

        mockServer.verify();
        assertThat(result).isNotEmpty();
    }

    @Test
    @DisplayName("getPriceList sends GET /lists/{priceListId} and returns body")
    void getPriceList_sendsGetToPriceListEndpoint() {
        mockServer
                .expect(requestTo(BASE_URL + "/price/v1/pricing/lists/PL-001"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"id\":\"PL-001\",\"items\":[]}", MediaType.APPLICATION_JSON));

        String result = tool.getPriceList("PL-001");

        mockServer.verify();
        assertThat(result).isNotEmpty();
    }
}
