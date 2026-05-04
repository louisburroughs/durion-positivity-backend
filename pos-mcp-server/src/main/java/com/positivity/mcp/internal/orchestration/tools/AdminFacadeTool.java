package com.positivity.mcp.internal.orchestration.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class AdminFacadeTool {

    private final RestClient usersRestClient;
    private final RestClient auditRestClient;
    private final String userPermissionsPathTemplate;
    private final String auditSearchUriTemplate;

    public AdminFacadeTool(
            RestClient.Builder restClientBuilder,
            @Value("${pos.security.users.base-url}") @NonNull String usersBaseUrl,
            @Value("${pos.security.audit.base-url}") @NonNull String auditBaseUrl,
            @Value("${pos.security.user-permissions-path-template}") @NonNull String userPermissionsPathTemplate,
            @Value("${pos.security.audit-search-uri-template}") @NonNull String auditSearchUriTemplate) {
        this.usersRestClient = ToolRestClientSupport.instrumentedClient(restClientBuilder, usersBaseUrl);
        this.auditRestClient = ToolRestClientSupport.instrumentedClient(restClientBuilder, auditBaseUrl);
        this.userPermissionsPathTemplate = userPermissionsPathTemplate;
        this.auditSearchUriTemplate = auditSearchUriTemplate;
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
        return usersRestClient
                .get()
                .uri(userPermissionsPathTemplate, Map.of("userId", userId))
                .retrieve()
                .body(String.class);
    }

    @Tool("Search the administrative audit log with a query string")
    public String getAuditLog(@P("Search query for audit log") @NonNull String query) {
        return auditRestClient
                .get()
                .uri(auditSearchUriTemplate, Map.of("query", query))
                .retrieve()
                .body(String.class);
    }
}
