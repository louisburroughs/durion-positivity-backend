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
 * Unit tests for {@link WorkorderFacadeTool}. Expected verbs and URIs derive from
 * {@code facade-contract.yaml} (#1519 WS-0.3), never from literals duplicating the configuration.
 */
class WorkorderFacadeToolTest {

    private static final String BASE_URL = "http://api-gateway";
    private static final String WORKORDER_ID = "01960003-0000-7000-8000-0000000000e0";
    private static final String CUSTOMER_ID = "01960003-0000-7000-8000-000000000050";
    private static final String VEHICLE_ID = "01960003-0000-7000-8000-000000000077";
    private static final String TECHNICIAN_ID = "01960003-0000-7000-8000-000000000099";

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
                contract("getWorkorderStatus").template(),
                contract("getTechnicianLaborAnalytics").template());
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
    @DisplayName("searchWorkorders sends GET /workorders/search?q={query} when no filters are given")
    void searchWorkorders_sendsGetToSearchEndpoint() {
        FacadeContractManifest.Entry entry = contract("searchWorkorders");
        mockServer
                .expect(requestTo(BASE_URL + entry.expand(Map.of("query", "brakes"))))
                .andExpect(method(entry.httpMethod()))
                .andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));

        String result = tool.searchWorkorders("brakes", null, null, null, null, null, null);

        mockServer.verify();
        assertThat(result).isNotEmpty();
    }

    @Test
    @DisplayName("searchWorkorders appends customerId and vehicleId as query params when supplied")
    void searchWorkorders_appendsCustomerAndVehicleFilters() {
        FacadeContractManifest.Entry entry = contract("searchWorkorders");
        mockServer
                .expect(requestTo(BASE_URL
                        + entry.expand(Map.of("query", "smith"))
                        + "&customerId=" + CUSTOMER_ID
                        + "&vehicleId=" + VEHICLE_ID))
                .andExpect(method(entry.httpMethod()))
                .andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));

        String result = tool.searchWorkorders("smith", CUSTOMER_ID, VEHICLE_ID, null, null, null, null);

        mockServer.verify();
        assertThat(result).isNotEmpty();
    }

    @Test
    @DisplayName("searchWorkorders omits blank id filters from the request URI")
    void searchWorkorders_omitsBlankFilters() {
        FacadeContractManifest.Entry entry = contract("searchWorkorders");
        mockServer
                .expect(requestTo(BASE_URL + entry.expand(Map.of("query", "smith")) + "&vehicleId=" + VEHICLE_ID))
                .andExpect(method(entry.httpMethod()))
                .andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));

        String result = tool.searchWorkorders("smith", "  ", VEHICLE_ID, null, null, null, null);

        mockServer.verify();
        assertThat(result).isNotEmpty();
    }

    @Test
    @DisplayName("searchWorkorders appends status, createdFrom, createdTo and technicianId when supplied "
            + "(Q5 gate combo: status + customerId in one server-side call)")
    void searchWorkorders_appendsStructuredFilters() {
        FacadeContractManifest.Entry entry = contract("searchWorkorders");
        mockServer
                .expect(requestTo(BASE_URL
                        + entry.expand(Map.of("query", ""))
                        + "&customerId=" + CUSTOMER_ID
                        + "&status=APPROVED"
                        + "&createdFrom=2026-06-01"
                        + "&createdTo=2026-06-30"
                        + "&technicianId=" + TECHNICIAN_ID))
                .andExpect(method(entry.httpMethod()))
                .andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));

        String result =
                tool.searchWorkorders("", CUSTOMER_ID, null, "APPROVED", "2026-06-01", "2026-06-30", TECHNICIAN_ID);

        mockServer.verify();
        assertThat(result).isNotEmpty();
    }

    @Test
    @DisplayName("searchWorkorders omits a blank status filter from the request URI")
    void searchWorkorders_omitsBlankStatus() {
        FacadeContractManifest.Entry entry = contract("searchWorkorders");
        mockServer
                .expect(requestTo(BASE_URL + entry.expand(Map.of("query", "brakes"))))
                .andExpect(method(entry.httpMethod()))
                .andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));

        String result = tool.searchWorkorders("brakes", null, null, "   ", null, null, null);

        mockServer.verify();
        assertThat(result).isNotEmpty();
    }

    @Test
    @DisplayName("searchWorkorders expands the OPEN alias to the six non-terminal statuses in one call (#1676)")
    void searchWorkorders_expandsOpenAliasToSixStatuses() {
        FacadeContractManifest.Entry entry = contract("searchWorkorders");
        mockServer
                .expect(requestTo(BASE_URL
                        + entry.expand(Map.of("query", ""))
                        + "&customerId=" + CUSTOMER_ID
                        + "&status=APPROVED%2CASSIGNED%2CWORK_IN_PROGRESS%2CAWAITING_PARTS%2CAWAITING_APPROVAL%2C"
                        + "READY_FOR_PICKUP"))
                .andExpect(method(entry.httpMethod()))
                .andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));

        String result = tool.searchWorkorders("", CUSTOMER_ID, null, "OPEN", null, null, null);

        mockServer.verify();
        assertThat(result).isNotEmpty();
    }

    @Test
    @DisplayName("searchWorkorders expands a lowercase \"open\" alias the same as \"OPEN\"")
    void searchWorkorders_expandsLowercaseOpenAlias() {
        FacadeContractManifest.Entry entry = contract("searchWorkorders");
        mockServer
                .expect(requestTo(BASE_URL
                        + entry.expand(Map.of("query", ""))
                        + "&status=APPROVED%2CASSIGNED%2CWORK_IN_PROGRESS%2CAWAITING_PARTS%2CAWAITING_APPROVAL%2C"
                        + "READY_FOR_PICKUP"))
                .andExpect(method(entry.httpMethod()))
                .andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));

        String result = tool.searchWorkorders("", null, null, "open", null, null, null);

        mockServer.verify();
        assertThat(result).isNotEmpty();
    }

    @Test
    @DisplayName("searchWorkorders passes an explicit comma-separated status list through unchanged")
    void searchWorkorders_passesExplicitStatusListThrough() {
        FacadeContractManifest.Entry entry = contract("searchWorkorders");
        mockServer
                .expect(requestTo(BASE_URL + entry.expand(Map.of("query", "")) + "&status=APPROVED%2CWORK_IN_PROGRESS"))
                .andExpect(method(entry.httpMethod()))
                .andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));

        String result = tool.searchWorkorders("", null, null, "APPROVED,WORK_IN_PROGRESS", null, null, null);

        mockServer.verify();
        assertThat(result).isNotEmpty();
    }

    @Test
    @DisplayName("searchWorkorders leaves a single status unchanged (not the OPEN alias)")
    void searchWorkorders_leavesSingleStatusUnchanged() {
        FacadeContractManifest.Entry entry = contract("searchWorkorders");
        mockServer
                .expect(requestTo(BASE_URL + entry.expand(Map.of("query", "")) + "&status=COMPLETED"))
                .andExpect(method(entry.httpMethod()))
                .andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));

        String result = tool.searchWorkorders("", null, null, "COMPLETED", null, null, null);

        mockServer.verify();
        assertThat(result).isNotEmpty();
    }

    @Test
    @DisplayName("searchWorkorders starts the query string with '?' when the configured template has none")
    void searchWorkorders_usesQuestionMarkOnQuerylessTemplate() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        WorkorderFacadeTool pathOnlyTool = new WorkorderFacadeTool(
                builder,
                BASE_URL,
                contract("getWorkorder").template(),
                "/workorder/v1/workorders/search",
                contract("getWorkorderStatus").template(),
                contract("getTechnicianLaborAnalytics").template());
        server.expect(requestTo(BASE_URL + "/workorder/v1/workorders/search?customerId=" + CUSTOMER_ID))
                .andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));

        String result = pathOnlyTool.searchWorkorders("ignored-by-template", CUSTOMER_ID, null, null, null, null, null);

        server.verify();
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
                        "{\"id\":\"" + WORKORDER_ID + "\",\"status\":\"IN_PROGRESS\",\"lineItems\":[{\"sku\":\"X\"}]}",
                        MediaType.APPLICATION_JSON));

        String result = tool.getWorkorderStatus(WORKORDER_ID);

        mockServer.verify();
        assertThat(result)
                .isNotEmpty()
                .contains("IN_PROGRESS")
                .contains(WORKORDER_ID)
                .doesNotContain("lineItems");
    }

    @Test
    @DisplayName("getWorkorderStatus passes an unparseable or status-less body through unchanged")
    void getWorkorderStatus_passesThroughWhenNoStatusField() {
        FacadeContractManifest.Entry entry = contract("getWorkorderStatus");
        mockServer
                .expect(requestTo(BASE_URL + entry.expand(Map.of("workorderId", WORKORDER_ID))))
                .andExpect(method(entry.httpMethod()))
                .andRespond(withSuccess("{\"error\":\"boom\"}", MediaType.APPLICATION_JSON));

        String result = tool.getWorkorderStatus(WORKORDER_ID);

        mockServer.verify();
        assertThat(result).isEqualTo("{\"error\":\"boom\"}");
    }

    @Test
    @DisplayName("getTechnicianLaborAnalytics maps a YYYY-MM period to GET technician-labor?startDate&endDate")
    void getTechnicianLaborAnalytics_mapsCalendarMonthToDateRange() {
        FacadeContractManifest.Entry entry = contract("getTechnicianLaborAnalytics");
        mockServer
                .expect(requestTo(BASE_URL + entry.expand(Map.of("startDate", "2026-06-01", "endDate", "2026-06-30"))))
                .andExpect(method(entry.httpMethod()))
                .andRespond(withSuccess("{\"rows\":[],\"truncated\":false,\"limit\":100}", MediaType.APPLICATION_JSON));

        String result = tool.getTechnicianLaborAnalytics("2026-06");

        mockServer.verify();
        assertThat(result).isNotEmpty().contains("\"truncated\":false");
    }

    @Test
    @DisplayName("getTechnicianLaborAnalytics maps a YYYY period to the full calendar year")
    void getTechnicianLaborAnalytics_mapsCalendarYearToDateRange() {
        FacadeContractManifest.Entry entry = contract("getTechnicianLaborAnalytics");
        mockServer
                .expect(requestTo(BASE_URL + entry.expand(Map.of("startDate", "2026-01-01", "endDate", "2026-12-31"))))
                .andExpect(method(entry.httpMethod()))
                .andRespond(withSuccess("{\"rows\":[]}", MediaType.APPLICATION_JSON));

        String result = tool.getTechnicianLaborAnalytics("2026");

        mockServer.verify();
        assertThat(result).isNotEmpty();
    }

    @Test
    @DisplayName("getTechnicianLaborAnalytics rejects an unsupported period form without issuing a request")
    void getTechnicianLaborAnalytics_rejectsUnsupportedPeriod() {
        assertThatThrownBy(() -> tool.getTechnicianLaborAnalytics("Q2-2026"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("YYYY-MM")
                .hasMessageContaining("YYYY");

        mockServer.verify();
    }
}
