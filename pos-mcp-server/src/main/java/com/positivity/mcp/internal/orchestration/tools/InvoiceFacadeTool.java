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
public class InvoiceFacadeTool {

    /**
     * Default for the backing {@code searchInvoiceLines} bound (newest 200 line rows); a response
     * of exactly this many rows means the scan hit the bound. The pos-invoice cap is server-side
     * and not discoverable from the response, so the effective value is configurable
     * ({@code pos.invoice.customer-invoice-line-cap}) and must be kept aligned with the backend
     * — a drifted value inverts the {@code truncated} signal in both directions.
     */
    static final int CUSTOMER_INVOICE_LINE_CAP = 200;

    private final RestClient restClient;
    private final String invoiceUriTemplate;
    private final String invoiceSearchUriTemplate;
    private final String customerInvoicesUriTemplate;
    private final int customerInvoiceLineCap;

    public InvoiceFacadeTool(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("${pos.invoice.base-url}") @NonNull String baseUrl,
            @Value("${pos.invoice.invoice-uri-template}") @NonNull String invoiceUriTemplate,
            @Value("${pos.invoice.search-uri-template}") @NonNull String invoiceSearchUriTemplate,
            @Value("${pos.invoice.customer-invoices-uri-template}") @NonNull String customerInvoicesUriTemplate,
            @Value("${pos.invoice.customer-invoice-line-cap:" + CUSTOMER_INVOICE_LINE_CAP + "}")
                    int customerInvoiceLineCap) {
        this.restClient = ToolRestClientSupport.instrumentedClient(restClientBuilder, baseUrl);
        this.invoiceUriTemplate = invoiceUriTemplate;
        this.invoiceSearchUriTemplate = invoiceSearchUriTemplate;
        this.customerInvoicesUriTemplate = customerInvoicesUriTemplate;
        this.customerInvoiceLineCap = customerInvoiceLineCap;
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
                    + "name, or workorder number, optionally narrowed by an exact status, an issued-date "
                    + "window, and/or an exact customerId, each combinable with the query and with each "
                    + "other. status must be an exact InvoiceStatus value (DRAFT, FINALIZED, POSTED, ERROR, "
                    + "CANCELLED); an unrecognized value is rejected by the backend. issuedFrom/issuedTo "
                    + "(YYYY-MM-DD, inclusive on both ends) bound the invoice's finalizedAt date — the date it "
                    + "was finalized/issued to the customer — so a DRAFT invoice is excluded whenever either "
                    + "bound is set. Returns only the first page of matches (default size 25, newest first).")
    public String searchInvoices(
            @ToolParam(description = "Free-text term matching invoice number, customer name, or workorder number")
                    @NonNull
                    String query,
            @ToolParam(
                            description = "Optional exact invoice status filter (DRAFT, FINALIZED, POSTED, ERROR, "
                                    + "CANCELLED), combinable with the query",
                            required = false)
                    String status,
            @ToolParam(
                            description = "Optional issued-date window start, inclusive (YYYY-MM-DD); evaluated "
                                    + "against the invoice's finalizedAt date",
                            required = false)
                    String issuedFrom,
            @ToolParam(
                            description = "Optional issued-date window end, inclusive (YYYY-MM-DD); evaluated "
                                    + "against the invoice's finalizedAt date",
                            required = false)
                    String issuedTo,
            @ToolParam(
                            description = "Optional exact customer id (UUID) filter, combinable with the query",
                            required = false)
                    String customerId) {
        StringBuilder template = new StringBuilder(invoiceSearchUriTemplate);
        Map<String, String> uriParams = new HashMap<>();
        uriParams.put("query", query);
        appendQueryParam(template, uriParams, "status", status);
        appendQueryParam(template, uriParams, "issuedFrom", issuedFrom);
        appendQueryParam(template, uriParams, "issuedTo", issuedTo);
        appendQueryParam(template, uriParams, "customerId", customerId);
        return restClient.get().uri(template.toString(), uriParams).retrieve().body(String.class);
    }

    private static void appendQueryParam(
            StringBuilder template, Map<String, String> uriParams, String name, String value) {
        if (value != null && !value.isBlank()) {
            // The default template carries ?q={query}, but the property is env-overridable — a
            // path-only override must not get the filter glued onto its last path segment.
            template.append(template.indexOf("?") >= 0 ? '&' : '?')
                    .append(name)
                    .append("={")
                    .append(name)
                    .append('}');
            uriParams.put(name, value);
        }
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
                : FacadeJsonSupport.distinctInvoicesFromLineRows(lineRows, customerInvoiceLineCap);
    }
}
