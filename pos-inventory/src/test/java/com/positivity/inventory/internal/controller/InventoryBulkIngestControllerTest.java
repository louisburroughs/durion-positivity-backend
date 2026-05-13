package com.positivity.inventory.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.bulkingest.BulkIngestRequest;
import com.positivity.inventory.config.TestSecurityConfig;
import com.positivity.inventory.internal.dto.AdjustmentRequestResponse;
import com.positivity.inventory.internal.dto.InventoryBulkIngestRecord;
import com.positivity.inventory.service.StockMovementService;
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

@WebMvcTest(InventoryBulkIngestController.class)
@Import(TestSecurityConfig.class)
@ActiveProfiles("test")
@SuppressWarnings({"java:S6813", "java:S100", "java:S1192"})
class InventoryBulkIngestControllerTest {

    private static final UUID JOB_ID = UUID.fromString("00000000-0000-0000-0000-000000000030");
    private static final UUID LOCATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000031");
    private static final UUID ADJUSTMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000032");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockitoBean
    java.time.Clock clock;

    @MockitoBean
    StockMovementService stockMovementService;

    // ─── POST /v1/inventory/bulk-ingest — 200 OK ─────────────────────────────

    @Test
    void bulkIngest_validRequest_returns200WithResults() throws Exception {
        InventoryBulkIngestRecord invRecord = new InventoryBulkIngestRecord();
        invRecord.setSku("PART-001");
        invRecord.setQuantity(10);
        invRecord.setLocationId(LOCATION_ID);

        BulkIngestRequest<InventoryBulkIngestRecord> request = new BulkIngestRequest<>();
        request.setJobId(JOB_ID);
        request.setLocationId(LOCATION_ID);
        request.setRecords(List.of(invRecord));

        AdjustmentRequestResponse adjustmentResponse = AdjustmentRequestResponse.builder()
                .adjustmentRequestId(ADJUSTMENT_ID)
                .build();

        when(stockMovementService.createAdjustmentRequest(any(), any())).thenReturn(adjustmentResponse);

        mockMvc.perform(post("/v1/inventory/bulk-ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSubmitted").value(1))
                .andExpect(jsonPath("$.successCount").value(1))
                .andExpect(jsonPath("$.failureCount").value(0));
    }

    @Test
    void bulkIngest_whenServiceThrows_recordsAsFailure() throws Exception {
        InventoryBulkIngestRecord invRecord = new InventoryBulkIngestRecord();
        invRecord.setSku("BAD-001");
        invRecord.setQuantity(5);

        BulkIngestRequest<InventoryBulkIngestRecord> request = new BulkIngestRequest<>();
        request.setJobId(JOB_ID);
        request.setLocationId(LOCATION_ID);
        request.setRecords(List.of(invRecord));

        when(stockMovementService.createAdjustmentRequest(any(), any()))
                .thenThrow(new IllegalArgumentException("Unknown SKU"));

        mockMvc.perform(post("/v1/inventory/bulk-ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSubmitted").value(1))
                .andExpect(jsonPath("$.successCount").value(0))
                .andExpect(jsonPath("$.failureCount").value(1));
    }

    @Test
    void bulkIngest_emptyRecords_returns400() throws Exception {
        BulkIngestRequest<InventoryBulkIngestRecord> request = new BulkIngestRequest<>();
        request.setJobId(JOB_ID);
        request.setLocationId(LOCATION_ID);
        request.setRecords(List.of()); // @NotEmpty constraint

        mockMvc.perform(post("/v1/inventory/bulk-ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }
}
