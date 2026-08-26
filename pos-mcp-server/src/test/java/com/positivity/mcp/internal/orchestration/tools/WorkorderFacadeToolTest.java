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
 * Unit tests for {@link WorkorderFacadeTool}. Expected verbs and URIs derive from
 * {@code facade-contract.yaml} (#1519 WS-0.3), never from literals duplicating the configuration.
 */
class WorkorderFacadeToolTest {

    private static final String BASE_URL = "http://api-gateway";
    private static final String WORKORDER_ID = "01960003-0000-7000-8000-0000000000e0";

    private MockRestServiceServer mockServer;
    private WorkorderFacadeTool tool;

    private static FacadeContractManifest.Entry contract(String toolMethod) {
        return FacadeContractManifest.entry("WorkorderFacadeTool." + toolMethod);
    }

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        tool = new WorkorderFacadeTool(
                builder,
                BASE_URL,
                contract("getWorkorder").template(),
                contract("searchWorkorders").template(),
                contract("getWorkorderStatus").template());
    }

    @Test
    @DisplayName("getWorkorder sends GET /workorders/{workorderId} and returns body")
    void getWorkorder_sendsGetToWorkorderEndpoint() {
        FacadeContractManifest.Entry entry = contract("getWorkorder");
        mockServer
                .expect(requestTo(BASE_URL + entry.expand(Map.of("workorderId", WORKORDER_ID))))
                .andExpect(method(entry.httpMethod()))
                .andRespond(withSuccess("{\"id\":\"" + WORKORDER_ID + "\"}", MediaType.APPLICATION_JSON));

        String result = tool.getWorkorder(WORKORDER_ID);

        mockServer.verify();
        assertThat(result).isNotEmpty().contains(WORKORDER_ID);
    }

    @Test
    @DisplayName("searchWorkorders sends GET /workorders/search?q={query} and returns body")
    void searchWorkorders_sendsGetToSearchEndpoint() {
        FacadeContractManifest.Entry entry = contract("searchWorkorders");
        mockServer
                .expect(requestTo(BASE_URL + entry.expand(Map.of("query", "brakes"))))
                .andExpect(method(entry.httpMethod()))
                .andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));

        String result = tool.searchWorkorders("brakes");

        mockServer.verify();
        assertThat(result).isNotEmpty();
    }

    @Test
    @DisplayName("getWorkorderStatus sends GET /workorders/{workorderId} (same endpoint as getWorkorder)")
    void getWorkorderStatus_sendsGetToWorkorderEndpoint() {
        FacadeContractManifest.Entry entry = contract("getWorkorderStatus");
        mockServer
                .expect(requestTo(BASE_URL + entry.expand(Map.of("workorderId", WORKORDER_ID))))
                .andExpect(method(entry.httpMethod()))
                .andRespond(withSuccess(
                        "{\"id\":\"" + WORKORDER_ID + "\",\"status\":\"IN_PROGRESS\"}", MediaType.APPLICATION_JSON));

        String result = tool.getWorkorderStatus(WORKORDER_ID);

        mockServer.verify();
        assertThat(result).isNotEmpty().contains("IN_PROGRESS");
    }
}
