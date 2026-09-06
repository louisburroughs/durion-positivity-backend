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
import com.positivity.price.internal.dto.BasePriceBulkIngestRecord;
import com.positivity.price.internal.exception.BasePriceWindowConflictException;
import com.positivity.price.internal.service.BasePriceService;
import java.time.Instant;
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

@WebMvcTest(BasePriceBulkIngestController.class)
@Import(TestSecurityConfig.class)
@ActiveProfiles("test")
@SuppressWarnings({"java:S6813", "java:S100", "java:S1192"})
class BasePriceBulkIngestControllerTest {

    private static final UUID JOB_ID = UUID.fromString("00000000-0000-0000-0000-000000000030");
    private static final UUID LOCATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000031");
    private static final UUID PRODUCT_ID = UUID.fromString("00000000-0000-0000-0000-000000000032");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockitoBean
    java.time.Clock clock;

    @MockitoBean
    BasePriceService basePriceService;

    // ─── POST /v1/price/bulk-ingest — 200 OK ─────────────────────────────────

    @Test
    void bulkIngest_returnsOkWithResults_whenAllRecordsSucceed() throws Exception {
        BasePriceBulkIngestRecord ingestRecord = new BasePriceBulkIngestRecord();
        ingestRecord.setProductId(PRODUCT_ID.toString());
        ingestRecord.setMsrp("29.99");
        ingestRecord.setCurrency("USD");
        ingestRecord.setEffectiveFrom("2025-01-01T00:00:00Z");

        BulkIngestRequest<BasePriceBulkIngestRecord> request = new BulkIngestRequest<>();
        request.setJobId(JOB_ID);
        request.setLocationId(LOCATION_ID);
        request.setRecords(List.of(ingestRecord));

        when(basePriceService.saveBasePrice(any(), any(), any(), any())).thenReturn(PRODUCT_ID);

        mockMvc.perform(post("/v1/price/bulk-ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSubmitted").value(1))
                .andExpect(jsonPath("$.successCount").value(1))
                .andExpect(jsonPath("$.failureCount").value(0));
    }

    @Test
    void bulkIngest_returnsPartialFailure_whenProductIdIsInvalid() throws Exception {
        BasePriceBulkIngestRecord ingestRecord = new BasePriceBulkIngestRecord();
        ingestRecord.setProductId("not-a-valid-uuid");
        ingestRecord.setMsrp("29.99");
        ingestRecord.setCurrency("USD");
        ingestRecord.setEffectiveFrom("2025-01-01T00:00:00Z");

        BulkIngestRequest<BasePriceBulkIngestRecord> request = new BulkIngestRequest<>();
        request.setJobId(JOB_ID);
        request.setLocationId(LOCATION_ID);
        request.setRecords(List.of(ingestRecord));

        mockMvc.perform(post("/v1/price/bulk-ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSubmitted").value(1))
                .andExpect(jsonPath("$.successCount").value(0))
                .andExpect(jsonPath("$.failureCount").value(1))
                .andExpect(jsonPath("$.results[0].errorCode").value("PRICE_INGEST_FAILED"))
                // The controller parses this field itself, so it names the failure while it is
                // still unambiguous rather than leaving a JDK type to be classified (#1718).
                .andExpect(jsonPath("$.results[0].errorMessage").value("productId is not a valid UUID"));
    }

    /**
     * Issue #1718: a row lost to a server-side fault must not carry the exception's text into the
     * 200 body that reports it. The caller gets a generic code and their own correlation id.
     */
    @Test
    void bulkIngest_serverFault_reportsGenericFailureAndTheCorrelationId() throws Exception {
        BasePriceBulkIngestRecord ingestRecord = new BasePriceBulkIngestRecord();
        ingestRecord.setProductId(UUID.randomUUID().toString());
        ingestRecord.setMsrp("29.99");
        ingestRecord.setCurrency("USD");
        ingestRecord.setEffectiveFrom("2025-01-01T00:00:00Z");

        BulkIngestRequest<BasePriceBulkIngestRecord> request = new BulkIngestRequest<>();
        request.setJobId(JOB_ID);
        request.setLocationId(LOCATION_ID);
        request.setRecords(List.of(ingestRecord));

        when(basePriceService.saveBasePrice(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("could not execute statement [insert into base_price ...]"));

        mockMvc.perform(post("/v1/price/bulk-ingest")
                        .header("X-Correlation-Id", "corr-from-caller")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failureCount").value(1))
                .andExpect(jsonPath("$.results[0].errorCode").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.results[0].correlationId").value("corr-from-caller"))
                .andExpect(jsonPath("$.results[0].errorMessage", not(containsString("base_price"))));
    }

    /**
     * The other declared rejection type: a window collision the service raises. Its message
     * describes the caller's own row, so unlike the fault above it is returned in full.
     */
    @Test
    void bulkIngest_windowConflict_keepsTheRejectionMessage() throws Exception {
        BasePriceBulkIngestRecord ingestRecord = new BasePriceBulkIngestRecord();
        ingestRecord.setProductId(UUID.randomUUID().toString());
        ingestRecord.setMsrp("29.99");
        ingestRecord.setCurrency("USD");
        ingestRecord.setEffectiveFrom("2025-01-01T00:00:00Z");

        BulkIngestRequest<BasePriceBulkIngestRecord> request = new BulkIngestRequest<>();
        request.setJobId(JOB_ID);
        request.setLocationId(LOCATION_ID);
        request.setRecords(List.of(ingestRecord));

        BasePriceWindowConflictException conflict =
                new BasePriceWindowConflictException(PRODUCT_ID, "USD", Instant.parse("2025-01-01T00:00:00Z"));
        when(basePriceService.saveBasePrice(any(), any(), any(), any())).thenThrow(conflict);

        mockMvc.perform(post("/v1/price/bulk-ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failureCount").value(1))
                .andExpect(jsonPath("$.results[0].errorCode").value("PRICE_INGEST_FAILED"))
                // Whatever the service formats, verbatim: this row is the caller's to fix.
                .andExpect(jsonPath("$.results[0].errorMessage").value(conflict.getMessage()));
    }
}
