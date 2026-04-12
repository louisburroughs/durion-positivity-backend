package com.positivity.mcp.internal.orchestration.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class InvoiceFacadeTool {

  private final RestClient restClient;

  public InvoiceFacadeTool(RestClient.Builder restClientBuilder) {
    this.restClient = restClientBuilder
        .baseUrl("http://pos-invoice/v1/invoices")
        .build();
  }

  @Tool("Get invoice details by invoice ID")
  public String getInvoice(
      @P("The invoice ID") @NonNull String invoiceId) {
    return restClient.get()
        .uri("/{invoiceId}", invoiceId)
        .retrieve()
        .body(String.class);
  }

  @Tool("Search invoices by status, customer, date, or amount")
  public String searchInvoices(
      @P("Search query for invoices") @NonNull String query) {
    return restClient.get()
        .uri(uriBuilder -> uriBuilder
            .path("/search")
            .queryParam("q", query)
            .build())
        .retrieve()
        .body(String.class);
  }

  @Tool("Get invoices linked to a specific customer")
  public String getInvoicesByCustomer(
      @P("The customer ID") @NonNull String customerId) {
    return restClient.get()
        .uri("/customer/{customerId}", customerId)
        .retrieve()
        .body(String.class);
  }
}
