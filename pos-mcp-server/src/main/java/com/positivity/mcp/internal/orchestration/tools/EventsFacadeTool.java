package com.positivity.mcp.internal.orchestration.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class EventsFacadeTool {

  private final RestClient restClient;

  public EventsFacadeTool(RestClient.Builder restClientBuilder) {
    this.restClient = restClientBuilder
        .baseUrl("http://pos-event-receiver/v1")
        .build();
  }

  @Tool("Get all registered event types and metadata")
  public String getEventTypes() {
    return restClient.get()
        .uri("/eventTypes")
        .retrieve()
        .body(String.class);
  }

  @Tool("Search events by query text, event type, or other criteria")
  public String searchEvents(
      @P("Search query for events") @NonNull String query) {
    return restClient.get()
        .uri(uriBuilder -> uriBuilder
            .path("/events/summary")
            .queryParam("q", query)
            .build())
        .retrieve()
        .body(String.class);
  }

  @Tool("Get historical event activity for a specific entity")
  public String getEventHistory(
      @P("The entity ID") @NonNull String entityId) {
    return restClient.get()
        .uri(uriBuilder -> uriBuilder
            .path("/events/summary")
            .queryParam("entityId", entityId)
            .build())
        .retrieve()
        .body(String.class);
  }
}
