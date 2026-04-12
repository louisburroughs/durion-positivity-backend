package com.positivity.mcp.internal.orchestration.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class AdminFacadeTool {

  private final RestClient securityRestClient;

  public AdminFacadeTool(RestClient.Builder restClientBuilder) {
    this.securityRestClient = restClientBuilder
        .baseUrl("http://pos-security-service/v1")
        .build();
  }

  @Tool("Get overall platform system status and health summary")
  public String getSystemStatus() {
    return "pos-mcp-server status: UP";
  }

  @Tool("Get effective user permissions and access details")
  public String getUserPermissions(
      @P("The user ID") @NonNull String userId) {
    return securityRestClient.get()
        .uri("/users/{userId}/roles", userId)
        .retrieve()
        .body(String.class);
  }

  @Tool("Search the administrative audit log with a query string")
  public String getAuditLog(
      @P("Search query for audit log") @NonNull String query) {
    return securityRestClient.get()
        .uri("/audit?q={q}", query)
        .retrieve()
        .body(String.class);
  }
}
