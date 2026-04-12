package com.positivity.mcp.internal.orchestration.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class ShopManagerFacadeTool {

  private final RestClient restClient;

  public ShopManagerFacadeTool(RestClient.Builder restClientBuilder) {
    this.restClient = restClientBuilder
        .baseUrl("http://pos-shop-manager/v1/shop")
        .build();
  }

  @Tool("Get overall status for a shop location")
  public String getShopStatus(
      @P("The shop ID") @NonNull String shopId) {
    return restClient.get()
        .uri("/{shopId}/status", shopId)
        .retrieve()
        .body(String.class);
  }

  @Tool("Get the active queue and workflow load for a shop")
  public String getShopQueue(
      @P("The shop ID") @NonNull String shopId) {
    return restClient.get()
        .uri("/{shopId}/queue", shopId)
        .retrieve()
        .body(String.class);
  }

  @Tool("Search shops by name, region, status, or manager")
  public String searchShops(
      @P("Search query for shops") @NonNull String query) {
    return restClient.get()
        .uri(uriBuilder -> uriBuilder
            .path("/search")
            .queryParam("q", query)
            .build())
        .retrieve()
        .body(String.class);
  }
}
