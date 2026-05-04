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
 * Unit tests for {@link VehicleFacadeTool}: verifies RestClient call shapes.
 */
class VehicleFacadeToolTest {

    private static final String BASE_URL = "http://api-gateway";

    private MockRestServiceServer mockServer;
    private VehicleFacadeTool tool;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        tool = new VehicleFacadeTool(builder, BASE_URL,
                "/customer/v1/vehicles/{vehicleId}",
                "/customer/v1/vehicles/search?q={query}",
                "/customer/v1/vehicles/customer/{customerId}");
    }

    @Test
    @DisplayName("getVehicle sends GET /{vehicleId} and returns body")
    void getVehicle_sendsGetToVehicleEndpoint() {
        mockServer
                .expect(requestTo(BASE_URL + "/customer/v1/vehicles/VEH-001"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"id\":\"VEH-001\"}", MediaType.APPLICATION_JSON));

        String result = tool.getVehicle("VEH-001");

        mockServer.verify();
        assertThat(result).isNotEmpty().contains("VEH-001");
    }

    @Test
    @DisplayName("searchVehicles sends GET /search?q={query} and returns body")
    void searchVehicles_sendsGetToSearchEndpoint() {
        mockServer
                .expect(requestTo(BASE_URL + "/customer/v1/vehicles/search?q=Honda"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));

        String result = tool.searchVehicles("Honda");

        mockServer.verify();
        assertThat(result).isNotEmpty();
    }

    @Test
    @DisplayName("getVehiclesByCustomer sends GET /customer/{customerId} and returns body")
    void getVehiclesByCustomer_sendsGetToCustomerVehiclesEndpoint() {
        mockServer
                .expect(requestTo(BASE_URL + "/customer/v1/vehicles/customer/CUST-001"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"vehicles\":[]}", MediaType.APPLICATION_JSON));

        String result = tool.getVehiclesByCustomer("CUST-001");

        mockServer.verify();
        assertThat(result).isNotEmpty();
    }
}
