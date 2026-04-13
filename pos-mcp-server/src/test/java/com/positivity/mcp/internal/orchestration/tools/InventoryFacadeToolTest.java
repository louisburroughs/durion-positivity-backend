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
 * Unit tests for {@link InventoryFacadeTool}: verifies RestClient call shapes.
 */
class InventoryFacadeToolTest {

  private static final String BASE_URL = "http://pos-inventory/v1/inventory";

  private MockRestServiceServer mockServer;
  private InventoryFacadeTool tool;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder();
    mockServer = MockRestServiceServer.bindTo(builder).build();
    tool = new InventoryFacadeTool(builder, BASE_URL);
  }

  @Test
  @DisplayName("checkStock sends GET /stock/{sku} and returns body")
  void checkStock_sendsGetToStockEndpoint() {
    mockServer.expect(requestTo(BASE_URL + "/stock/SKU001"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("{\"sku\":\"SKU001\",\"qty\":42}", MediaType.APPLICATION_JSON));

    String result = tool.checkStock("SKU001");

    mockServer.verify();
    assertThat(result).contains("SKU001");
  }

  @Test
  @DisplayName("searchInventory sends GET /search?q={query} and returns body")
  void searchInventory_sendsGetToSearchEndpoint() {
    mockServer.expect(requestTo(BASE_URL + "/search?q=oil%20filter"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("{\"items\":[]}", MediaType.APPLICATION_JSON));

    String result = tool.searchInventory("oil filter");

    mockServer.verify();
    assertThat(result).contains("items");
  }

  @Test
  @DisplayName("getLocationStock sends GET /locations/{locationId}/stock and returns body")
  void getLocationStock_returnsStockForLocation() {
    mockServer.expect(requestTo(BASE_URL + "/locations/LOC-001/stock"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("{\"locationId\":\"LOC-001\",\"items\":[]}", MediaType.APPLICATION_JSON));

    String result = tool.getLocationStock("LOC-001");

    mockServer.verify();
    assertThat(result).contains("LOC-001");
  }
}
