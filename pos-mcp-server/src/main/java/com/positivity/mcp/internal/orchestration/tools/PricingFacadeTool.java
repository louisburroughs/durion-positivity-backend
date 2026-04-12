package com.positivity.mcp.internal.orchestration.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class PricingFacadeTool {

  private final RestClient restClient;

  public PricingFacadeTool(RestClient.Builder restClientBuilder) {
    this.restClient = restClientBuilder
        .baseUrl("http://pos-price/v1/pricing")
        .build();
  }

  @Tool("Get current price details for a specific SKU")
  public String getPriceForSku(
      @P("The SKU to price") @NonNull String sku) {
    return restClient.get()
        .uri("/sku/{sku}", sku)
        .retrieve()
        .body(String.class);
  }

  @Tool("Search pricing data by SKU, description, or pricing rule")
  public String searchPricing(
      @P("Search query for pricing") @NonNull String query) {
    return restClient.get()
        .uri(uriBuilder -> uriBuilder
            .path("/search")
            .queryParam("q", query)
            .build())
        .retrieve()
        .body(String.class);
  }

  @Tool("Get a full price list by price list ID")
  public String getPriceList(
      @P("The price list ID") @NonNull String priceListId) {
    return restClient.get()
        .uri("/lists/{priceListId}", priceListId)
        .retrieve()
        .body(String.class);
  }
}
