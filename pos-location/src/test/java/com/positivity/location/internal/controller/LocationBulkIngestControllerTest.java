package com.positivity.location.internal.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.bulkingest.BulkIngestRequest;
import com.positivity.location.config.TestSecurityConfig;
import com.positivity.location.internal.dto.LocationBulkIngestRecord;
import com.positivity.location.internal.dto.LocationResponseDTO;
import com.positivity.location.internal.service.LocationService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@WebMvcTest(LocationBulkIngestController.class)
@Import(TestSecurityConfig.class)
@ActiveProfiles("test")
@SuppressWarnings({"java:S6813", "java:S100", "java:S1192"})
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
    void bulkIngest_mapsPhoneNumberToCreateRequest() throws Exception {
        LocationBulkIngestRecord locRecord = new LocationBulkIngestRecord();
        locRecord.setName("Main Store");
        locRecord.setCode("STORE-001");
        locRecord.setPhoneNumber("+1-217-555-0100");
        locRecord.setTimezone("America/New_York");

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
                .andExpect(jsonPath("$.successCount").value(1));

        var captor = org.mockito.ArgumentCaptor.forClass(com.positivity.location.internal.dto.LocationRequestDTO.class);
        org.mockito.Mockito.verify(locationService).createLocation(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getPhoneNumber())
                .isEqualTo("+1-217-555-0100");
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getTimezone())
                .isEqualTo("America/New_York");
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

        // The type LocationServiceImpl actually raises for a name collision, not a stand-in — and
        // the assertions now cover the outcome, not just the counts, so this test can tell the
        // difference between a rejection and a server fault (issue #1718 review).
        when(locationService.createLocation(any()))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "LOCATION_NAME_TAKEN"));

        mockMvc.perform(post("/v1/locations/bulk-ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSubmitted").value(1))
                .andExpect(jsonPath("$.successCount").value(0))
                .andExpect(jsonPath("$.failureCount").value(1))
                .andExpect(jsonPath("$.results[0].errorCode").value("LOCATION_INGEST_FAILED"))
                .andExpect(jsonPath("$.results[0].errorMessage").value("LOCATION_NAME_TAKEN"));
    }

    /**
     * Issue #1718: a row lost to a server-side fault must not carry the exception's text into the
     * 200 body that reports it. The caller gets a generic code and the request's correlation id.
     */
    @Test
    void bulkIngest_serverFault_reportsGenericFailureAndTheCorrelationId() throws Exception {
        LocationBulkIngestRecord locRecord = new LocationBulkIngestRecord();
        locRecord.setName("Broken Store");
        locRecord.setCode("STORE-002");

        BulkIngestRequest<LocationBulkIngestRecord> request = new BulkIngestRequest<>();
        request.setJobId(JOB_ID);
        request.setLocationId(LOCATION_ID);
        request.setRecords(List.of(locRecord));

        when(locationService.createLocation(any()))
                .thenThrow(new IllegalStateException("could not execute statement [insert into location ...]"));

        mockMvc.perform(post("/v1/locations/bulk-ingest")
                        .header("X-Correlation-Id", "corr-from-caller")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failureCount").value(1))
                .andExpect(jsonPath("$.results[0].errorCode").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.results[0].correlationId").value("corr-from-caller"))
                .andExpect(jsonPath("$.results[0].errorMessage", not(containsString("insert into location"))));
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
