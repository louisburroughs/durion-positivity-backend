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
 * Unit tests for {@link CustomerFacadeTool}: verifies RestClient call shapes.
 */
class CustomerFacadeToolTest {

  private static final String BASE_URL = "http://pos-customer/v1/customers";

  private MockRestServiceServer mockServer;
  private CustomerFacadeTool tool;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder();
    mockServer = MockRestServiceServer.bindTo(builder).build();
    tool = new CustomerFacadeTool(builder, BASE_URL);
  }

  @Test
  @DisplayName("getCustomer sends GET /{customerId} and returns body")
  void getCustomer_sendsGetToCustomerEndpoint() {
    mockServer.expect(requestTo(BASE_URL + "/CUST-001"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("{\"id\":\"CUST-001\"}", MediaType.APPLICATION_JSON));

    String result = tool.getCustomer("CUST-001");

    mockServer.verify();
    assertThat(result).isNotEmpty().contains("CUST-001");
  }

  @Test
  @DisplayName("searchCustomers sends GET /search?q={query} and returns body")
  void searchCustomers_sendsGetToSearchEndpoint() {
    mockServer.expect(requestTo(BASE_URL + "/search?q=Smith"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));

    String result = tool.searchCustomers("Smith");

    mockServer.verify();
    assertThat(result).isNotEmpty();
  }

  @Test
  @DisplayName("getCustomerHistory sends GET /{customerId}/history and returns body")
  void getCustomerHistory_sendsGetToHistoryEndpoint() {
    mockServer.expect(requestTo(BASE_URL + "/CUST-001/history"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("{\"events\":[]}", MediaType.APPLICATION_JSON));

    String result = tool.getCustomerHistory("CUST-001");

    mockServer.verify();
    assertThat(result).isNotEmpty();
  }
}
