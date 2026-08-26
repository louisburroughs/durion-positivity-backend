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
 * Unit tests for {@link VehicleFacadeTool}. Expected verbs and URIs derive from
 * {@code facade-contract.yaml} (#1519 WS-0.3), never from literals duplicating the configuration.
 */
class VehicleFacadeToolTest {

    private static final String BASE_URL = "http://api-gateway";
    private static final String VEHICLE_ID = "01960003-0000-7000-8000-0000000000d0";
    private static final String PARTY_ID = "01960003-0000-7000-8000-0000000000d1";

    private MockRestServiceServer mockServer;
    private VehicleFacadeTool tool;

    private static FacadeContractManifest.Entry contract(String toolMethod) {
        return FacadeContractManifest.entry("VehicleFacadeTool." + toolMethod);
    }

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        tool = new VehicleFacadeTool(
                builder,
                BASE_URL,
                contract("getVehicle").template(),
                contract("searchVehicles").template(),
                contract("getVehiclesByCustomer").template());
    }

    @Test
    @DisplayName("getVehicle sends GET /vehicle-registry/{vehicleId} and returns body")
    void getVehicle_sendsGetToRegistryEndpoint() {
        FacadeContractManifest.Entry entry = contract("getVehicle");
        mockServer
                .expect(requestTo(BASE_URL + entry.expand(Map.of("vehicleId", VEHICLE_ID))))
                .andExpect(method(entry.httpMethod()))
                .andRespond(
                        withSuccess("{\"id\":\"" + VEHICLE_ID + "\",\"isActive\":true}", MediaType.APPLICATION_JSON));

        String result = tool.getVehicle(VEHICLE_ID);

        mockServer.verify();
        assertThat(result).isNotEmpty().contains(VEHICLE_ID);
    }

    @Test
    @DisplayName("searchVehicles sends GET /vehicles/search?q={query} and returns body")
    void searchVehicles_sendsGetToSearchEndpoint() {
        FacadeContractManifest.Entry entry = contract("searchVehicles");
        mockServer
                .expect(requestTo(BASE_URL + entry.expand(Map.of("query", "civic"))))
                .andExpect(method(entry.httpMethod()))
                .andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));

        String result = tool.searchVehicles("civic");

        mockServer.verify();
        assertThat(result).isNotEmpty();
    }

    @Test
    @DisplayName("getVehiclesByCustomer sends GET /crm/{customerId}/vehicles and returns body")
    void getVehiclesByCustomer_sendsGetToCrmVehicles() {
        FacadeContractManifest.Entry entry = contract("getVehiclesByCustomer");
        mockServer
                .expect(requestTo(BASE_URL + entry.expand(Map.of("customerId", PARTY_ID))))
                .andExpect(method(entry.httpMethod()))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        String result = tool.getVehiclesByCustomer(PARTY_ID);

        mockServer.verify();
        assertThat(result).isNotNull();
    }
}
