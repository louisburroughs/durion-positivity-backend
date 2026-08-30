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

    /**
     * The backing {@code searchInvoiceLines} endpoint bounds its answer to the newest 200 line
     * rows; a response of exactly this many rows means the scan hit the bound.
     */
    static final int CUSTOMER_INVOICE_LINE_CAP = 200;

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

    @Tool(
            description = "Search invoices by a free-text term matched against the invoice number, customer "
                    + "name, or workorder number. Free-text match only — this tool cannot filter by status, "
                    + "date, or amount, and returns only the first page of matches (default size 25, newest "
                    + "first).")
    public String searchInvoices(
            @ToolParam(description = "Free-text term matching invoice number, customer name, or workorder number")
                    @NonNull
                    String query) {
        return restClient
                .get()
                .uri(invoiceSearchUriTemplate, Map.of("query", query))
                .retrieve()
                .body(String.class);
    }

    @Tool(
            description = "Get the distinct invoices linked to a customer by party id (UUID). Built from the "
                    + "customer's newest invoice line items — bounded to the newest 200 lines — de-duplicated "
                    + "by invoice; each entry carries invoiceId, invoiceNumber, invoiceStatus, "
                    + "invoiceCreatedAt, and the number of matched lines. The result is an envelope: truncated "
                    + "(true when the 200-line bound was hit, meaning older invoices may be missing — check it "
                    + "before treating counts or totals as complete), coveredFrom/coveredTo (the "
                    + "invoiceCreatedAt span actually scanned), and invoices (the de-duplicated entries).")
    public String getInvoicesByCustomer(
            @ToolParam(description = "The customer's party id (UUID)") @NonNull String customerId) {
        String lineRows = restClient
                .get()
                .uri(customerInvoicesUriTemplate, Map.of("customerId", customerId))
                .retrieve()
                .body(String.class);
        return lineRows == null
                ? null
                : FacadeJsonSupport.distinctInvoicesFromLineRows(lineRows, CUSTOMER_INVOICE_LINE_CAP);
    }
}
