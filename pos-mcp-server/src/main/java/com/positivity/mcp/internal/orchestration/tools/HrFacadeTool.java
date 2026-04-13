package com.positivity.mcp.internal.orchestration.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class HrFacadeTool {

  private final RestClient restClient;

  public HrFacadeTool(
      RestClient.Builder restClientBuilder,
      @Value("${pos.hr.base-url:http://pos-people/v1/hr}") @NonNull String baseUrl) {
    this.restClient = restClientBuilder
        .baseUrl(baseUrl)
        .build();
  }

  @Tool("Get employee profile information by employee ID")
  public String getEmployee(
      @P("The employee ID") @NonNull String employeeId) {
    return restClient.get()
        .uri("/{employeeId}", employeeId)
        .retrieve()
        .body(String.class);
  }

  @Tool("Search employees by name, role, team, or availability")
  public String searchEmployees(
      @P("Search query for employees") @NonNull String query) {
    return restClient.get()
        .uri(uriBuilder -> uriBuilder
            .path("/search")
            .queryParam("q", query)
            .build())
        .retrieve()
        .body(String.class);
  }

  @Tool("Get current schedule details for a specific employee")
  public String getEmployeeSchedule(
      @P("The employee ID") @NonNull String employeeId) {
    return restClient.get()
        .uri("/{employeeId}/schedule", employeeId)
        .retrieve()
        .body(String.class);
  }
}
