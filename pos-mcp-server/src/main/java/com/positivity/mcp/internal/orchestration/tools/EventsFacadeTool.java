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

    public EventsFacadeTool(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("${pos.event-receiver.base-url}") @NonNull String baseUrl,
            @Value("${pos.event-receiver.event-types-uri-template}") @NonNull String eventTypesUriTemplate,
            @Value("${pos.event-receiver.summary-uri-template}") @NonNull String eventSummaryUriTemplate) {
        this.restClient = ToolRestClientSupport.instrumentedClient(restClientBuilder, baseUrl);
        this.eventTypesUriTemplate = eventTypesUriTemplate;
        this.eventSummaryUriTemplate = eventSummaryUriTemplate;
    }

    @Tool(description = "List all active registered event types with their metadata and thresholds")
    public String getEventTypes() {
        return restClient.get().uri(eventTypesUriTemplate).retrieve().body(String.class);
    }

    @Tool(
            description = "Get an aggregate summary of platform event activity for a recent time window. "
                    + "window must be exactly one of: lastHour, lastDay, lastWeek. The summary takes no other "
                    + "filters — there is no per-entity or free-text event search.")
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
}
