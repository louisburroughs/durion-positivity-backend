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
                .andExpect(header("X-Authorities", "people:person:create"))
                .andRespond(withSuccess(
                        "{\"personId\":\"bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb\"}", MediaType.APPLICATION_JSON));

        UUID result = client.resolveOrCreatePersonId("test@example.com", "555-1234", "Doe", "John");

        assertThat(result).isEqualTo(UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"));
        mockServer.verify();
    }

    @Test
    void peopleClient_fetchPersonIdentities_deserializesPosPeopleContactPoints() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(builder).build();

        PeopleClient client = new PeopleClient(builder, "people");

        // pos-people serializes the contact-point flag as JSON "primary" (not "isPrimary").
        // Regression: a primitive-boolean field mapped from the wrong name bound null and
        // threw MismatchedInputException, collapsing all contact reads to local fallback.
        UUID personId = UUID.fromString("01960025-0000-7000-8000-000000000001");
        String json = "[{\"id\":\"" + personId + "\",\"firstName\":\"Greg\",\"lastName\":\"Whitfield\","
                + "\"primaryEmail\":\"g.whitfield@example.com\",\"contactPoints\":["
                + "{\"contactType\":\"EMAIL\",\"value\":\"g.whitfield@example.com\",\"primary\":true},"
                + "{\"contactType\":\"PHONE_WORK\",\"value\":\"704-555-3001\",\"primary\":false}]}]";

        mockServer
                .expect(requestTo("http://people/v1/people/by-ids"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Authorities", "people:person:view"))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        var identities = client.fetchPersonIdentities(java.util.List.of(personId));

        assertThat(identities).containsKey(personId);
        var identity = identities.get(personId);
        assertThat(identity.displayName()).isEqualTo("Greg Whitfield");
        assertThat(identity.emails()).containsExactly("g.whitfield@example.com");
        assertThat(identity.phones()).containsExactly("704-555-3001");
        mockServer.verify();
    }

    // -----------------------------------------------------------------------
    // VehicleInventoryClient
    // -----------------------------------------------------------------------

    @Test
    void vehicleInventoryClient_createVehicle_usesEurekaUrl() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(builder).build();

        VehicleInventoryClient client = new VehicleInventoryClient(
                builder.baseUrl("http://vehicle-inventory").build());

        mockServer
                .expect(requestTo("http://vehicle-inventory/v1/vehicle-registry"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-User", "pos-customer"))
                .andExpect(header("X-Authorities", "vehicle-inventory:registry:create"))
                .andRespond(withSuccess("{\"vehicleId\":\"" + VEHICLE_ID + "\"}", MediaType.APPLICATION_JSON));

        CreateVehicleRequest request = new CreateVehicleRequest();
        VehicleResponse response = client.createVehicle(request);

        assertThat(response).isNotNull();
        mockServer.verify();
    }

    @Test
    void vehicleInventoryClient_getVehicle_usesEurekaUrl() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(builder).build();

        VehicleInventoryClient client = new VehicleInventoryClient(
                builder.baseUrl("http://vehicle-inventory").build());

        mockServer
                .expect(requestTo("http://vehicle-inventory/v1/vehicle-registry/" + VEHICLE_ID))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-User", "pos-customer"))
                .andExpect(header("X-Authorities", "vehicle-inventory:registry:view"))
                .andRespond(withSuccess("{\"vehicleId\":\"" + VEHICLE_ID + "\"}", MediaType.APPLICATION_JSON));

        Optional<VehicleResponse> result = client.getVehicle(VEHICLE_ID);

        assertThat(result).isPresent();
        mockServer.verify();
    }

    @Test
    void vehicleInventoryClient_getVehicleByVin_usesEurekaUrl() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(builder).build();

        VehicleInventoryClient client = new VehicleInventoryClient(
                builder.baseUrl("http://vehicle-inventory").build());

        mockServer
                .expect(requestTo("http://vehicle-inventory/v1/vehicle-registry/vin/1HGCM82633A004352"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-User", "pos-customer"))
                .andExpect(header("X-Authorities", "vehicle-inventory:registry:view"))
                .andRespond(withSuccess("{\"vehicleId\":\"" + VEHICLE_ID + "\"}", MediaType.APPLICATION_JSON));

        Optional<VehicleResponse> result = client.getVehicleByVin("1HGCM82633A004352");

        assertThat(result).isPresent();
        mockServer.verify();
    }

    @Test
    void vehicleInventoryClient_updateVehicle_usesEurekaUrl() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(builder).build();

        VehicleInventoryClient client = new VehicleInventoryClient(
                builder.baseUrl("http://vehicle-inventory").build());

        mockServer
                .expect(requestTo("http://vehicle-inventory/v1/vehicle-registry/" + VEHICLE_ID))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(header("X-User", "pos-customer"))
                .andExpect(header("X-Authorities", "vehicle-inventory:registry:update"))
                .andRespond(withSuccess("{\"vehicleId\":\"" + VEHICLE_ID + "\"}", MediaType.APPLICATION_JSON));

        CreateVehicleRequest request = new CreateVehicleRequest();
        VehicleResponse response = client.updateVehicle(VEHICLE_ID, request);

        assertThat(response).isNotNull();
        mockServer.verify();
    }

    @Test
    void vehicleInventoryClient_deleteVehicle_usesEurekaUrl() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(builder).build();

        VehicleInventoryClient client = new VehicleInventoryClient(
                builder.baseUrl("http://vehicle-inventory").build());

        mockServer
                .expect(requestTo("http://vehicle-inventory/v1/vehicle-registry/" + VEHICLE_ID))
                .andExpect(method(HttpMethod.DELETE))
                .andExpect(header("X-User", "pos-customer"))
                .andExpect(header("X-Authorities", "vehicle-inventory:registry:delete"))
                .andRespond(withSuccess());

        client.deleteVehicle(VEHICLE_ID);

        mockServer.verify();
    }
}
