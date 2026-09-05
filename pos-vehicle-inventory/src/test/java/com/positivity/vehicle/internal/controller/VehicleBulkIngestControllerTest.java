package com.positivity.vehicle.internal.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.bulkingest.BulkIngestRequest;
import com.positivity.shared.dto.VehicleResponse;
import com.positivity.vehicle.config.WebMvcTestSecurityConfig;
import com.positivity.vehicle.internal.dto.VehicleBulkIngestRecord;
import com.positivity.vehicle.internal.exception.VehicleValidationException;
import com.positivity.vehicle.internal.exception.VehicleVinConflictException;
import com.positivity.vehicle.internal.service.VehicleService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@WebMvcTest(VehicleBulkIngestController.class)
@Import(WebMvcTestSecurityConfig.class)
@ActiveProfiles("test")
@SuppressWarnings({"java:S6813", "java:S100", "java:S1192"})
class VehicleBulkIngestControllerTest {

    private static final UUID JOB_ID = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final UUID LOCATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000011");
    private static final UUID VEHICLE_ID = UUID.fromString("00000000-0000-0000-0000-000000000020");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockitoBean
    VehicleService vehicleService;

    // ─── POST /v1/vehicles/bulk-ingest — 200 OK ──────────────────────────────

    @Test
    void bulkIngest_createsVehicle_andReturnsSuccess() throws Exception {
        VehicleBulkIngestRecord ingestRecord = new VehicleBulkIngestRecord();
        ingestRecord.setAccountId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        ingestRecord.setVin("1HGCM82633A004352");
        ingestRecord.setUnitNumber("UNIT-001");
        ingestRecord.setDescription("2024 Honda Accord");

        BulkIngestRequest<VehicleBulkIngestRecord> request = new BulkIngestRequest<>();
        request.setJobId(JOB_ID);
        request.setLocationId(LOCATION_ID);
        request.setRecords(List.of(ingestRecord));

        VehicleResponse vehicleResponse =
                VehicleResponse.builder().vehicleId(VEHICLE_ID).build();

        when(vehicleService.createVehicle(any())).thenReturn(vehicleResponse);

        mockMvc.perform(post("/v1/vehicles/bulk-ingest")
                        .header("Authorization", "Bearer test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSubmitted").value(1))
                .andExpect(jsonPath("$.successCount").value(1))
                .andExpect(jsonPath("$.failureCount").value(0));
    }

    @Test
    void bulkIngest_returnsFailure_whenServiceThrows() throws Exception {
        BulkIngestRequest<VehicleBulkIngestRecord> request = requestFor("UNIT-002");

        when(vehicleService.createVehicle(any())).thenThrow(new VehicleVinConflictException("Duplicate VIN"));

        mockMvc.perform(post("/v1/vehicles/bulk-ingest")
                        .header("Authorization", "Bearer test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failureCount").value(1))
                .andExpect(jsonPath("$.results[0].errorCode").value("VEHICLE_INGEST_FAILED"))
                .andExpect(jsonPath("$.results[0].errorMessage").value("Duplicate VIN"));
    }

    /**
     * The registry refused the row itself, so the caller is told why: this is the message they
     * need in order to correct that line and resubmit (issue #1718).
     */
    @Test
    void bulkIngest_rejectedRow_reportsTheRejectionMessage() throws Exception {
        BulkIngestRequest<VehicleBulkIngestRecord> request = requestFor("UNIT-003");

        when(vehicleService.createVehicle(any()))
                .thenThrow(new VehicleValidationException("Invalid VIN format. VIN must be 17 characters"));

        mockMvc.perform(post("/v1/vehicles/bulk-ingest")
                        .header("Authorization", "Bearer test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].errorCode").value("VEHICLE_INGEST_FAILED"))
                .andExpect(
                        jsonPath("$.results[0].errorMessage").value("Invalid VIN format. VIN must be 17 characters"));
    }

    /**
     * The defect issue #1718 reported: a server-side fault used to be echoed verbatim into the
     * 200 body, internal class names, column names and query text included. It now answers with a
     * generic code and the caller's own correlation id and nothing else — ADR-0056's rule, which
     * no exception handler could apply here because the failure is reported inside a 200.
     */
    @Test
    void bulkIngest_serverFault_reportsGenericFailureAndTheCorrelationId() throws Exception {
        BulkIngestRequest<VehicleBulkIngestRecord> request = requestFor("UNIT-004");

        when(vehicleService.createVehicle(any()))
                .thenThrow(new IllegalStateException(
                        "could not extract ResultSet; SQL [select vr1_0.vin_normalized from vehicle_record vr1_0]"));

        mockMvc.perform(post("/v1/vehicles/bulk-ingest")
                        .header("Authorization", "Bearer test")
                        .header("X-Correlation-Id", "corr-from-caller")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failureCount").value(1))
                .andExpect(jsonPath("$.results[0].errorCode").value("INGEST_INTERNAL_ERROR"))
                .andExpect(jsonPath("$.results[0].errorMessage", containsString("corr-from-caller")))
                .andExpect(jsonPath("$.results[0].errorMessage", not(containsString("ResultSet"))))
                .andExpect(jsonPath("$.results[0].errorMessage", not(containsString("vehicle_record"))));
    }

    private BulkIngestRequest<VehicleBulkIngestRecord> requestFor(String unitNumber) {
        VehicleBulkIngestRecord ingestRecord = new VehicleBulkIngestRecord();
        ingestRecord.setAccountId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        ingestRecord.setVin("1HGCM82633A004352");
        ingestRecord.setUnitNumber(unitNumber);
        ingestRecord.setDescription("2024 Honda Accord");

        BulkIngestRequest<VehicleBulkIngestRecord> request = new BulkIngestRequest<>();
        request.setJobId(JOB_ID);
        request.setLocationId(LOCATION_ID);
        request.setRecords(List.of(ingestRecord));
        return request;
    }

    @Test
    void bulkIngest_returnsUnauthorized_whenNoAuth() throws Exception {
        BulkIngestRequest<VehicleBulkIngestRecord> request = new BulkIngestRequest<>();
        request.setJobId(JOB_ID);
        request.setLocationId(LOCATION_ID);
        request.setRecords(List.of());

        mockMvc.perform(post("/v1/vehicles/bulk-ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }
}
