package com.positivity.mcp.internal.orchestration.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Unit tests for {@link OrderFacadeTool}: verifies RestClient call shapes.
 */
class OrderFacadeToolTest {

    private static final String BASE_URL = "http://api-gateway";

    private MockRestServiceServer mockServer;
    private OrderFacadeTool tool;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        tool = new OrderFacadeTool(builder, BASE_URL,
                "/order/v1/orders/{orderId}",
                "/order/v1/orders/search?q={query}");
    }

    @Test
    @DisplayName("getOrder sends GET /{orderId} and returns body")
    void getOrder_sendsGetToOrderEndpoint() {
        mockServer
                .expect(requestTo(BASE_URL + "/order/v1/orders/ORD-001"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"orderId\":\"ORD-001\"}", MediaType.APPLICATION_JSON));

        String result = tool.getOrder("ORD-001");

        mockServer.verify();
        assertThat(result).contains("ORD-001");
    }

    @Test
    @DisplayName("searchOrders sends GET /search?q={query} and returns body")
    void searchOrders_sendsGetToSearchEndpoint() {
        mockServer
                .expect(requestTo(BASE_URL + "/order/v1/orders/search?q=COMPLETED"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"orders\":[]}", MediaType.APPLICATION_JSON));

        String result = tool.searchOrders("COMPLETED");

        mockServer.verify();
        assertThat(result).contains("orders");
    }
}
