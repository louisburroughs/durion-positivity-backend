package com.positivity.mcp.internal.orchestration.tools;

import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class OrderFacadeTool {

    private final RestClient restClient;
    private final String orderUriTemplate;
    private final String orderListUriTemplate;

    public OrderFacadeTool(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("${pos.order.base-url}") @NonNull String baseUrl,
            @Value("${pos.order.order-uri-template}") @NonNull String orderUriTemplate,
            @Value("${pos.order.list-uri-template}") @NonNull String orderListUriTemplate) {
        this.restClient = ToolRestClientSupport.instrumentedClient(restClientBuilder, baseUrl);
        this.orderUriTemplate = orderUriTemplate;
        this.orderListUriTemplate = orderListUriTemplate;
    }

    @Tool(
            description = "Get a sales order (cart) by its order id (UUID): line items, totals, status, and "
                    + "invoice references.")
    public String getOrder(@ToolParam(description = "The order id (UUID)") @NonNull String orderId) {
        return restClient
                .get()
                .uri(orderUriTemplate, Map.of("orderId", orderId))
                .retrieve()
                .body(String.class);
    }

    @Tool(
            description = "List sales orders (carts), optionally filtered by status (an order status name such "
                    + "as DRAFT), clerkId, or terminalId. These are the only filters — there is no customer, "
                    + "date-range, or free-text order search. Results are paginated.")
    public String listOrders(
            @ToolParam(description = "Optional order status name, e.g. DRAFT", required = false) String status,
            @ToolParam(description = "Optional clerk identifier", required = false) String clerkId,
            @ToolParam(description = "Optional terminal identifier", required = false) String terminalId) {
        StringBuilder template = new StringBuilder(orderListUriTemplate);
        Map<String, String> uriParams = new HashMap<>();
        appendQueryParam(template, uriParams, "status", status);
        appendQueryParam(template, uriParams, "clerkId", clerkId);
        appendQueryParam(template, uriParams, "terminalId", terminalId);
        return restClient.get().uri(template.toString(), uriParams).retrieve().body(String.class);
    }

    private static void appendQueryParam(
            StringBuilder template, Map<String, String> uriParams, String name, String value) {
        if (value != null && !value.isBlank()) {
            template.append(uriParams.isEmpty() ? '?' : '&')
                    .append(name)
                    .append("={")
                    .append(name)
                    .append('}');
            uriParams.put(name, value);
        }
    }
}
