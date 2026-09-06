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
    private final String purchaseOrderListUriTemplate;
    private final String purchaseOrderUriTemplate;
    private final String purchaseOrderSummaryUriTemplate;

    public OrderFacadeTool(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("${pos.order.base-url}") @NonNull String baseUrl,
            @Value("${pos.order.order-uri-template}") @NonNull String orderUriTemplate,
            @Value("${pos.order.list-uri-template}") @NonNull String orderListUriTemplate,
            @Value("${pos.order.purchase-order-list-uri-template}") @NonNull String purchaseOrderListUriTemplate,
            @Value("${pos.order.purchase-order-uri-template}") @NonNull String purchaseOrderUriTemplate,
            @Value("${pos.order.purchase-order-summary-uri-template}") @NonNull
                    String purchaseOrderSummaryUriTemplate) {
        this.restClient = ToolRestClientSupport.instrumentedClient(restClientBuilder, baseUrl);
        this.orderUriTemplate = orderUriTemplate;
        this.orderListUriTemplate = orderListUriTemplate;
        this.purchaseOrderListUriTemplate = purchaseOrderListUriTemplate;
        this.purchaseOrderUriTemplate = purchaseOrderUriTemplate;
        this.purchaseOrderSummaryUriTemplate = purchaseOrderSummaryUriTemplate;
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
                    + "date-range, or free-text order search. Returns only the first page of results (default "
                    + "size 20).")
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

    @Tool(
            description = "List PURCHASE orders — what we have on order from vendors — optionally filtered by "
                    + "status and vendorId. These are orders we placed with suppliers, NOT sales orders; use "
                    + "listOrders for customer carts. Status is one of DRAFT, APPROVED, PARTIALLY_RECEIVED, "
                    + "FULLY_RECEIVED, CLOSED, CANCELLED: APPROVED and PARTIALLY_RECEIVED are still open with "
                    + "the vendor, DRAFT was never sent, FULLY_RECEIVED and CLOSED are done and CANCELLED is "
                    + "void — so filter by status=APPROVED when the question is about what is outstanding "
                    + "(add PARTIALLY_RECEIVED to include orders that have started arriving). Each "
                    + "line carries the quantity ordered and the quantity still open, which are different "
                    + "numbers. Returns the FIRST PAGE ONLY (20 orders), so never total, count or sum across "
                    + "its rows — for any aggregate (how many units are on order, how many orders are open, "
                    + "how much is outstanding) call getPurchaseOrderSummary instead.")
    public String listPurchaseOrders(
            @ToolParam(
                            description = "Optional status: DRAFT, APPROVED, PARTIALLY_RECEIVED, FULLY_RECEIVED, "
                                    + "CLOSED or CANCELLED. Pass APPROVED for orders still open with the vendor.",
                            required = false)
                    String status,
            @ToolParam(description = "Optional vendor id (UUID)", required = false) String vendorId) {
        StringBuilder template = new StringBuilder(purchaseOrderListUriTemplate);
        Map<String, String> uriParams = new HashMap<>();
        appendQueryParam(template, uriParams, "status", status);
        appendQueryParam(template, uriParams, "vendorId", vendorId);
        return restClient.get().uri(template.toString(), uriParams).retrieve().body(String.class);
    }

    @Tool(
            description = "Totals across ALL purchase orders matching the filter — the whole population, not a "
                    + "page. Returns orderCount, lineCount, unitsOrdered, unitsOpen (ordered but NOT yet "
                    + "received), unitsReceived, grandTotalMinor and openBalanceMinor, plus the same figures per "
                    + "status in byStatus. Use this for every aggregate question about purchase orders: how "
                    + "many units are still on order, how much is outstanding with a vendor, how many orders "
                    + "are open. 'Still on order' / 'not yet received' is unitsOpen, NOT unitsOrdered — both are "
                    + "plausible and only one answers the question. With no status the population is what is "
                    + "currently open with vendors (APPROVED and PARTIALLY_RECEIVED), which is what outstanding "
                    + "questions want; name statuses only to look at other orders, e.g. status=CANCELLED — "
                    + "their open figures are NOT outstanding supply.")
    public String getPurchaseOrderSummary(
            @ToolParam(
                            description =
                                    "Optional, comma-separated statuses from DRAFT, APPROVED, PARTIALLY_RECEIVED, "
                                            + "FULLY_RECEIVED, CLOSED, CANCELLED. Empty means the open set, APPROVED and "
                                            + "PARTIALLY_RECEIVED.",
                            required = false)
                    String status,
            @ToolParam(description = "Optional vendor id (UUID)", required = false) String vendorId) {
        StringBuilder template = new StringBuilder(purchaseOrderSummaryUriTemplate);
        Map<String, String> uriParams = new HashMap<>();
        appendQueryParam(template, uriParams, "status", status);
        appendQueryParam(template, uriParams, "vendorId", vendorId);
        return restClient.get().uri(template.toString(), uriParams).retrieve().body(String.class);
    }

    @Tool(
            description = "Fetch one purchase order by id, including its lines with ordered and still-open "
                    + "quantities. This is an order we placed with a vendor, not a customer sales order.")
    public String getPurchaseOrder(@ToolParam(description = "The purchase order id (UUID)") @NonNull String poId) {
        return restClient
                .get()
                .uri(purchaseOrderUriTemplate, Map.of("poId", poId))
                .retrieve()
                .body(String.class);
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
