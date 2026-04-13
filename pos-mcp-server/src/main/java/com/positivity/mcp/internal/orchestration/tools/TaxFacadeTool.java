package com.positivity.mcp.internal.orchestration.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class TaxFacadeTool {

  private final RestClient restClient;

  public TaxFacadeTool(
      RestClient.Builder restClientBuilder,
      @Value("${pos.tax.base-url:http://pos-tax/v1/tax}") @NonNull String baseUrl) {
    this.restClient = restClientBuilder
        .baseUrl(baseUrl)
        .build();
  }

  @Tool("Get the tax rate for a specific location")
  public String getTaxRate(
      @P("The location ID") @NonNull String locationId) {
    return restClient.get()
        .uri("/rates/{locationId}", locationId)
        .retrieve()
        .body(String.class);
  }

  @Tool("Calculate estimated tax amount for an amount and location")
  public String calculateTax(
      @P("The taxable amount") @NonNull String amount,
      @P("The location ID") @NonNull String locationId) {
    return restClient.get()
        .uri(uriBuilder -> uriBuilder
            .path("/calculate")
            .queryParam("amount", amount)
            .queryParam("locationId", locationId)
            .build())
        .retrieve()
        .body(String.class);
  }

  @Tool("Get tax summary details for a reporting period")
  public String getTaxSummary(
      @P("Tax reporting period") @NonNull String period) {
    return restClient.get()
        .uri("/summary/{period}", period)
        .retrieve()
        .body(String.class);
  }
}
