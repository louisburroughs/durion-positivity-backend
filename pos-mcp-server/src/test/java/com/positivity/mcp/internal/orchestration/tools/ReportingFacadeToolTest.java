package com.positivity.mcp.internal.orchestration.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
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
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MockRestServiceServer mockServer;
    private ReportingFacadeTool tool;

    private static FacadeContractManifest.Entry contract(String toolMethod) {
        return FacadeContractManifest.entry("ReportingFacadeTool." + toolMethod);
    }

    private static JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Tool result is not valid JSON: " + json, exception);
        }
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
                contract("getRevenueReport").leg("agedReceivables").template());
    }

    @Test
    @DisplayName("getSalesReport rejects a missing range and names the resolver tools to call")
    void getSalesReport_rejectsMissingRange() {
        assertThatThrownBy(() -> tool.getSalesReport(null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("startDate and endDate are both required")
                .hasMessageContaining("resolveDateWindow")
                .hasMessageContaining("resolveNamedPeriod");

        mockServer.verify();
    }

    @Test
    @DisplayName("getSalesReport takes a startDate/endDate window spanning more than one calendar month "
            + "in a single call")
    void getSalesReport_takesExplicitDateRangeSpanningMultipleMonths() {
        FacadeContractManifest.Entry entry = contract("getSalesReport");
        mockServer
                .expect(requestTo(BASE_URL + entry.expand(Map.of("startDate", "2026-03-01", "endDate", "2026-08-31"))))
                .andExpect(method(entry.httpMethod()))
                .andRespond(withSuccess("{\"revenue\":[]}", MediaType.APPLICATION_JSON));

        String result = tool.getSalesReport("2026-03-01", "2026-08-31");

        mockServer.verify();
        assertThat(result).isNotEmpty();
    }

    @Test
    @DisplayName("getSalesReport rejects an unpaired startDate without issuing a request")
    void getSalesReport_rejectsUnpairedStartDate() {
        assertThatThrownBy(() -> tool.getSalesReport("2026-03-01", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("startDate")
                .hasMessageContaining("endDate");

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
    @DisplayName("getRevenueReport composes the income statement and aged receivables for the period")
    void getRevenueReport_composesIncomeStatementAndAgedReceivables() {
        FacadeContractManifest.Entry revenue = contract("getRevenueReport");
        FacadeContractManifest.Entry incomeStatement = revenue.leg("incomeStatement");
        FacadeContractManifest.Entry agedReceivables = revenue.leg("agedReceivables");
        mockServer
                .expect(requestTo(
                        BASE_URL + incomeStatement.expand(Map.of("startDate", "2026-05-01", "endDate", "2026-05-31"))))
                .andExpect(method(incomeStatement.httpMethod()))
                .andRespond(withSuccess("{\"revenue\":[{\"line\":\"Sales\"}]}", MediaType.APPLICATION_JSON));
        mockServer
                .expect(requestTo(BASE_URL + agedReceivables.expand(Map.of("asOfDate", "2026-05-31"))))
                .andExpect(method(agedReceivables.httpMethod()))
                .andRespond(withSuccess("{\"buckets\":[]}", MediaType.APPLICATION_JSON));

        JsonNode envelope = parse(tool.getRevenueReport("2026-05-01", "2026-05-31"));

        mockServer.verify();
        assertThat(envelope.get("composition").asText()).isEqualTo("revenueReport");
        assertThat(envelope.get("status").asText()).isEqualTo("ok");
        assertThat(envelope.get("sections")
                        .get("incomeStatement")
                        .get("data")
                        .get("revenue")
                        .get(0)
                        .get("line")
                        .asText())
                .isEqualTo("Sales");
        assertThat(envelope.get("sections").get("agedReceivables").get("status").asText())
                .isEqualTo("ok");
        assertThat(envelope.get("sources"))
                .extracting(JsonNode::asText)
                .containsExactly("incomeStatement", "agedReceivables");
    }

    @Test
    @DisplayName("getRevenueReport renders a 403 aged-receivables leg as not_authorized and stays ok")
    void getRevenueReport_forbiddenAgedReceivables_rendersNotAuthorized() {
        FacadeContractManifest.Entry revenue = contract("getRevenueReport");
        mockServer
                .expect(requestTo(BASE_URL
                        + revenue.leg("incomeStatement")
                                .expand(Map.of("startDate", "2026-01-01", "endDate", "2026-12-31"))))
                .andRespond(withSuccess("{\"revenue\":[]}", MediaType.APPLICATION_JSON));
        mockServer
                .expect(requestTo(BASE_URL + revenue.leg("agedReceivables").expand(Map.of("asOfDate", "2026-12-31"))))
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"secret\":\"FORBIDDEN-PAYLOAD\"}"));

        String rendered = tool.getRevenueReport("2026-01-01", "2026-12-31");

        mockServer.verify();
        assertThat(rendered).doesNotContain("FORBIDDEN-PAYLOAD");
        JsonNode envelope = parse(rendered);
        assertThat(envelope.get("status").asText()).isEqualTo("ok");
        assertThat(envelope.get("sections").get("agedReceivables").get("status").asText())
                .isEqualTo("not_authorized");
        assertThat(envelope.get("sources")).extracting(JsonNode::asText).containsExactly("incomeStatement");
    }

    @Test
    @DisplayName("getRevenueReport rejects a missing range and names the resolver tools to call")
    void getRevenueReport_rejectsMissingRange() {
        assertThatThrownBy(() -> tool.getRevenueReport(null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("startDate and endDate are both required")
                .hasMessageContaining("resolveDateWindow")
                .hasMessageContaining("resolveNamedPeriod");

        mockServer.verify();
    }

    @Test
    @DisplayName("getRevenueReport takes a startDate/endDate window spanning more than one calendar month "
            + "in a single call, sending every dated leg the range")
    void getRevenueReport_takesExplicitDateRangeSpanningMultipleMonths() {
        FacadeContractManifest.Entry revenue = contract("getRevenueReport");
        FacadeContractManifest.Entry incomeStatement = revenue.leg("incomeStatement");
        FacadeContractManifest.Entry agedReceivables = revenue.leg("agedReceivables");
        mockServer
                .expect(requestTo(
                        BASE_URL + incomeStatement.expand(Map.of("startDate", "2026-03-01", "endDate", "2026-08-31"))))
                .andExpect(method(incomeStatement.httpMethod()))
                .andRespond(withSuccess("{\"revenue\":[{\"line\":\"Sales\"}]}", MediaType.APPLICATION_JSON));
        mockServer
                .expect(requestTo(BASE_URL + agedReceivables.expand(Map.of("asOfDate", "2026-08-31"))))
                .andExpect(method(agedReceivables.httpMethod()))
                .andRespond(withSuccess("{\"buckets\":[]}", MediaType.APPLICATION_JSON));

        JsonNode envelope = parse(tool.getRevenueReport("2026-03-01", "2026-08-31"));

        mockServer.verify();
        assertThat(envelope.get("status").asText()).isEqualTo("ok");
        assertThat(envelope.get("sources"))
                .extracting(JsonNode::asText)
                .containsExactly("incomeStatement", "agedReceivables");
    }

    @Test
    @DisplayName("getRevenueReport rejects an unpaired startDate without issuing a request")
    void getRevenueReport_rejectsUnpairedStartDate() {
        assertThatThrownBy(() -> tool.getRevenueReport("2026-03-01", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("startDate")
                .hasMessageContaining("endDate");

        mockServer.verify();
    }
}
