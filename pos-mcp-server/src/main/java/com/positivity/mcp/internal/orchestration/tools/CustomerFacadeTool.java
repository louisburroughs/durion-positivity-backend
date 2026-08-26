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
    private final String snapshotUriTemplate;
    private final String interactionsUriTemplate;
    private final String invoicesUriTemplate;
    private final String workordersUriTemplate;

    public CustomerFacadeTool(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("${pos.customer.base-url}") @NonNull String baseUrl,
            @Value("${pos.customer.customer-uri-template}") @NonNull String customerUriTemplate,
            @Value("${pos.customer.search-uri-template}") @NonNull String customerSearchUriTemplate,
            @Value("${pos.customer.snapshot-uri-template}") @NonNull String snapshotUriTemplate,
            @Value("${pos.customer.interactions-uri-template}") @NonNull String interactionsUriTemplate,
            @Value("${pos.customer.invoices-uri-template}") @NonNull String invoicesUriTemplate,
            @Value("${pos.customer.workorders-uri-template}") @NonNull String workordersUriTemplate) {
        this.restClient = ToolRestClientSupport.instrumentedClient(restClientBuilder, baseUrl);
        this.customerUriTemplate = customerUriTemplate;
        this.customerSearchUriTemplate = customerSearchUriTemplate;
        this.snapshotUriTemplate = snapshotUriTemplate;
        this.interactionsUriTemplate = interactionsUriTemplate;
        this.invoicesUriTemplate = invoicesUriTemplate;
        this.workordersUriTemplate = workordersUriTemplate;
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

    @Tool(
            description = "Get a composed history for a customer by party id (UUID) — the customerId "
                    + "argument IS the CRM party id. Returns a JSON envelope with four sections: snapshot "
                    + "(the CRM party snapshot), interactions (the interaction timeline, newest first), "
                    + "invoices (the customer's invoice line items), and workorders (the customer's "
                    + "workorders). Every section is optional: one the caller is not authorized for, or "
                    + "that fails, reports its own status while the rest still answer.")
    public String getCustomerHistory(
            @ToolParam(description = "The customer's party id (UUID)") @NonNull String customerId) {
        Map<String, String> uriParams = Map.of("partyId", customerId);
        return ToolComposition.named("customerHistory")
                .call("snapshot", () -> restClient
                        .get()
                        .uri(snapshotUriTemplate, uriParams)
                        .retrieve()
                        .body(String.class))
                .call("interactions", () -> restClient
                        .get()
                        .uri(interactionsUriTemplate, uriParams)
                        .retrieve()
                        .body(String.class))
                .call("invoices", () -> restClient
                        .get()
                        .uri(invoicesUriTemplate, uriParams)
                        .retrieve()
                        .body(String.class))
                .call("workorders", () -> restClient
                        .get()
                        .uri(workordersUriTemplate, uriParams)
                        .retrieve()
                        .body(String.class))
                .render();
    }
}
