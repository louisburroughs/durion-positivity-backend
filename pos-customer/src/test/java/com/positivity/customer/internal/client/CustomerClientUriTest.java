package com.positivity.customer.internal.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.positivity.shared.dto.CreateVehicleRequest;
import com.positivity.shared.dto.VehicleResponse;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Verifies that PeopleClient and VehicleInventoryClient call the correct direct
 * Eureka service URL (http://{serviceId}/v1/...) with the required auth headers.
 */
class CustomerClientUriTest {

    private static final UUID VEHICLE_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    // -----------------------------------------------------------------------
    // PeopleClient
    // -----------------------------------------------------------------------

    @Test
    void peopleClient_resolveOrCreate_usesEurekaUrl_withAuthHeaders() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(builder).build();

        PeopleClient client = new PeopleClient(builder, "people");

        mockServer
                .expect(requestTo("http://people/v1/people/resolve"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-User", "pos-customer"))
                .andExpect(header("X-Authorities", "people:person:view"))
                .andRespond(withSuccess(
                        "{\"personId\":\"bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb\"}",
                        MediaType.APPLICATION_JSON));

        UUID result = client.resolveOrCreatePersonId("test@example.com", "555-1234", "Doe", "John");

        assertThat(result).isEqualTo(UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"));
        mockServer.verify();
    }

    // -----------------------------------------------------------------------
    // VehicleInventoryClient
    // -----------------------------------------------------------------------

    @Test
    void vehicleInventoryClient_createVehicle_usesEurekaUrl() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(builder).build();

        VehicleInventoryClient client = new VehicleInventoryClient(builder, "vehicle-inventory");

        mockServer
                .expect(requestTo("http://vehicle-inventory/v1/vehicles"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-User", "pos-customer"))
                .andRespond(withSuccess(
                        "{\"vehicleId\":\"" + VEHICLE_ID + "\"}",
                        MediaType.APPLICATION_JSON));

        CreateVehicleRequest request = new CreateVehicleRequest();
        VehicleResponse response = client.createVehicle(request);

        assertThat(response).isNotNull();
        mockServer.verify();
    }

    @Test
    void vehicleInventoryClient_getVehicle_usesEurekaUrl() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(builder).build();

        VehicleInventoryClient client = new VehicleInventoryClient(builder, "vehicle-inventory");

        mockServer
                .expect(requestTo("http://vehicle-inventory/v1/vehicles/" + VEHICLE_ID))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-User", "pos-customer"))
                .andRespond(withSuccess(
                        "{\"vehicleId\":\"" + VEHICLE_ID + "\"}",
                        MediaType.APPLICATION_JSON));

        Optional<VehicleResponse> result = client.getVehicle(VEHICLE_ID);

        assertThat(result).isPresent();
        mockServer.verify();
    }

    @Test
    void vehicleInventoryClient_getVehicleByVin_usesEurekaUrl() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(builder).build();

        VehicleInventoryClient client = new VehicleInventoryClient(builder, "vehicle-inventory");

        mockServer
                .expect(requestTo("http://vehicle-inventory/v1/vehicles/vin/1HGCM82633A004352"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-User", "pos-customer"))
                .andRespond(withSuccess(
                        "{\"vehicleId\":\"" + VEHICLE_ID + "\"}",
                        MediaType.APPLICATION_JSON));

        Optional<VehicleResponse> result = client.getVehicleByVin("1HGCM82633A004352");

        assertThat(result).isPresent();
        mockServer.verify();
    }

    @Test
    void vehicleInventoryClient_updateVehicle_usesEurekaUrl() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(builder).build();

        VehicleInventoryClient client = new VehicleInventoryClient(builder, "vehicle-inventory");

        mockServer
                .expect(requestTo("http://vehicle-inventory/v1/vehicles/" + VEHICLE_ID))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(header("X-User", "pos-customer"))
                .andRespond(withSuccess(
                        "{\"vehicleId\":\"" + VEHICLE_ID + "\"}",
                        MediaType.APPLICATION_JSON));

        CreateVehicleRequest request = new CreateVehicleRequest();
        VehicleResponse response = client.updateVehicle(VEHICLE_ID, request);

        assertThat(response).isNotNull();
        mockServer.verify();
    }

    @Test
    void vehicleInventoryClient_deleteVehicle_usesEurekaUrl() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(builder).build();

        VehicleInventoryClient client = new VehicleInventoryClient(builder, "vehicle-inventory");

        mockServer
                .expect(requestTo("http://vehicle-inventory/v1/vehicles/" + VEHICLE_ID))
                .andExpect(method(HttpMethod.DELETE))
                .andExpect(header("X-User", "pos-customer"))
                .andRespond(withSuccess());

        client.deleteVehicle(VEHICLE_ID);

        mockServer.verify();
    }
}
