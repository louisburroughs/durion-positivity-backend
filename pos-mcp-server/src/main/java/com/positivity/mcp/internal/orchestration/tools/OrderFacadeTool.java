package com.positivity.mcp.internal.orchestration.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class OrderFacadeTool {

    private final RestClient restClient;
    private final String orderUriTemplate;
    private final String orderSearchUriTemplate;

    public OrderFacadeTool(
            RestClient.Builder restClientBuilder,
            @Value("${pos.order.base-url}") @NonNull String baseUrl,
            @Value("${pos.order.order-uri-template}") @NonNull String orderUriTemplate,
            @Value("${pos.order.search-uri-template}") @NonNull String orderSearchUriTemplate) {
        this.restClient = ToolRestClientSupport.instrumentedClient(restClientBuilder, baseUrl);
        this.orderUriTemplate = orderUriTemplate;
        this.orderSearchUriTemplate = orderSearchUriTemplate;
    }

    @Tool("Look up an order by order ID")
    public String getOrder(@P("The order ID") @NonNull String orderId) {
        return restClient
                .get()
                .uri(orderUriTemplate, Map.of("orderId", orderId))
                .retrieve()
                .body(String.class);
    }

    @Tool("Search orders by customer name, date range, or status")
    public String searchOrders(@P("Search query: customer name, date, or status") @NonNull String query) {
        return restClient
                .get()
                .uri(orderSearchUriTemplate, Map.of("query", query))
                .retrieve()
                .body(String.class);
    }
}
