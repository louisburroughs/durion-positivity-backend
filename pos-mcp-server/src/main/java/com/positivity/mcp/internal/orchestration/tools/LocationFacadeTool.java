package com.positivity.mcp.internal.orchestration.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class LocationFacadeTool {

  private final RestClient restClient;

  public LocationFacadeTool(
      RestClient.Builder restClientBuilder,
      @Value("${pos.location.base-url:http://pos-location/v1/locations}") @NonNull String baseUrl) {
    this.restClient = restClientBuilder
        .baseUrl(baseUrl)
        .build();
  }

  @Tool("Get location details by location ID")
  public String getLocation(
      @P("The location ID") @NonNull String locationId) {
    return restClient.get()
        .uri("/{locationId}", locationId)
        .retrieve()
        .body(String.class);
  }

  @Tool("Search locations by name, city, code, or attributes")
  public String searchLocations(
      @P("Search query for locations") @NonNull String query) {
    return restClient.get()
        .uri(uriBuilder -> uriBuilder
            .path("/search")
            .queryParam("q", query)
            .build())
        .retrieve()
        .body(String.class);
  }

  @Tool("Get inventory context for a specific location")
  public String getLocationInventory(
      @P("The location ID") @NonNull String locationId) {
    return restClient.get()
        .uri("/{locationId}/inventory", locationId)
        .retrieve()
        .body(String.class);
  }
}
