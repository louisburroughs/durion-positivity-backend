package com.positivity.mcp.internal.orchestration.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class CustomerFacadeTool {

  private final RestClient restClient;

  public CustomerFacadeTool(
      RestClient.Builder restClientBuilder,
      @Value("${pos.customer.base-url:http://pos-customer/v1/customers}") @NonNull String baseUrl) {
    this.restClient = restClientBuilder
        .baseUrl(baseUrl)
        .build();
  }

  @Tool("Get customer profile details by customer ID")
  public String getCustomer(
      @P("The customer ID") @NonNull String customerId) {
    return restClient.get()
        .uri("/{customerId}", customerId)
        .retrieve()
        .body(String.class);
  }

  @Tool("Search customers by name, phone number, email, or other criteria")
  public String searchCustomers(
      @P("Search query for customers") @NonNull String query) {
    return restClient.get()
        .uri(uriBuilder -> uriBuilder
            .path("/search")
            .queryParam("q", query)
            .build())
        .retrieve()
        .body(String.class);
  }

  @Tool("Get account history and interaction timeline for a customer")
  public String getCustomerHistory(
      @P("The customer ID") @NonNull String customerId) {
    return restClient.get()
        .uri("/{customerId}/history", customerId)
        .retrieve()
        .body(String.class);
  }
}
