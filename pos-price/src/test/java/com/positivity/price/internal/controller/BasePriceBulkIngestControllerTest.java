package com.positivity.price.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.bulkingest.BulkIngestRequest;
import com.positivity.price.config.TestSecurityConfig;
import com.positivity.price.internal.dto.BasePriceBulkIngestRecord;
import com.positivity.price.service.BasePriceService;
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
                .andExpect(jsonPath("$.results[0].errorCode").value("PRICE_INGEST_FAILED"));
    }
}
