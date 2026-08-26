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
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Unit tests for {@link ReportingFacadeTool}. Expected verbs and URIs derive from
 * {@code facade-contract.yaml} (#1519 WS-0.3), never from literals duplicating the configuration.
 */
class ReportingFacadeToolTest {

    private static final String BASE_URL = "http://api-gateway";
    private static final String LOCATION_ID = "01960003-0000-7000-8000-000000000090";

    private MockRestServiceServer mockServer;
    private ReportingFacadeTool tool;

    private static FacadeContractManifest.Entry contract(String toolMethod) {
        return FacadeContractManifest.entry("ReportingFacadeTool." + toolMethod);
    }

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        tool = new ReportingFacadeTool(
                builder,
                BASE_URL,
                contract("getSalesReport").template(),
                contract("getInventoryReport").template(),
                contract("getRevenueReport").template());
    }

    @Test
    @DisplayName("getSalesReport maps a YYYY-MM period onto the month's income-statement date range")
    void getSalesReport_mapsMonthPeriodToDateRange() {
        FacadeContractManifest.Entry entry = contract("getSalesReport");
        mockServer
                .expect(requestTo(BASE_URL + entry.expand(Map.of("startDate", "2026-02-01", "endDate", "2026-02-28"))))
                .andExpect(method(entry.httpMethod()))
                .andRespond(withSuccess("{\"revenue\":[]}", MediaType.APPLICATION_JSON));

        String result = tool.getSalesReport("2026-02");

        mockServer.verify();
        assertThat(result).isNotEmpty();
    }

    @Test
    @DisplayName("getSalesReport maps a YYYY period onto the full calendar year")
    void getSalesReport_mapsYearPeriodToDateRange() {
        FacadeContractManifest.Entry entry = contract("getSalesReport");
        mockServer
                .expect(requestTo(BASE_URL + entry.expand(Map.of("startDate", "2026-01-01", "endDate", "2026-12-31"))))
                .andExpect(method(entry.httpMethod()))
                .andRespond(withSuccess("{\"revenue\":[]}", MediaType.APPLICATION_JSON));

        String result = tool.getSalesReport("2026");

        mockServer.verify();
        assertThat(result).isNotEmpty();
    }

    @Test
    @DisplayName("getSalesReport rejects an unsupported period form without issuing a request")
    void getSalesReport_rejectsUnsupportedPeriod() {
        assertThatThrownBy(() -> tool.getSalesReport("Q1-2026"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("YYYY-MM")
                .hasMessageContaining("YYYY");

        mockServer.verify();
    }

    @Test
    @DisplayName("getInventoryReport sends GET /locations/{locationId}/inventory-rollup and returns body")
    void getInventoryReport_sendsGetToInventoryRollup() {
        FacadeContractManifest.Entry entry = contract("getInventoryReport");
        mockServer
                .expect(requestTo(BASE_URL + entry.expand(Map.of("locationId", LOCATION_ID))))
                .andExpect(method(entry.httpMethod()))
                .andRespond(withSuccess("{\"rollup\":[]}", MediaType.APPLICATION_JSON));

        String result = tool.getInventoryReport(LOCATION_ID);

        mockServer.verify();
        assertThat(result).isNotEmpty();
    }

    @Test
    @DisplayName("getRevenueReport sends GET /reporting/revenue/{period} and returns body")
    void getRevenueReport_sendsGetToRevenueEndpoint() {
        FacadeContractManifest.Entry entry = contract("getRevenueReport");
        mockServer
                .expect(requestTo(BASE_URL + entry.expand(Map.of("period", "2025-Q1"))))
                .andExpect(method(entry.httpMethod()))
                .andRespond(withSuccess("{\"period\":\"2025-Q1\"}", MediaType.APPLICATION_JSON));

        String result = tool.getRevenueReport("2025-Q1");

        mockServer.verify();
        assertThat(result).isNotEmpty();
    }
}
