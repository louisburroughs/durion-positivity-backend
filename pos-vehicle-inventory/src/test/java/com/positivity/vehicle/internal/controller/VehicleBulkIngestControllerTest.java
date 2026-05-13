package com.positivity.vehicle.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.bulkingest.BulkIngestRequest;
import com.positivity.shared.dto.VehicleResponse;
import com.positivity.vehicle.config.WebMvcTestSecurityConfig;
import com.positivity.vehicle.internal.dto.VehicleBulkIngestRecord;
import com.positivity.vehicle.service.VehicleService;
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
        VehicleBulkIngestRecord ingestRecord = new VehicleBulkIngestRecord();
        ingestRecord.setAccountId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        ingestRecord.setVin("1HGCM82633A004352");
        ingestRecord.setUnitNumber("UNIT-002");
        ingestRecord.setDescription("2024 Honda Accord");

        BulkIngestRequest<VehicleBulkIngestRecord> request = new BulkIngestRequest<>();
        request.setJobId(JOB_ID);
        request.setLocationId(LOCATION_ID);
        request.setRecords(List.of(ingestRecord));

        when(vehicleService.createVehicle(any())).thenThrow(new IllegalArgumentException("Duplicate VIN"));

        mockMvc.perform(post("/v1/vehicles/bulk-ingest")
                        .header("Authorization", "Bearer test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failureCount").value(1))
                .andExpect(jsonPath("$.results[0].errorCode").value("VEHICLE_INGEST_FAILED"));
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
