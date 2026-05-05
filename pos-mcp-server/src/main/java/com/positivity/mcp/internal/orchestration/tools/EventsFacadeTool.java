package com.positivity.mcp.internal.orchestration.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.client.RestClient;

@Component
public class EventsFacadeTool {

    private final RestClient restClient;
    private final String eventTypesUriTemplate;
    private final String eventSearchUriTemplate;
    private final String eventHistoryUriTemplate;

    public EventsFacadeTool(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("${pos.event-receiver.base-url}") @NonNull String baseUrl,
            @Value("${pos.event-receiver.event-types-uri-template}") @NonNull String eventTypesUriTemplate,
            @Value("${pos.event-receiver.search-uri-template}") @NonNull String eventSearchUriTemplate,
            @Value("${pos.event-receiver.history-uri-template}") @NonNull String eventHistoryUriTemplate) {
        this.restClient = ToolRestClientSupport.instrumentedClient(restClientBuilder, baseUrl);
        this.eventTypesUriTemplate = eventTypesUriTemplate;
        this.eventSearchUriTemplate = eventSearchUriTemplate;
        this.eventHistoryUriTemplate = eventHistoryUriTemplate;
    }

    @Tool("Get all registered event types and metadata")
    public String getEventTypes() {
        return restClient.get().uri(eventTypesUriTemplate).retrieve().body(String.class);
    }

    @Tool("Search events by query text, event type, or other criteria")
    public String searchEvents(@P("Search query for events") @NonNull String query) {
        return restClient
                .get()
                .uri(eventSearchUriTemplate, Map.of("query", query))
                .retrieve()
                .body(String.class);
    }

    @Tool("Get historical event activity for a specific entity")
    public String getEventHistory(@P("The entity ID") @NonNull String entityId) {
        return restClient
                .get()
                .uri(eventHistoryUriTemplate, Map.of("entityId", entityId))
                .retrieve()
                .body(String.class);
    }
}
