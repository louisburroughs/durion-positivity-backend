package com.positivity.vehiclefitment.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.bulkingest.BulkIngestRequest;
import com.positivity.vehiclefitment.config.WebMvcTestSecurityConfig;
import com.positivity.vehiclefitment.internal.dto.FitmentBulkIngestRecord;
import com.positivity.vehiclefitment.service.VehicleFitmentService;
import com.positivity.vehiclefitment.service.dto.PartFitmentResponse;
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

@WebMvcTest(VehicleFitmentBulkIngestController.class)
@Import(WebMvcTestSecurityConfig.class)
@ActiveProfiles("test")
@SuppressWarnings({"java:S6813", "java:S100", "java:S1192"})
class VehicleFitmentBulkIngestControllerTest {

    private static final UUID JOB_ID = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final UUID LOCATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000011");
    private static final UUID FITMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000030");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockitoBean
    VehicleFitmentService vehicleFitmentService;

    // ─── POST /v1/fitments/bulk-ingest — 200 OK ──────────────────────────────

    @Test
    void bulkIngest_createsFitment_andReturnsSuccess() throws Exception {
        FitmentBulkIngestRecord ingestRecord = new FitmentBulkIngestRecord();
        ingestRecord.setPartNumberId(99001L);
        ingestRecord.setManufacturerName("Bosch");
        ingestRecord.setMakeName("Toyota");

        BulkIngestRequest<FitmentBulkIngestRecord> request = new BulkIngestRequest<>();
        request.setJobId(JOB_ID);
        request.setLocationId(LOCATION_ID);
        request.setRecords(List.of(ingestRecord));

        PartFitmentResponse fitmentResponse = PartFitmentResponse.builder()
                .id(FITMENT_ID)
                .partNumberId(99001L)
                .build();

        when(vehicleFitmentService.createFitment(any())).thenReturn(fitmentResponse);

        mockMvc.perform(post("/v1/fitments/bulk-ingest")
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
        FitmentBulkIngestRecord ingestRecord = new FitmentBulkIngestRecord();
        ingestRecord.setPartNumberId(99002L);

        BulkIngestRequest<FitmentBulkIngestRecord> request = new BulkIngestRequest<>();
        request.setJobId(JOB_ID);
        request.setLocationId(LOCATION_ID);
        request.setRecords(List.of(ingestRecord));

        when(vehicleFitmentService.createFitment(any()))
                .thenThrow(new IllegalArgumentException("Part number not found"));

        mockMvc.perform(post("/v1/fitments/bulk-ingest")
                        .header("Authorization", "Bearer test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failureCount").value(1))
                .andExpect(jsonPath("$.results[0].errorCode").value("FITMENT_INGEST_FAILED"));
    }

    @Test
    void bulkIngest_returnsUnauthorized_whenNoAuth() throws Exception {
        BulkIngestRequest<FitmentBulkIngestRecord> request = new BulkIngestRequest<>();
        request.setJobId(JOB_ID);
        request.setLocationId(LOCATION_ID);
        request.setRecords(List.of());

        mockMvc.perform(post("/v1/fitments/bulk-ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }
}
