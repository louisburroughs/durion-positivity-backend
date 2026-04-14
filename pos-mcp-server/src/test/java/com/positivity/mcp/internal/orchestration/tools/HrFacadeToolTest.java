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
 * Unit tests for {@link HrFacadeTool}: verifies RestClient call shapes.
 */
class HrFacadeToolTest {

    private static final String BASE_URL = "http://pos-people/v1/hr";

    private MockRestServiceServer mockServer;
    private HrFacadeTool tool;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        tool = new HrFacadeTool(builder, BASE_URL);
    }

    @Test
    @DisplayName("getEmployee sends GET /{employeeId} and returns body")
    void getEmployee_sendsGetToEmployeeEndpoint() {
        mockServer
                .expect(requestTo(BASE_URL + "/EMP-001"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"id\":\"EMP-001\"}", MediaType.APPLICATION_JSON));

        String result = tool.getEmployee("EMP-001");

        mockServer.verify();
        assertThat(result).isNotEmpty().contains("EMP-001");
    }

    @Test
    @DisplayName("searchEmployees sends GET /search?q={query} and returns body")
    void searchEmployees_sendsGetToSearchEndpoint() {
        mockServer
                .expect(requestTo(BASE_URL + "/search?q=technician"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));

        String result = tool.searchEmployees("technician");

        mockServer.verify();
        assertThat(result).isNotEmpty();
    }

    @Test
    @DisplayName("getEmployeeSchedule sends GET /{employeeId}/schedule and returns body")
    void getEmployeeSchedule_sendsGetToScheduleEndpoint() {
        mockServer
                .expect(requestTo(BASE_URL + "/EMP-001/schedule"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"employeeId\":\"EMP-001\",\"shifts\":[]}", MediaType.APPLICATION_JSON));

        String result = tool.getEmployeeSchedule("EMP-001");

        mockServer.verify();
        assertThat(result).isNotEmpty();
    }
}
