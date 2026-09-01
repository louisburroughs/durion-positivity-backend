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
    private final String revenueByCustomerUriTemplate;
    private final int customerInvoiceLineCap;

    public InvoiceFacadeTool(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("${pos.invoice.base-url}") @NonNull String baseUrl,
            @Value("${pos.invoice.invoice-uri-template}") @NonNull String invoiceUriTemplate,
            @Value("${pos.invoice.search-uri-template}") @NonNull String invoiceSearchUriTemplate,
            @Value("${pos.invoice.customer-invoices-uri-template}") @NonNull String customerInvoicesUriTemplate,
            @Value("${pos.invoice.revenue-by-customer-uri-template}") @NonNull String revenueByCustomerUriTemplate,
            @Value("${pos.invoice.customer-invoice-line-cap:" + CUSTOMER_INVOICE_LINE_CAP + "}")
                    int customerInvoiceLineCap) {
        this.restClient = ToolRestClientSupport.instrumentedClient(restClientBuilder, baseUrl);
        this.invoiceUriTemplate = invoiceUriTemplate;
        this.invoiceSearchUriTemplate = invoiceSearchUriTemplate;
        this.customerInvoicesUriTemplate = customerInvoicesUriTemplate;
        this.revenueByCustomerUriTemplate = revenueByCustomerUriTemplate;
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

    @Tool(
            description = "Get per-customer revenue for a reporting period (Wave 2 E1). period must be a "
                    + "calendar month in YYYY-MM form (e.g. 2026-05) or a calendar year in YYYY form (e.g. "
                    + "2026) — resolve a relative phrase like \"last month\" or \"last quarter\" to the "
                    + "concrete YYYY-MM/YYYY yourself before calling; a quarter or a run of months is not a "
                    + "single period here, so loop this call once per month (or once for the enclosing year) "
                    + "rather than guess at a wider window. Returns rows — customerId, name (null until the "
                    + "customer-party replica has caught up), revenue, invoiceCount, avgInvoiceValue "
                    + "(revenue / invoiceCount, server-computed), lastInvoiceDate — ordered by revenue "
                    + "descending and capped at the top 20 customers; the response's truncated flag is true "
                    + "when more customers had revenue in the window than that cap allowed. Only "
                    + "revenue-recognized invoices (status FINALIZED or POSTED) count — a DRAFT invoice has "
                    + "not been billed yet and a CANCELLED/ERROR one never will be, so neither contributes. "
                    + "This tool does NOT accept a customerId filter, a limit override, or a groupBy — it "
                    + "always ranks every customer in the business for one window; for a single customer's "
                    + "invoices use getInvoicesByCustomer instead, and do not loop this tool across more than "
                    + "a handful of periods for a multi-period trend.")
    public String getRevenueByCustomer(
            @ToolParam(description = "Reporting period: YYYY-MM or YYYY") @NonNull String period) {
        ReportingPeriods.DateRange range = ReportingPeriods.toDateRange(period);
        return restClient
                .get()
                .uri(revenueByCustomerUriTemplate, Map.of("startDate", range.startDate(), "endDate", range.endDate()))
                .retrieve()
                .body(String.class);
    }
}
