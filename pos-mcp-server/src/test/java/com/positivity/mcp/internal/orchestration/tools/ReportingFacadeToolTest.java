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
 * Unit tests for {@link ReportingFacadeTool}: verifies RestClient call shapes.
 */
class ReportingFacadeToolTest {

  private static final String BASE_URL = "http://pos-accounting/v1/reporting";

  private MockRestServiceServer mockServer;
  private ReportingFacadeTool tool;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder();
    mockServer = MockRestServiceServer.bindTo(builder).build();
    tool = new ReportingFacadeTool(builder, BASE_URL);
  }

  @Test
  @DisplayName("getSalesReport sends GET /sales/{period} and returns body")
  void getSalesReport_sendsGetToSalesEndpoint() {
    mockServer.expect(requestTo(BASE_URL + "/sales/2025-Q1"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("{\"period\":\"2025-Q1\",\"total\":50000}", MediaType.APPLICATION_JSON));

    String result = tool.getSalesReport("2025-Q1");

    mockServer.verify();
    assertThat(result).isNotEmpty().contains("2025-Q1");
  }

  @Test
  @DisplayName("getInventoryReport sends GET /inventory/{locationId} and returns body")
  void getInventoryReport_sendsGetToInventoryEndpoint() {
    mockServer.expect(requestTo(BASE_URL + "/inventory/LOC-001"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("{\"locationId\":\"LOC-001\",\"items\":[]}", MediaType.APPLICATION_JSON));

    String result = tool.getInventoryReport("LOC-001");

    mockServer.verify();
    assertThat(result).isNotEmpty();
  }

  @Test
  @DisplayName("getRevenueReport sends GET /revenue/{period} and returns body")
  void getRevenueReport_sendsGetToRevenueEndpoint() {
    mockServer.expect(requestTo(BASE_URL + "/revenue/2025-Q1"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("{\"period\":\"2025-Q1\",\"revenue\":75000}", MediaType.APPLICATION_JSON));

    String result = tool.getRevenueReport("2025-Q1");

    mockServer.verify();
    assertThat(result).isNotEmpty();
  }
}
