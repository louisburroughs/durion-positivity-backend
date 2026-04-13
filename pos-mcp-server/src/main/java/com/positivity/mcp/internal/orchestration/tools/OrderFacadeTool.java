package com.positivity.mcp.internal.orchestration.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class OrderFacadeTool {

    private final RestClient restClient;

    public OrderFacadeTool(
            RestClient.Builder restClientBuilder,
            @Value("${pos.order.base-url:http://pos-order/v1/orders}") @NonNull String baseUrl) {
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