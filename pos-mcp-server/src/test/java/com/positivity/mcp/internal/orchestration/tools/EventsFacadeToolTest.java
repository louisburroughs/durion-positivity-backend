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
 * Unit tests for {@link EventsFacadeTool}: verifies RestClient call shapes.
 */
class EventsFacadeToolTest {

    private static final String BASE_URL = "http://pos-event-receiver/v1/events";

    private MockRestServiceServer mockServer;
    private EventsFacadeTool tool;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        tool = new EventsFacadeTool(builder, BASE_URL);
    }

    @Test
    @DisplayName("getEventTypes sends GET /eventTypes and returns body")
    void getEventTypes_sendsGetToTypesEndpoint() {
        mockServer
                .expect(requestTo(BASE_URL + "/eventTypes"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"types\":[]}", MediaType.APPLICATION_JSON));

        String result = tool.getEventTypes();

        mockServer.verify();
        assertThat(result).isNotEmpty();
    }

    @Test
    @DisplayName("searchEvents sends GET /events/summary?q={query} and returns body")
    void searchEvents_sendsGetToSearchEndpoint() {
        mockServer
                .expect(requestTo(BASE_URL + "/events/summary?q=ORDER"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));

        String result = tool.searchEvents("ORDER");

        mockServer.verify();
        assertThat(result).isNotEmpty();
    }

    @Test
    @DisplayName("getEventHistory sends GET /events/summary?entityId={entityId} and returns body")
    void getEventHistory_sendsGetToHistoryEndpoint() {
        mockServer
                .expect(requestTo(BASE_URL + "/events/summary?entityId=ENT-001"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"entityId\":\"ENT-001\",\"events\":[]}", MediaType.APPLICATION_JSON));

        String result = tool.getEventHistory("ENT-001");

        mockServer.verify();
        assertThat(result).isNotEmpty();
    }
}
