package com.positivity.mcp.internal.orchestration.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class WorkorderFacadeTool {

  private final RestClient restClient;

  public WorkorderFacadeTool(RestClient.Builder restClientBuilder) {
    this.restClient = restClientBuilder
        .baseUrl("http://pos-workorder/v1/workorders")
        .build();
  }

  @Tool("Get full workorder details by workorder ID")
  public String getWorkorder(
      @P("The workorder ID") @NonNull String workorderId) {
    return restClient.get()
        .uri("/{workorderId}", workorderId)
        .retrieve()
        .body(String.class);
  }

  @Tool("Search workorders by customer, status, or vehicle criteria")
  public String searchWorkorders(
      @P("Search query for workorders") @NonNull String query) {
    return restClient.get()
        .uri(uriBuilder -> uriBuilder
            .path("/search")
            .queryParam("q", query)
            .build())
        .retrieve()
        .body(String.class);
  }

  @Tool("Get current lifecycle status for a workorder")
  public String getWorkorderStatus(
      @P("The workorder ID") @NonNull String workorderId) {
    return restClient.get()
        .uri("/{workorderId}/status", workorderId)
        .retrieve()
        .body(String.class);
  }
}
