package com.positivity.mcp.internal.orchestration.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Unit tests for {@link OrderFacadeTool}. Expected verbs and URIs derive from
 * {@code facade-contract.yaml} (#1519 WS-0.3), never from literals duplicating the configuration.
 */
class OrderFacadeToolTest {

    private static final String BASE_URL = "http://api-gateway";
    private static final String ORDER_ID = "01960003-0000-7000-8000-000000000070";

    private MockRestServiceServer mockServer;
    private OrderFacadeTool tool;

    private static FacadeContractManifest.Entry contract(String toolMethod) {
        return FacadeContractManifest.entry("OrderFacadeTool." + toolMethod);
    }

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        tool = new OrderFacadeTool(
                builder,
                BASE_URL,
                contract("getOrder").template(),
                contract("listOrders").template(),
                "/order/v1/orders/purchase-orders",
                "/order/v1/orders/purchase-orders/{poId}",
                "/order/v1/orders/purchase-orders/summary");
    }

    @Test
    @DisplayName("getOrder sends GET /orders/carts/{orderId} and returns body")
    void getOrder_sendsGetToCartEndpoint() {
        FacadeContractManifest.Entry entry = contract("getOrder");
        mockServer
                .expect(requestTo(BASE_URL + entry.expand(Map.of("orderId", ORDER_ID))))
                .andExpect(method(entry.httpMethod()))
                .andRespond(withSuccess("{\"orderId\":\"" + ORDER_ID + "\"}", MediaType.APPLICATION_JSON));

        String result = tool.getOrder(ORDER_ID);

        mockServer.verify();
        assertThat(result).isNotEmpty().contains(ORDER_ID);
    }

    @Test
    @DisplayName("listOrders without filters sends GET /orders/carts with no query params")
    void listOrders_withoutFilters_sendsBareList() {
        FacadeContractManifest.Entry entry = contract("listOrders");
        mockServer
                .expect(requestTo(BASE_URL + entry.expand(Map.of())))
                .andExpect(method(entry.httpMethod()))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        String result = tool.listOrders(null, null, null);

        mockServer.verify();
        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("listOrders with status appends only the status param")
    void listOrders_withStatus_appendsStatus() {
        FacadeContractManifest.Entry entry = contract("listOrders");
        mockServer
                .expect(requestTo(BASE_URL + entry.expand(Map.of()) + "?status=DRAFT"))
                .andExpect(method(entry.httpMethod()))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        String result = tool.listOrders("DRAFT", null, null);

        mockServer.verify();
        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("listOrders with clerkId appends only the clerkId param")
    void listOrders_withClerk_appendsClerkId() {
        FacadeContractManifest.Entry entry = contract("listOrders");
        mockServer
                .expect(requestTo(BASE_URL + entry.expand(Map.of()) + "?clerkId=clerk-9"))
                .andExpect(method(entry.httpMethod()))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        String result = tool.listOrders(null, "clerk-9", null);

        mockServer.verify();
        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("listOrders with terminalId appends only the terminalId param")
    void listOrders_withTerminal_appendsTerminalId() {
        FacadeContractManifest.Entry entry = contract("listOrders");
        mockServer
                .expect(requestTo(BASE_URL + entry.expand(Map.of()) + "?terminalId=term-3"))
                .andExpect(method(entry.httpMethod()))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        String result = tool.listOrders(null, null, "term-3");

        mockServer.verify();
        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("listOrders with all filters appends status, clerkId, and terminalId")
    void listOrders_withAllFilters_appendsAllParams() {
        FacadeContractManifest.Entry entry = contract("listOrders");
        mockServer
                .expect(requestTo(
                        BASE_URL + entry.expand(Map.of()) + "?status=DRAFT&clerkId=clerk-9&terminalId=term-3"))
                .andExpect(method(entry.httpMethod()))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        String result = tool.listOrders("DRAFT", "clerk-9", "term-3");

        mockServer.verify();
        assertThat(result).isNotNull();
    }
}
