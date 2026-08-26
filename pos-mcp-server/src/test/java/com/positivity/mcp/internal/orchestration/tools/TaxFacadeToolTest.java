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
 * Unit tests for {@link TaxFacadeTool}. Expected verbs and URIs derive from
 * {@code facade-contract.yaml} (#1519 WS-0.3), never from literals duplicating the configuration.
 * Rate/calculate use the ADR-0021 direct pos-tax client; the summary uses the gateway client.
 */
class TaxFacadeToolTest {

    private static final String DIRECT_BASE_URL = "http://pos-tax/v1/tax";
    private static final String GATEWAY_BASE_URL = "http://api-gateway";

    private MockRestServiceServer directMockServer;
    private MockRestServiceServer gatewayMockServer;
    private TaxFacadeTool tool;

    private static FacadeContractManifest.Entry contract(String toolMethod) {
        return FacadeContractManifest.entry("TaxFacadeTool." + toolMethod);
    }

    @BeforeEach
    void setUp() {
        RestClient.Builder directBuilder = RestClient.builder();
        RestClient.Builder gatewayBuilder = RestClient.builder();
        directMockServer = MockRestServiceServer.bindTo(directBuilder).build();
        gatewayMockServer = MockRestServiceServer.bindTo(gatewayBuilder).build();
        tool = new TaxFacadeTool(
                directBuilder,
                gatewayBuilder,
                DIRECT_BASE_URL,
                GATEWAY_BASE_URL,
                contract("getTaxRate").template(),
                contract("calculateTax").template(),
                contract("getTaxSummary").template());
    }

    @Test
    @DisplayName("getTaxRate sends GET /rates/{locationId} on the direct client and returns body")
    void getTaxRate_sendsGetToRatesEndpoint() {
        FacadeContractManifest.Entry entry = contract("getTaxRate");
        directMockServer
                .expect(requestTo(DIRECT_BASE_URL + entry.expand(Map.of("locationId", "LOC-001"))))
                .andExpect(method(entry.httpMethod()))
                .andRespond(withSuccess("{\"locationId\":\"LOC-001\",\"rate\":0.08}", MediaType.APPLICATION_JSON));

        String result = tool.getTaxRate("LOC-001");

        directMockServer.verify();
        gatewayMockServer.verify();
        assertThat(result).isNotEmpty().contains("LOC-001");
    }

    @Test
    @DisplayName("calculateTax sends GET /calculate?amount&locationId on the direct client")
    void calculateTax_sendsGetToCalculateEndpoint() {
        FacadeContractManifest.Entry entry = contract("calculateTax");
        directMockServer
                .expect(requestTo(DIRECT_BASE_URL + entry.expand(Map.of("amount", "100.00", "locationId", "LOC-001"))))
                .andExpect(method(entry.httpMethod()))
                .andRespond(withSuccess("{\"tax\":8.00}", MediaType.APPLICATION_JSON));

        String result = tool.calculateTax("100.00", "LOC-001");

        directMockServer.verify();
        gatewayMockServer.verify();
        assertThat(result).isNotEmpty();
    }

    @Test
    @DisplayName("getTaxSummary maps the period onto the accounting tax-liability report via the gateway")
    void getTaxSummary_sendsGetToTaxLiabilityReport() {
        FacadeContractManifest.Entry entry = contract("getTaxSummary");
        gatewayMockServer
                .expect(requestTo(
                        GATEWAY_BASE_URL + entry.expand(Map.of("startDate", "2026-03-01", "endDate", "2026-03-31"))))
                .andExpect(method(entry.httpMethod()))
                .andRespond(withSuccess("{\"netTax\":4000}", MediaType.APPLICATION_JSON));

        String result = tool.getTaxSummary("2026-03");

        directMockServer.verify();
        gatewayMockServer.verify();
        assertThat(result).isNotEmpty();
    }

    @Test
    @DisplayName("getTaxSummary rejects an unsupported period form without issuing a request")
    void getTaxSummary_rejectsUnsupportedPeriod() {
        assertThatThrownBy(() -> tool.getTaxSummary("2025-Q1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("YYYY-MM")
                .hasMessageContaining("YYYY");

        directMockServer.verify();
        gatewayMockServer.verify();
    }
}
