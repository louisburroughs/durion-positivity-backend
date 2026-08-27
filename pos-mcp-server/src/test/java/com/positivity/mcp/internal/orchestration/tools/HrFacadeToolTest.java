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
 * Unit tests for {@link HrFacadeTool}. Expected verbs and URIs derive from
 * {@code facade-contract.yaml} (#1519 WS-0.3), never from literals duplicating the configuration.
 */
class HrFacadeToolTest {

    private static final String BASE_URL = "http://api-gateway";
    private static final String EMPLOYEE_ID = "01960003-0000-7000-8000-000000000030";

    private MockRestServiceServer mockServer;
    private HrFacadeTool tool;

    private static FacadeContractManifest.Entry contract(String toolMethod) {
        return FacadeContractManifest.entry("HrFacadeTool." + toolMethod);
    }

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        tool = new HrFacadeTool(
                builder,
                BASE_URL,
                BASE_URL,
                contract("getEmployee").template(),
                contract("getEmployeeSchedule").template(),
                contract("searchEmployees").template());
    }

    @Test
    @DisplayName("getEmployee sends GET /people/employees/{employeeId} and returns body")
    void getEmployee_sendsGetToEmployeeEndpoint() {
        FacadeContractManifest.Entry entry = contract("getEmployee");
        mockServer
                .expect(requestTo(BASE_URL + entry.expand(Map.of("employeeId", EMPLOYEE_ID))))
                .andExpect(method(entry.httpMethod()))
                .andRespond(withSuccess("{\"employeeId\":\"" + EMPLOYEE_ID + "\"}", MediaType.APPLICATION_JSON));

        String result = tool.getEmployee(EMPLOYEE_ID);

        mockServer.verify();
        assertThat(result).isNotEmpty().contains(EMPLOYEE_ID);
    }

    @Test
    @DisplayName("getEmployeeSchedule sends GET /people/availability?employeeId={employeeId} and returns body")
    void getEmployeeSchedule_sendsGetToAvailabilityEndpoint() {
        FacadeContractManifest.Entry entry = contract("getEmployeeSchedule");
        mockServer
                .expect(requestTo(BASE_URL + entry.expand(Map.of("employeeId", EMPLOYEE_ID))))
                .andExpect(method(entry.httpMethod()))
                .andRespond(withSuccess("{\"slots\":[]}", MediaType.APPLICATION_JSON));

        String result = tool.getEmployeeSchedule(EMPLOYEE_ID);

        mockServer.verify();
        assertThat(result).isNotEmpty();
    }

    @Test
    @DisplayName("searchEmployees sends GET /people/employees?q={query} and returns body")
    void searchEmployees_sendsGetToEmployeeSearchEndpoint() {
        FacadeContractManifest.Entry entry = contract("searchEmployees");
        mockServer
                .expect(requestTo(BASE_URL + entry.expand(Map.of("query", "jane"))))
                .andExpect(method(entry.httpMethod()))
                .andRespond(withSuccess("{\"items\":[{\"lastName\":\"Smith\"}]}", MediaType.APPLICATION_JSON));

        String result = tool.searchEmployees("jane");

        mockServer.verify();
        assertThat(result).isNotEmpty().contains("Smith");
    }

    @Test
    @DisplayName("searchEmployees treats a null query as blank (lists all employees)")
    void searchEmployees_nullQuery_sendsBlankQueryParam() {
        FacadeContractManifest.Entry entry = contract("searchEmployees");
        mockServer
                .expect(requestTo(BASE_URL + entry.expand(Map.of("query", ""))))
                .andExpect(method(entry.httpMethod()))
                .andRespond(withSuccess("{\"items\":[]}", MediaType.APPLICATION_JSON));

        String result = tool.searchEmployees(null);

        mockServer.verify();
        assertThat(result).isNotEmpty();
    }
}
