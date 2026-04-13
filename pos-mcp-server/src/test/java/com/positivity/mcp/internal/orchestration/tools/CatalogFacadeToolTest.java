package com.positivity.mcp.internal.orchestration.tools;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Unit tests for {@link CatalogFacadeTool}: verifies RestClient call shapes.
 */
class CatalogFacadeToolTest {

  private static final String BASE_URL = "http://pos-catalog/v1/catalog";

  private MockRestServiceServer mockServer;
  private CatalogFacadeTool tool;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder();
    mockServer = MockRestServiceServer.bindTo(builder).build();
    tool = new CatalogFacadeTool(builder, BASE_URL);
  }

  @Test
  @DisplayName("getProduct sends GET /products/{productId} and returns body")
  void getProduct_sendsGetToProductEndpoint() {
    mockServer.expect(requestTo(BASE_URL + "/products/PROD-001"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("{\"id\":\"PROD-001\"}", MediaType.APPLICATION_JSON));

    String result = tool.getProduct("PROD-001");

    mockServer.verify();
    assertThat(result).isNotEmpty().contains("PROD-001");
  }

  @Test
  @DisplayName("searchCatalog sends GET /search?q={query} and returns body")
  void searchCatalog_sendsGetToSearchEndpoint() {
    mockServer.expect(requestTo(BASE_URL + "/search?q=brake%20pads"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));

    String result = tool.searchCatalog("brake pads");

    mockServer.verify();
    assertThat(result).isNotEmpty();
  }

  @Test
  @DisplayName("getCatalogByCategory sends GET /categories/{category} and returns body")
  void getCatalogByCategory_sendsGetToCategoryEndpoint() {
    mockServer.expect(requestTo(BASE_URL + "/categories/BRAKES"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("{\"category\":\"BRAKES\",\"items\":[]}", MediaType.APPLICATION_JSON));

    String result = tool.getCatalogByCategory("BRAKES");

    mockServer.verify();
    assertThat(result).isNotEmpty();
  }
}
