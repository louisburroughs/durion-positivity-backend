package com.positivity.mcp.internal.orchestration.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class WorkorderFacadeTool {

    private final RestClient restClient;
    private final String workorderUriTemplate;
    private final String workorderSearchUriTemplate;
    private final String workorderStatusUriTemplate;

    public WorkorderFacadeTool(
            RestClient.Builder restClientBuilder,
            @Value("${pos.workorder.base-url}") @NonNull String baseUrl,
            @Value("${pos.workorder.workorder-uri-template}") @NonNull String workorderUriTemplate,
            @Value("${pos.workorder.search-uri-template}") @NonNull String workorderSearchUriTemplate,
            @Value("${pos.workorder.status-uri-template}") @NonNull String workorderStatusUriTemplate) {
        this.restClient = ToolRestClientSupport.instrumentedClient(restClientBuilder, baseUrl);
        this.workorderUriTemplate = workorderUriTemplate;
        this.workorderSearchUriTemplate = workorderSearchUriTemplate;
        this.workorderStatusUriTemplate = workorderStatusUriTemplate;
    }

    @Tool("Get full workorder details by workorder ID")
    public String getWorkorder(@P("The workorder ID") @NonNull String workorderId) {
        return restClient
                .get()
                .uri(workorderUriTemplate, Map.of("workorderId", workorderId))
                .retrieve()
                .body(String.class);
    }

    @Tool("Search workorders by customer, status, or vehicle criteria")
    public String searchWorkorders(@P("Search query for workorders") @NonNull String query) {
        return restClient
                .get()
                .uri(workorderSearchUriTemplate, Map.of("query", query))
                .retrieve()
                .body(String.class);
    }

    @Tool("Get current lifecycle status for a workorder")
    public String getWorkorderStatus(@P("The workorder ID") @NonNull String workorderId) {
        return restClient
                .get()
                .uri(workorderStatusUriTemplate, Map.of("workorderId", workorderId))
                .retrieve()
                .body(String.class);
    }
}
