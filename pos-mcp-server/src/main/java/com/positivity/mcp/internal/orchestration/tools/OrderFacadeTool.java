package com.positivity.mcp.internal.orchestration.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Phase 1 facade tool for order lookup operations.
 * <p>
 * <strong>Phase 1 Limitation (ADR-0014):</strong> Outbound calls to pos-order
 * do not propagate the caller's JWT bearer token. Tool calls will receive 401
 * responses
 * from pos-order unless that service permits unauthenticated access on these
 * paths.
 * Bearer token propagation will be implemented in Phase 2 via a shared
 * {@code ClientHttpRequestInterceptor} on the RestClient.
 * <p>
 * Tracked for Phase2: add auth propagation via SecurityContext-sourced bearer
 * token.
 */
@Component
public class OrderFacadeTool {

  private final RestClient restClient;

  public OrderFacadeTool(
      RestClient.Builder restClientBuilder,
      @Value("${pos.order.base-url:http://pos-order/v1/orders}") @NonNull String baseUrl) {
    // Phase 1 limitation: RestClient is constructed without auth propagation
    // interceptors.
    this.restClient = restClientBuilder
        .baseUrl(baseUrl)
        .build();
  }

  @Tool("Look up an order by order ID")
  public String getOrder(
      @P("The order ID") @NonNull String orderId) {
    return restClient.get()
        .uri("/{orderId}", orderId)
        .retrieve()
        .body(String.class);
  }

  @Tool("Search orders by customer name, date range, or status")
  public String searchOrders(
      @P("Search query: customer name, date, or status") @NonNull String query) {
    return restClient.get()
        .uri(uriBuilder -> uriBuilder
            .path("/search")
            .queryParam("q", query)
            .build())
        .retrieve()
        .body(String.class);
  }
}