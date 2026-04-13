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
 * Unit tests for {@link ShopManagerFacadeTool}: verifies RestClient call
 * shapes.
 */
class ShopManagerFacadeToolTest {

  private static final String BASE_URL = "http://pos-shop-manager/v1/shop";

  private MockRestServiceServer mockServer;
  private ShopManagerFacadeTool tool;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder();
    mockServer = MockRestServiceServer.bindTo(builder).build();
    tool = new ShopManagerFacadeTool(builder, BASE_URL);
  }

  @Test
  @DisplayName("getShopStatus sends GET /{shopId}/status and returns body")
  void getShopStatus_sendsGetToStatusEndpoint() {
    mockServer.expect(requestTo(BASE_URL + "/SHOP-001/status"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("{\"shopId\":\"SHOP-001\",\"status\":\"OPEN\"}", MediaType.APPLICATION_JSON));

    String result = tool.getShopStatus("SHOP-001");

    mockServer.verify();
    assertThat(result).isNotEmpty().contains("SHOP-001");
  }

  @Test
  @DisplayName("getShopQueue sends GET /{shopId}/queue and returns body")
  void getShopQueue_sendsGetToQueueEndpoint() {
    mockServer.expect(requestTo(BASE_URL + "/SHOP-001/queue"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("{\"shopId\":\"SHOP-001\",\"queued\":3}", MediaType.APPLICATION_JSON));

    String result = tool.getShopQueue("SHOP-001");

    mockServer.verify();
    assertThat(result).isNotEmpty();
  }

  @Test
  @DisplayName("searchShops sends GET /search?q={query} and returns body")
  void searchShops_sendsGetToSearchEndpoint() {
    mockServer.expect(requestTo(BASE_URL + "/search?q=north"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));

    String result = tool.searchShops("north");

    mockServer.verify();
    assertThat(result).isNotEmpty();
  }
}
