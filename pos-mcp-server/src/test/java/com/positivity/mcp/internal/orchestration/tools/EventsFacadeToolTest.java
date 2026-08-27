package com.positivity.mcp.internal.orchestration.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Unit tests for {@link EventsFacadeTool}. Expected verbs and URIs derive from
 * {@code facade-contract.yaml} (#1519 WS-0.3), never from literals duplicating the configuration.
 */
class EventsFacadeToolTest {

    private static final String BASE_URL = "http://api-gateway";
    private static final String ENTITY_ID = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b";

    private MockRestServiceServer mockServer;
    private EventsFacadeTool tool;

    private static FacadeContractManifest.Entry contract(String toolMethod) {
        return FacadeContractManifest.entry("EventsFacadeTool." + toolMethod);
    }

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        tool = new EventsFacadeTool(
                builder,
                BASE_URL,
                contract("getEventTypes").template(),
                contract("getEventSummary").template(),
                contract("getEventHistory").template());
    }

    @Test
    @DisplayName("getEventTypes sends GET /eventTypes/active and returns body")
    void getEventTypes_sendsGetToActiveEventTypes() {
        FacadeContractManifest.Entry entry = contract("getEventTypes");
        mockServer
                .expect(requestTo(BASE_URL + entry.expand(Map.of())))
                .andExpect(method(entry.httpMethod()))
                .andRespond(withSuccess("[{\"id\":\"ORDER_CART_CREATE\"}]", MediaType.APPLICATION_JSON));

        String result = tool.getEventTypes();

        mockServer.verify();
        assertThat(result).isNotEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"lastHour", "lastDay", "lastWeek"})
    @DisplayName("getEventSummary sends GET /events/summary/{window} for each supported window")
    void getEventSummary_sendsGetToSummaryWindow(String window) {
        FacadeContractManifest.Entry entry = contract("getEventSummary");
        mockServer
                .expect(requestTo(BASE_URL + entry.expand(Map.of("window", window))))
                .andExpect(method(entry.httpMethod()))
                .andRespond(withSuccess("{\"window\":\"" + window + "\"}", MediaType.APPLICATION_JSON));

        String result = tool.getEventSummary(window);

        mockServer.verify();
        assertThat(result).isNotEmpty().contains(window);
    }

    @Test
    @DisplayName("getEventSummary rejects an unsupported window without issuing a request")
    void getEventSummary_rejectsUnsupportedWindow() {
        assertThatThrownBy(() -> tool.getEventSummary("lastMonth"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lastHour")
                .hasMessageContaining("lastDay")
                .hasMessageContaining("lastWeek");

        mockServer.verify();
    }

    @Test
    @DisplayName("getEventHistory sends GET /events?entityId={entityId} and returns body")
    void getEventHistory_sendsGetToEventsByEntityId() {
        FacadeContractManifest.Entry entry = contract("getEventHistory");
        mockServer
                .expect(requestTo(BASE_URL + entry.expand(Map.of("entityId", ENTITY_ID))))
                .andExpect(method(entry.httpMethod()))
                .andRespond(
                        withSuccess("{\"items\":[{\"entityId\":\"" + ENTITY_ID + "\"}]}", MediaType.APPLICATION_JSON));

        String result = tool.getEventHistory(ENTITY_ID);

        mockServer.verify();
        assertThat(result).isNotEmpty().contains(ENTITY_ID);
    }
}
