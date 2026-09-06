package com.positivity.mcp.internal.orchestration.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * #1785: no facade tool exposed purchase orders, so the assistant could not answer what was on
 * order, from whom, or what was still outstanding — while 402 purchase orders and 402 lines sat in
 * pos_order_db. Gate question q21 was authored against real business need and shipped excluded
 * because half of it had no tool to call.
 */
@DisplayName("OrderFacadeTool — reading purchase orders")
class PurchaseOrderToolTest {

    private static final String BASE = "http://pos-api-gateway";
    private static final String LIST = "/order/v1/orders/purchase-orders";
    private static final String SUMMARY = "/order/v1/orders/purchase-orders/summary";

    private RestClient.Builder builder;
    private MockRestServiceServer server;
    private OrderFacadeTool tool;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        tool = new OrderFacadeTool(
                builder,
                BASE,
                "/order/v1/orders/carts/{orderId}",
                "/order/v1/orders/carts",
                LIST,
                "/order/v1/orders/purchase-orders/{poId}",
                "/order/v1/orders/purchase-orders/summary");
    }

    @Test
    @DisplayName("lists purchase orders with no filter")
    void listsWithoutFilters() {
        server.expect(requestTo(BASE + LIST)).andRespond(withSuccess("{\"content\":[]}", MediaType.APPLICATION_JSON));

        assertThat(tool.listPurchaseOrders(null, null)).contains("content");
        server.verify();
    }

    @Test
    @DisplayName("a status filter reaches the query string")
    void statusFilterIsSent() {
        // Without it the answer is wrong by 90 of 402 rows on alpha: DRAFT was never sent to a
        // vendor and CANCELLED is void, so neither is an order that is open with us.
        server.expect(requestTo(BASE + LIST + "?status=APPROVED"))
                .andRespond(withSuccess("{\"content\":[]}", MediaType.APPLICATION_JSON));

        tool.listPurchaseOrders("APPROVED", null);
        server.verify();
    }

    @Test
    @DisplayName("a vendor filter reaches the query string")
    void vendorFilterIsSent() {
        server.expect(requestTo(BASE + LIST + "?vendorId=d1c3e5a5-dc2c-5f6b-8139-8925c147e3c5"))
                .andRespond(withSuccess("{\"content\":[]}", MediaType.APPLICATION_JSON));

        tool.listPurchaseOrders(null, "d1c3e5a5-dc2c-5f6b-8139-8925c147e3c5");
        server.verify();
    }

    @Test
    @DisplayName("fetches a single purchase order by id")
    void getsOneById() {
        server.expect(requestTo(BASE + "/order/v1/orders/purchase-orders/po-1"))
                .andRespond(withSuccess("{\"poId\":\"po-1\"}", MediaType.APPLICATION_JSON));

        assertThat(tool.getPurchaseOrder("po-1")).contains("po-1");
        server.verify();
    }

    @Test
    @DisplayName("the summary tool reads the whole population, not a page (#1798)")
    void summaryReadsThePopulation() {
        // 144 approved orders on alpha; the list tool showed 20 and the model summed those. The
        // summary endpoint answers with the aggregate the question actually asked for. (On that
        // data nothing approved had been received yet, so ordered and open coincide at 2,351.)
        server.expect(requestTo(BASE + SUMMARY + "?status=APPROVED"))
                .andRespond(withSuccess(
                        "{\"orderCount\":144,\"unitsOrdered\":2351,\"unitsOpen\":2351}", MediaType.APPLICATION_JSON));

        assertThat(tool.getPurchaseOrderSummary("APPROVED", null)).contains("\"unitsOpen\":2351");
        server.verify();
    }

    @Test
    @DisplayName("the summary tool passes both filters and sends none when absent")
    void summaryFilters() {
        server.expect(requestTo(BASE + SUMMARY + "?status=APPROVED&vendorId=d1c3e5a5-dc2c-5f6b-8139-8925c147e3c5"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + SUMMARY)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        tool.getPurchaseOrderSummary("APPROVED", "d1c3e5a5-dc2c-5f6b-8139-8925c147e3c5");
        tool.getPurchaseOrderSummary(null, null);
        server.verify();
    }
}
