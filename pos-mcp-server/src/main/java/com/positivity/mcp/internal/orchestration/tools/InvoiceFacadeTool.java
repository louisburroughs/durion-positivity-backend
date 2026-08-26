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
public class InvoiceFacadeTool {

    private final RestClient restClient;
    private final String invoiceUriTemplate;
    private final String invoiceSearchUriTemplate;
    private final String customerInvoicesUriTemplate;

    public InvoiceFacadeTool(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("${pos.invoice.base-url}") @NonNull String baseUrl,
            @Value("${pos.invoice.invoice-uri-template}") @NonNull String invoiceUriTemplate,
            @Value("${pos.invoice.search-uri-template}") @NonNull String invoiceSearchUriTemplate,
            @Value("${pos.invoice.customer-invoices-uri-template}") @NonNull String customerInvoicesUriTemplate) {
        this.restClient = ToolRestClientSupport.instrumentedClient(restClientBuilder, baseUrl);
        this.invoiceUriTemplate = invoiceUriTemplate;
        this.invoiceSearchUriTemplate = invoiceSearchUriTemplate;
        this.customerInvoicesUriTemplate = customerInvoicesUriTemplate;
    }

    @Tool(description = "Get invoice details by invoice ID")
    public String getInvoice(@ToolParam(description = "The invoice ID") @NonNull String invoiceId) {
        return restClient
                .get()
                .uri(invoiceUriTemplate, Map.of("invoiceId", invoiceId))
                .retrieve()
                .body(String.class);
    }

    @Tool(description = "Search invoices by status, customer, date, or amount")
    public String searchInvoices(@ToolParam(description = "Search query for invoices") @NonNull String query) {
        return restClient
                .get()
                .uri(invoiceSearchUriTemplate, Map.of("query", query))
                .retrieve()
                .body(String.class);
    }

    @Tool(
            description = "Get the distinct invoices linked to a customer by party id (UUID). Built from the "
                    + "customer's newest invoice line items (bounded to the newest 200 lines), de-duplicated by "
                    + "invoice; each entry carries invoiceId, invoiceNumber, invoiceStatus, invoiceCreatedAt, "
                    + "and the number of matched lines.")
    public String getInvoicesByCustomer(
            @ToolParam(description = "The customer's party id (UUID)") @NonNull String customerId) {
        String lineRows = restClient
                .get()
                .uri(customerInvoicesUriTemplate, Map.of("customerId", customerId))
                .retrieve()
                .body(String.class);
        return lineRows == null ? null : FacadeJsonSupport.distinctInvoicesFromLineRows(lineRows);
    }
}
