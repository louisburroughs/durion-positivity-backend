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
 * Unit tests for {@link InvoiceFacadeTool}: verifies RestClient call shapes.
 */
class InvoiceFacadeToolTest {

  private static final String BASE_URL = "http://pos-invoice/v1/invoices";

  private MockRestServiceServer mockServer;
  private InvoiceFacadeTool tool;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder();
    mockServer = MockRestServiceServer.bindTo(builder).build();
    tool = new InvoiceFacadeTool(builder);
  }

  @Test
  @DisplayName("getInvoice sends GET /{invoiceId} and returns body")
  void getInvoice_sendsGetToInvoiceEndpoint() {
    mockServer.expect(requestTo(BASE_URL + "/INV-001"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("{\"id\":\"INV-001\"}", MediaType.APPLICATION_JSON));

    String result = tool.getInvoice("INV-001");

    mockServer.verify();
    assertThat(result).isNotEmpty().contains("INV-001");
  }

  @Test
  @DisplayName("searchInvoices sends GET /search?q={query} and returns body")
  void searchInvoices_sendsGetToSearchEndpoint() {
    mockServer.expect(requestTo(BASE_URL + "/search?q=PAID"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));

    String result = tool.searchInvoices("PAID");

    mockServer.verify();
    assertThat(result).isNotEmpty();
  }

  @Test
  @DisplayName("getInvoicesByCustomer sends GET /customer/{customerId} and returns body")
  void getInvoicesByCustomer_sendsGetToCustomerInvoicesEndpoint() {
    mockServer.expect(requestTo(BASE_URL + "/customer/CUST-001"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("{\"invoices\":[]}", MediaType.APPLICATION_JSON));

    String result = tool.getInvoicesByCustomer("CUST-001");

    mockServer.verify();
    assertThat(result).isNotEmpty();
  }
}
