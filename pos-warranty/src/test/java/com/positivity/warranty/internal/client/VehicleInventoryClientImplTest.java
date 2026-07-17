package com.positivity.warranty.internal.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.positivity.shared.dto.VehicleResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * {@link VehicleInventoryClientImpl} wire behavior against a {@link MockRestServiceServer}
 * (same pattern as {@link InvoiceClientTest}). The response JSON is produced by serializing an
 * actual {@link com.positivity.shared.dto.VehicleResponse} — the producer-side DTO
 * pos-vehicle-inventory returns — making this a contract test: if the hand-written
 * {@code VehicleResponseWire} record's field names drift from {@code VehicleResponse}
 * (vin/odometerValue/odometerUnit, PRD §3.4 frozen snapshot), these tests fail.
 */
class VehicleInventoryClientImplTest {

    private static final String BASE = "http://vehicle-inventory/v1/vehicle-registry";
    private static final UUID VEHICLE_ID = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    private MockRestServiceServer server;
    private VehicleInventoryClientImpl client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new VehicleInventoryClientImpl(builder, "vehicle-inventory", "/v1/vehicle-registry");
    }

    private static String producerJson(Integer odometerValue, String odometerUnit) throws Exception {
        VehicleResponse producerDto = VehicleResponse.builder()
                .vehicleId(VEHICLE_ID)
                .accountId(UUID.fromString("22222222-2222-7222-8222-222222222222"))
                .vin("1HGCM82633A004352")
                .vinNormalized("1HGCM82633A004352")
                .unitNumber("UNIT-1024")
                .odometerValue(odometerValue)
                .odometerUnit(odometerUnit)
                .build();
        return MAPPER.writeValueAsString(producerDto);
    }

    @Test
    void mapsVinAndOdometerSnapshotFromTheProducerDtoAndSendsServiceHeaders() throws Exception {
        server.expect(requestTo(BASE + "/" + VEHICLE_ID))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-User", "pos-warranty-service"))
                .andExpect(header("X-Authorities", "warranty:claim:view"))
                .andRespond(withSuccess(producerJson(42_000, "MILES"), MediaType.APPLICATION_JSON));

        Optional<VehicleInventoryClient.VehicleSnapshot> snapshot = client.getVehicle(VEHICLE_ID);

        assertThat(snapshot).isPresent();
        assertThat(snapshot.orElseThrow().vin()).isEqualTo("1HGCM82633A004352");
        assertThat(snapshot.orElseThrow().odometerValue()).isEqualTo(42_000);
        assertThat(snapshot.orElseThrow().odometerUnit()).isEqualTo("MILES");
        server.verify();
    }

    @Test
    void vehicleWithoutOdometerYieldsSnapshotWithNullOdometerFields() throws Exception {
        server.expect(requestTo(BASE + "/" + VEHICLE_ID))
                .andRespond(withSuccess(producerJson(null, null), MediaType.APPLICATION_JSON));

        Optional<VehicleInventoryClient.VehicleSnapshot> snapshot = client.getVehicle(VEHICLE_ID);

        assertThat(snapshot).isPresent();
        assertThat(snapshot.orElseThrow().vin()).isEqualTo("1HGCM82633A004352");
        assertThat(snapshot.orElseThrow().odometerValue()).isNull();
        assertThat(snapshot.orElseThrow().odometerUnit()).isNull();
        server.verify();
    }

    @Test
    void notFoundDegradesToEmptySoIntakeSurvivesUnknownVehicles() {
        server.expect(requestTo(BASE + "/" + VEHICLE_ID)).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(client.getVehicle(VEHICLE_ID)).isEmpty();
        server.verify();
    }

    @Test
    void transportFailureDegradesToEmptySoIntakeSurvivesAnOutage() {
        server.expect(requestTo(BASE + "/" + VEHICLE_ID))
                .andRespond(withException(new IOException("connection refused")));

        assertThat(client.getVehicle(VEHICLE_ID)).isEmpty();
        server.verify();
    }
}
