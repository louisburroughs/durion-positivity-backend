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
 * Unit tests for {@link TaxFacadeTool}: verifies RestClient call shapes.
 */
class TaxFacadeToolTest {

    private static final String BASE_URL = "http://pos-tax/v1/tax";

    private MockRestServiceServer mockServer;
    private TaxFacadeTool tool;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        tool = new TaxFacadeTool(
                builder,
                BASE_URL,
                "/rates/{locationId}",
                "/calculate?amount={amount}&locationId={locationId}",
                "/summary/{period}");
    }

    @Test
    @DisplayName("getTaxRate sends GET /rates/{locationId} and returns body")
    void getTaxRate_sendsGetToRatesEndpoint() {
        mockServer
                .expect(requestTo(BASE_URL + "/rates/LOC-001"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"locationId\":\"LOC-001\",\"rate\":0.08}", MediaType.APPLICATION_JSON));

        String result = tool.getTaxRate("LOC-001");

        mockServer.verify();
        assertThat(result).isNotEmpty().contains("LOC-001");
    }

    @Test
    @DisplayName("calculateTax sends GET /calculate?amount={amount}&locationId={locationId} and returns body")
    void calculateTax_sendsGetToCalculateEndpoint() {
        mockServer
                .expect(requestTo(BASE_URL + "/calculate?amount=100.00&locationId=LOC-001"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"tax\":8.00}", MediaType.APPLICATION_JSON));

        String result = tool.calculateTax("100.00", "LOC-001");

        mockServer.verify();
        assertThat(result).isNotEmpty();
    }

    @Test
    @DisplayName("getTaxSummary sends GET /summary/{period} and returns body")
    void getTaxSummary_sendsGetToSummaryEndpoint() {
        mockServer
                .expect(requestTo(BASE_URL + "/summary/2025-Q1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"period\":\"2025-Q1\",\"collected\":4000}", MediaType.APPLICATION_JSON));

        String result = tool.getTaxSummary("2025-Q1");

        mockServer.verify();
        assertThat(result).isNotEmpty();
    }
}
