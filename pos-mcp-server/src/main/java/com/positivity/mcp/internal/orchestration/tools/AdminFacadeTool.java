package com.positivity.mcp.internal.orchestration.tools;

import com.positivity.security.common.SecurityContextHelper;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class AdminFacadeTool {

    private final RestClient usersRestClient;
    private final RestClient auditRestClient;
    private final String usersListPath;
    private final String userPermissionsPathTemplate;
    private final String auditSearchUriTemplate;

    public AdminFacadeTool(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("${pos.security.users.base-url}") @NonNull String usersBaseUrl,
            @Value("${pos.security.audit.base-url}") @NonNull String auditBaseUrl,
            @Value("${pos.security.users-list-path}") @NonNull String usersListPath,
            @Value("${pos.security.user-permissions-path-template}") @NonNull String userPermissionsPathTemplate,
            @Value("${pos.security.audit-search-uri-template}") @NonNull String auditSearchUriTemplate) {
        this.usersRestClient = ToolRestClientSupport.instrumentedClient(restClientBuilder, usersBaseUrl);
        this.auditRestClient = ToolRestClientSupport.instrumentedClient(restClientBuilder, auditBaseUrl);
        this.usersListPath = usersListPath;
        this.userPermissionsPathTemplate = userPermissionsPathTemplate;
        this.auditSearchUriTemplate = auditSearchUriTemplate;
    }

    @Tool(
            description = "Report whether the MCP server itself is reachable. This is a static self-check — it "
                    + "does not query the health of any other platform service, and cannot diagnose outages "
                    + "elsewhere.")
    public String getSystemStatus() {
        return "pos-mcp-server status: UP";
    }

    @Tool(
            description =
                    "List all users registered in the platform. Returns each user's ID, username, and assigned roles. "
                            + "Use this to answer questions about the total number of users, look up users by name, or audit who has platform access.")
    public String listUsers() {
        return usersRestClient.get().uri(usersListPath).retrieve().body(String.class);
    }

    @Tool(description = "Get effective user permissions and access details")
    public String getUserPermissions(@ToolParam(description = "The user ID") @NonNull String userId) {
        return usersRestClient
                .get()
                .uri(userPermissionsPathTemplate, Map.of("userId", userId))
                .retrieve()
                .body(String.class);
    }

    @Tool(
            description =
                    "Get effective permissions for the currently authenticated user. Use this when the user asks what permissions they have or what access they currently hold.")
    public String getMyPermissions() {
        return getUserPermissions(SecurityContextHelper.getCurrentUserIdAsUuidOrThrowIllegalStateException()
                .toString());
    }

    @Tool(
            description = "List administrative audit-log events filtered by event type. The audit endpoint "
                    + "applies only fromDate, toDate, actorId, eventType, and aggregateId filters; this tool "
                    + "filters by eventType — free-text queries are not supported. Returns only the first page "
                    + "of matches (default size 20).")
    public String getAuditLog(@ToolParam(description = "The audit event type to filter by") @NonNull String query) {
        return auditRestClient
                .get()
                .uri(auditSearchUriTemplate, Map.of("query", query))
                .retrieve()
                .body(String.class);
    }
}
