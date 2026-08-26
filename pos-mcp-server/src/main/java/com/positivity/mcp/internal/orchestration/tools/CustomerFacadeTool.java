package com.positivity.mcp.internal.orchestration.tools;

import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class CustomerFacadeTool {

    private final RestClient restClient;
    private final String customerUriTemplate;
    private final String customerSearchUriTemplate;
    private final String customerHistoryUriTemplate;

    public CustomerFacadeTool(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("${pos.customer.base-url}") @NonNull String baseUrl,
            @Value("${pos.customer.customer-uri-template}") @NonNull String customerUriTemplate,
            @Value("${pos.customer.search-uri-template}") @NonNull String customerSearchUriTemplate,
            @Value("${pos.customer.history-uri-template}") @NonNull String customerHistoryUriTemplate) {
        this.restClient = ToolRestClientSupport.instrumentedClient(restClientBuilder, baseUrl);
        this.customerUriTemplate = customerUriTemplate;
        this.customerSearchUriTemplate = customerSearchUriTemplate;
        this.customerHistoryUriTemplate = customerHistoryUriTemplate;
    }

    @Tool(
            description = "Get a customer's identity projection by party id (UUID): display name, type "
                    + "(commercial or person), and customer number. This is a thin identity payload — it does "
                    + "not include vehicles, invoices, or interaction history.")
    public String getCustomer(@ToolParam(description = "The customer's party id (UUID)") @NonNull String partyId) {
        return restClient
                .get()
                .uri(customerUriTemplate, Map.of("partyId", partyId))
                .retrieve()
                .body(String.class);
    }

    @Tool(
            description = "Search the unified customer directory (commercial accounts and individual customers) "
                    + "by name. The match is case-insensitive contains on the customer name; results are "
                    + "paginated.")
    public String searchCustomers(@ToolParam(description = "Customer name (or part of it)") @NonNull String query) {
        return restClient
                .get()
                .uri(customerSearchUriTemplate, Map.of("query", query))
                .retrieve()
                .body(String.class);
    }

    @Tool(description = "Get account history and interaction timeline for a customer")
    public String getCustomerHistory(@ToolParam(description = "The customer ID") @NonNull String customerId) {
        return restClient
                .get()
                .uri(customerHistoryUriTemplate, Map.of("customerId", customerId))
                .retrieve()
                .body(String.class);
    }
}
