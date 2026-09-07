package com.positivity.price.internal.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.bulkingest.BulkIngestRequest;
import com.positivity.price.config.TestSecurityConfig;
import com.positivity.price.internal.dto.LaborRateAdjustmentBulkIngestRecord;
import com.positivity.price.internal.dto.LaborRateAdjustmentResponse;
import com.positivity.price.internal.dto.LaborRateBulkIngestRecord;
import com.positivity.price.internal.dto.LaborRateResponse;
import com.positivity.price.internal.exception.LaborRateValidationException;
import com.positivity.price.internal.service.LaborRateAdminService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * The two labor-rate bulk-ingest endpoints (#1575; docs/DATA_SEED_STRATEGY.md §3 Tier 2).
 *
 * <p>What is worth testing here is the contract a bulk endpoint has and the single-rate endpoint
 * does not: a bad row fails alone while the batch proceeds, a rejection carries the reason the
 * caller needs to fix the row, and a server-side fault carries a correlation id and none of the
 * exception's text (issue #1718). Idempotency itself is covered against the service.
 */
@WebMvcTest({LaborRateBulkIngestController.class, LaborRateAdjustmentBulkIngestController.class})
@Import(TestSecurityConfig.class)
@ActiveProfiles("test")
@SuppressWarnings({"java:S6813", "java:S100", "java:S1192"})
class LaborRateBulkIngestControllerTest {

    private static final UUID JOB_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b");
    private static final UUID LOCATION_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c");
    private static final UUID ENTITY_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5d");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockitoBean
    java.time.Clock clock;

    @MockitoBean
    LaborRateAdminService laborRateAdminService;

    @Test
    @DisplayName("rates: a batch reports one verdict per row")
    void ratesBatchReportsPerRowVerdicts() throws Exception {
        LaborRateResponse stored = new LaborRateResponse();
        stored.setId(ENTITY_ID);
        when(laborRateAdminService.upsertRate(any())).thenReturn(stored);

        mockMvc.perform(post("/v1/labor-rates/bulk-ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                batch(List.of(rateRecord(null, "125.0000"), rateRecord("TIRE_SERVICE", "95.0000"))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSubmitted").value(2))
                .andExpect(jsonPath("$.successCount").value(2))
                .andExpect(jsonPath("$.results[0].entityId").value(ENTITY_ID.toString()));
    }

    @Test
    @DisplayName("rates: a rate that will not parse fails its own row and names the field")
    void ratesUnparseableAmountFailsOneRow() throws Exception {
        LaborRateResponse stored = new LaborRateResponse();
        stored.setId(ENTITY_ID);
        when(laborRateAdminService.upsertRate(any())).thenReturn(stored);

        mockMvc.perform(post("/v1/labor-rates/bulk-ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(batch(
                                List.of(rateRecord(null, "125.0000"), rateRecord("TIRE_SERVICE", "ninety-five"))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.successCount").value(1))
                .andExpect(jsonPath("$.failureCount").value(1))
                .andExpect(jsonPath("$.results[1].errorCode").value("LABOR_RATE_INGEST_FAILED"))
                .andExpect(jsonPath("$.results[1].errorMessage", containsString("hourlyRate")));
    }

    @Test
    @DisplayName("rates: a category pricing does not know is the caller's row to fix")
    void ratesUnknownCategoryIsARejection() throws Exception {
        when(laborRateAdminService.upsertRate(any()))
                .thenThrow(new LaborRateValidationException("operationCategory must be one of [...]: BODYWORK"));

        mockMvc.perform(post("/v1/labor-rates/bulk-ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(batch(List.of(rateRecord("BODYWORK", "125.0000"))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failureCount").value(1))
                .andExpect(jsonPath("$.results[0].errorCode").value("LABOR_RATE_INGEST_FAILED"))
                .andExpect(jsonPath("$.results[0].errorMessage", containsString("BODYWORK")));
    }

    @Test
    @DisplayName("rates: a server-side fault gives a correlation id and none of the exception's text")
    void ratesServerFaultIsReportedGenerically() throws Exception {
        when(laborRateAdminService.upsertRate(any()))
                .thenThrow(new IllegalStateException("could not execute statement [insert into labor_rate ...]"));

        mockMvc.perform(post("/v1/labor-rates/bulk-ingest")
                        .header("X-Correlation-Id", "corr-from-caller")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(batch(List.of(rateRecord(null, "125.0000"))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failureCount").value(1))
                .andExpect(jsonPath("$.results[0].errorCode").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.results[0].correlationId").value("corr-from-caller"))
                .andExpect(jsonPath("$.results[0].errorMessage", not(containsString("labor_rate"))));
    }

    @Test
    @DisplayName("adjustments: a whole matrix loads in one call, sequences intact")
    void adjustmentsBatchLoadsTheWholeMatrix() throws Exception {
        LaborRateAdjustmentResponse stored = new LaborRateAdjustmentResponse();
        stored.setId(ENTITY_ID);
        when(laborRateAdminService.upsertAdjustment(any())).thenReturn(stored);

        mockMvc.perform(post("/v1/labor-rate-adjustments/bulk-ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(batch(List.of(
                                adjustmentRecord("CORROSION", "15.0000", "10"),
                                adjustmentRecord("FLEET_CONTRACT", "-10.0000", "90"))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.successCount").value(2))
                .andExpect(jsonPath("$.failureCount").value(0));
    }

    @Test
    @DisplayName("adjustments: a sequence that is not a whole number fails its own row")
    void adjustmentsUnparseableSequenceFailsOneRow() throws Exception {
        LaborRateAdjustmentResponse stored = new LaborRateAdjustmentResponse();
        stored.setId(ENTITY_ID);
        when(laborRateAdminService.upsertAdjustment(any())).thenReturn(stored);

        mockMvc.perform(post("/v1/labor-rate-adjustments/bulk-ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(batch(List.of(
                                adjustmentRecord("CORROSION", "15.0000", "10"),
                                adjustmentRecord("AFTER_HOURS", "25.0000", "thirty"))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.successCount").value(1))
                .andExpect(jsonPath("$.failureCount").value(1))
                .andExpect(jsonPath("$.results[1].errorCode").value("LABOR_RATE_ADJUSTMENT_INGEST_FAILED"))
                .andExpect(jsonPath("$.results[1].errorMessage", containsString("sequence")));
    }

    private static LaborRateBulkIngestRecord rateRecord(String category, String hourlyRate) {
        LaborRateBulkIngestRecord record = new LaborRateBulkIngestRecord();
        record.setOperationCategory(category);
        record.setCurrency("USD");
        record.setHourlyRate(hourlyRate);
        record.setEffectiveFrom("2026-01-01T00:00:00Z");
        return record;
    }

    private static LaborRateAdjustmentBulkIngestRecord adjustmentRecord(String code, String value, String sequence) {
        LaborRateAdjustmentBulkIngestRecord record = new LaborRateAdjustmentBulkIngestRecord();
        record.setAdjustmentCode(code);
        record.setAdjustmentType("PERCENT");
        record.setAdjustmentValue(value);
        record.setSequence(sequence);
        record.setEffectiveFrom("2026-01-01T00:00:00Z");
        return record;
    }

    private static <T> BulkIngestRequest<T> batch(List<T> records) {
        BulkIngestRequest<T> request = new BulkIngestRequest<>();
        request.setJobId(JOB_ID);
        request.setLocationId(LOCATION_ID);
        request.setRecords(records);
        return request;
    }
}
