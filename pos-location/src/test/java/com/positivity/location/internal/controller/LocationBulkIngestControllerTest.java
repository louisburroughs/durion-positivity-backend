package com.positivity.location.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.bulkingest.BulkIngestRequest;
import com.positivity.location.config.TestSecurityConfig;
import com.positivity.location.internal.dto.LocationBulkIngestRecord;
import com.positivity.location.internal.dto.LocationResponseDTO;
import com.positivity.location.service.LocationService;
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

@WebMvcTest(LocationBulkIngestController.class)
@Import(TestSecurityConfig.class)
@ActiveProfiles("test")
class LocationBulkIngestControllerTest {

    private static final UUID JOB_ID = UUID.fromString("00000000-0000-0000-0000-000000000040");
    private static final UUID LOCATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000041");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockitoBean
    LocationService locationService;

    // ─── POST /v1/locations/bulk-ingest — 200 OK ─────────────────────────────

    @Test
    void bulkIngest_validRequest_returns200WithResults() throws Exception {
        LocationBulkIngestRecord locRecord = new LocationBulkIngestRecord();
        locRecord.setName("Main Store");
        locRecord.setCode("STORE-001");
        locRecord.setCity("Austin");
        locRecord.setCountryCode("US");

        BulkIngestRequest<LocationBulkIngestRecord> request = new BulkIngestRequest<>();
        request.setJobId(JOB_ID);
        request.setLocationId(LOCATION_ID);
        request.setRecords(List.of(locRecord));

        LocationResponseDTO locationResponse = new LocationResponseDTO();
        locationResponse.setId(LOCATION_ID);

        when(locationService.createLocation(any())).thenReturn(locationResponse);

        mockMvc.perform(post("/v1/locations/bulk-ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSubmitted").value(1))
                .andExpect(jsonPath("$.successCount").value(1))
                .andExpect(jsonPath("$.failureCount").value(0));
    }

    @Test
    void bulkIngest_whenServiceThrows_recordsAsFailure() throws Exception {
        LocationBulkIngestRecord locRecord = new LocationBulkIngestRecord();
        locRecord.setName("Duplicate Store");
        locRecord.setCode("STORE-001");

        BulkIngestRequest<LocationBulkIngestRecord> request = new BulkIngestRequest<>();
        request.setJobId(JOB_ID);
        request.setLocationId(LOCATION_ID);
        request.setRecords(List.of(locRecord));

        when(locationService.createLocation(any())).thenThrow(new IllegalArgumentException("Duplicate code"));

        mockMvc.perform(post("/v1/locations/bulk-ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSubmitted").value(1))
                .andExpect(jsonPath("$.successCount").value(0))
                .andExpect(jsonPath("$.failureCount").value(1));
    }

    @Test
    void bulkIngest_emptyRecords_returns400() throws Exception {
        BulkIngestRequest<LocationBulkIngestRecord> request = new BulkIngestRequest<>();
        request.setJobId(JOB_ID);
        request.setLocationId(LOCATION_ID);
        request.setRecords(List.of()); // @NotEmpty constraint

        mockMvc.perform(post("/v1/locations/bulk-ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void bulkIngest_missingJobId_returns400() throws Exception {
        LocationBulkIngestRecord locRecord = new LocationBulkIngestRecord();
        locRecord.setName("Main Store");
        locRecord.setCode("STORE-001");

        BulkIngestRequest<LocationBulkIngestRecord> request = new BulkIngestRequest<>();
        // jobId is null — @NotNull constraint
        request.setLocationId(LOCATION_ID);
        request.setRecords(List.of(locRecord));

        mockMvc.perform(post("/v1/locations/bulk-ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }
}
