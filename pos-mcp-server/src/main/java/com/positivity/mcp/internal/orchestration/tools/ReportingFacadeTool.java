package com.positivity.mcp.internal.orchestration.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class ReportingFacadeTool {

  private final RestClient restClient;

  public ReportingFacadeTool(RestClient.Builder restClientBuilder) {
    this.restClient = restClientBuilder
        .baseUrl("http://pos-accounting/v1/reports")
        .build();
  }

  @Tool("Get a sales report for a requested period")
  public String getSalesReport(
      @P("Reporting period identifier") @NonNull String period) {
    return restClient.get()
        .uri("/sales/{period}", period)
        .retrieve()
        .body(String.class);
  }

  @Tool("Get an inventory report for a specific location")
  public String getInventoryReport(
      @P("The location ID") @NonNull String locationId) {
    return restClient.get()
        .uri("/inventory/{locationId}", locationId)
        .retrieve()
        .body(String.class);
  }

  @Tool("Get a revenue report for a requested period")
  public String getRevenueReport(
      @P("Reporting period identifier") @NonNull String period) {
    return restClient.get()
        .uri("/revenue/{period}", period)
        .retrieve()
        .body(String.class);
  }
}
