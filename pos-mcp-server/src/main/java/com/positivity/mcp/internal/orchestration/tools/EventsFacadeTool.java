package com.positivity.mcp.internal.orchestration.tools;

import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class EventsFacadeTool {

    private static final Set<String> SUMMARY_WINDOWS = Set.of("lastHour", "lastDay", "lastWeek");

    private final RestClient restClient;
    private final String eventTypesUriTemplate;
    private final String eventSummaryUriTemplate;
    private final String eventHistoryUriTemplate;

    public EventsFacadeTool(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("${pos.event-receiver.base-url}") @NonNull String baseUrl,
            @Value("${pos.event-receiver.event-types-uri-template}") @NonNull String eventTypesUriTemplate,
            @Value("${pos.event-receiver.summary-uri-template}") @NonNull String eventSummaryUriTemplate,
            @Value("${pos.event-receiver.history-uri-template}") @NonNull String eventHistoryUriTemplate) {
        this.restClient = ToolRestClientSupport.instrumentedClient(restClientBuilder, baseUrl);
        this.eventTypesUriTemplate = eventTypesUriTemplate;
        this.eventSummaryUriTemplate = eventSummaryUriTemplate;
        this.eventHistoryUriTemplate = eventHistoryUriTemplate;
    }

    @Tool(description = "List all active registered event types with their metadata and thresholds")
    public String getEventTypes() {
        return restClient.get().uri(eventTypesUriTemplate).retrieve().body(String.class);
    }

    @Tool(
            description = "Get an aggregate summary of platform event activity for a recent time window. "
                    + "window must be exactly one of: lastHour, lastDay, lastWeek. The summary takes no other "
                    + "filters; use getEventHistory instead for one entity's own recorded events.")
    public String getEventSummary(
            @ToolParam(description = "Time window: lastHour, lastDay, or lastWeek") @NonNull String window) {
        if (!SUMMARY_WINDOWS.contains(window)) {
            throw new IllegalArgumentException(
                    "Unsupported window '" + window + "': must be one of lastHour, lastDay, lastWeek");
        }
        return restClient
                .get()
                .uri(eventSummaryUriTemplate, Map.of("window", window))
                .retrieve()
                .body(String.class);
    }

    @Tool(
            description = "Get the recorded event history for one entity, newest first. entityId is required; "
                    + "since, page, and size are not exposed here and all default server-side (since defaults to "
                    + "7 days ago, page to 0, size to 50). Only events recorded with an entity id are ever "
                    + "returned, so an entity with no matching events is not necessarily inactive — it may simply "
                    + "have no events tagged with an entity id.")
    public String getEventHistory(
            @ToolParam(description = "The entity id events were recorded against") @NonNull String entityId) {
        return restClient
                .get()
                .uri(eventHistoryUriTemplate, Map.of("entityId", entityId))
                .retrieve()
                .body(String.class);
    }
}
