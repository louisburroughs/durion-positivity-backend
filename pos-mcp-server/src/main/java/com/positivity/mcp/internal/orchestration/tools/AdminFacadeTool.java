package com.positivity.mcp.internal.orchestration.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class AdminFacadeTool {

    private final RestClient securityRestClient;
    private final RestClient usersRestClient;

    public AdminFacadeTool(
            RestClient.Builder restClientBuilder,
            @Value("${pos.admin.base-url:http://pos-security-service/v1/admin}") @NonNull String adminBaseUrl,
            @Value("${pos.security.users.base-url:http://pos-security-service/v1/users}") @NonNull String usersBaseUrl) {
        this.securityRestClient = restClientBuilder.baseUrl(adminBaseUrl).build();
        this.usersRestClient = restClientBuilder.baseUrl(usersBaseUrl).build();
    }

    @Tool("Get overall platform system status and health summary")
    public String getSystemStatus() {
        return "pos-mcp-server status: UP";
    }

    @Tool("List all users registered in the platform. Returns each user's ID, username, and assigned roles. " +
          "Use this to answer questions about the total number of users, look up users by name, or audit who has platform access.")
    public String listUsers() {
        return usersRestClient.get().retrieve().body(String.class);
    }

    @Tool("Get effective user permissions and access details")
    public String getUserPermissions(@P("The user ID") @NonNull String userId) {
        return securityRestClient
                .get()
                .uri("/users/{userId}/roles", userId)
                .retrieve()
                .body(String.class);
    }

    @Tool("Search the administrative audit log with a query string")
    public String getAuditLog(@P("Search query for audit log") @NonNull String query) {
        return securityRestClient.get().uri("/audit?q={q}", query).retrieve().body(String.class);
    }
}
