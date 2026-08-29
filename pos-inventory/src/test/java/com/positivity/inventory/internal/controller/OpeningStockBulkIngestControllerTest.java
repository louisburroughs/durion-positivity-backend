package com.positivity.inventory.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.bulkingest.BulkIngestRequest;
import com.positivity.inventory.config.TestSecurityConfig;
import com.positivity.inventory.internal.dto.AdjustmentRequestResponse;
import com.positivity.inventory.internal.dto.AvailabilityView;
import com.positivity.inventory.internal.dto.OpeningStockBulkIngestRecord;
import com.positivity.inventory.internal.movement.service.StockMovementService;
import com.positivity.inventory.internal.service.InventoryAvailabilityService;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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
 * Opening stock is the one ingest path that both files and approves, because nobody reviews an
 * opening balance. These tests pin the two things that makes load-bearing: the approval actually
 * happens (without it no stock exists), and a re-run does not double the stock.
 */
@WebMvcTest(OpeningStockBulkIngestController.class)
@Import(TestSecurityConfig.class)
@ActiveProfiles("test")
@SuppressWarnings({"java:S6813", "java:S100", "java:S1192"})
class OpeningStockBulkIngestControllerTest {

    private static final UUID JOB_ID = UUID.fromString("00000000-0000-0000-0000-000000000040");
    private static final UUID SITE_ID = UUID.fromString("00000000-0000-0000-0000-000000000041");
    private static final UUID BIN_ID = UUID.fromString("00000000-0000-0000-0000-000000000042");
    private static final UUID ADJUSTMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000043");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockitoBean
    java.time.Clock clock;

    @MockitoBean
    StockMovementService stockMovementService;

    @MockitoBean
    InventoryAvailabilityService availabilityService;

    @BeforeEach
    void stockIsEmptyByDefault() {
        when(availabilityService.queryAvailability(anyString(), any(), any(), any()))
                .thenReturn(AvailabilityView.builder()
                        .productSku("PART-001")
                        .onHandQuantity(BigDecimal.ZERO)
                        .build());
    }

    private BulkIngestRequest<OpeningStockBulkIngestRecord> request(BigDecimal quantity) {
        OpeningStockBulkIngestRecord record = new OpeningStockBulkIngestRecord();
        record.setSku("PART-001");
        record.setLocationId(BIN_ID);
        record.setQuantity(quantity);
        record.setUnitOfMeasure("EA");

        BulkIngestRequest<OpeningStockBulkIngestRecord> request = new BulkIngestRequest<>();
        request.setJobId(JOB_ID);
        request.setLocationId(SITE_ID);
        request.setOperatorId("seed-operator");
        request.setRecords(List.of(record));
        return request;
    }

    private void adjustmentIsCreated() {
        when(stockMovementService.createAdjustmentRequest(any(), anyString()))
                .thenReturn(AdjustmentRequestResponse.builder()
                        .adjustmentRequestId(ADJUSTMENT_ID)
                        .build());
    }

    @Test
    void bulkIngest_filesAndApproves_soTheStockActuallyExists() throws Exception {
        adjustmentIsCreated();

        mockMvc.perform(post("/v1/inventory/opening-stock/bulk-ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request(new BigDecimal("24")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.successCount").value(1))
                .andExpect(jsonPath("$.failureCount").value(0))
                .andExpect(jsonPath("$.results[0].entityId").value(ADJUSTMENT_ID.toString()));

        // Without the approval the adjustment sits PENDING and no ledger entry posts, so the
        // caller would be told the stock is there when it is not.
        verify(stockMovementService).approveAdjustmentRequest(eq(ADJUSTMENT_ID), eq("seed-operator"));
    }

    @Test
    void bulkIngest_usesTheRecordLocation_notTheBatchLocation() throws Exception {
        adjustmentIsCreated();

        mockMvc.perform(post("/v1/inventory/opening-stock/bulk-ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request(new BigDecimal("24")))))
                .andExpect(status().isOk());

        // The batch location is the site; the stock belongs in the bin the row names.
        verify(stockMovementService)
                .createAdjustmentRequest(
                        org.mockito.ArgumentMatchers.argThat(dto -> BIN_ID.equals(dto.getLocationId())), anyString());
    }

    @Test
    void bulkIngest_whenAlreadyStocked_skipsRatherThanDoubling() throws Exception {
        // Adjustments are deltas, so a second run of the same file would add the quantity again.
        when(availabilityService.queryAvailability(anyString(), any(), any(), any()))
                .thenReturn(AvailabilityView.builder()
                        .productSku("PART-001")
                        .onHandQuantity(new BigDecimal("24"))
                        .build());

        mockMvc.perform(post("/v1/inventory/opening-stock/bulk-ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request(new BigDecimal("24")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.successCount").value(1))
                .andExpect(jsonPath("$.failureCount").value(0));

        verify(stockMovementService, never()).createAdjustmentRequest(any(), anyString());
        verify(stockMovementService, never()).approveAdjustmentRequest(any(), anyString());
    }

    @Test
    void bulkIngest_whenAvailabilityIsUnknown_treatsTheSkuAsUnstocked() throws Exception {
        // A SKU with no stock summary at all is the normal state of one being stocked for the
        // first time, which is exactly what this endpoint is for.
        when(availabilityService.queryAvailability(anyString(), any(), any(), any()))
                .thenThrow(new IllegalStateException("no stock summary"));
        adjustmentIsCreated();

        mockMvc.perform(post("/v1/inventory/opening-stock/bulk-ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request(new BigDecimal("24")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.successCount").value(1));

        verify(stockMovementService).approveAdjustmentRequest(eq(ADJUSTMENT_ID), anyString());
    }

    @Test
    void bulkIngest_whenApprovalFails_theRowFails() throws Exception {
        adjustmentIsCreated();
        org.mockito.Mockito.doThrow(new IllegalStateException("not pending"))
                .when(stockMovementService)
                .approveAdjustmentRequest(any(), anyString());

        mockMvc.perform(post("/v1/inventory/opening-stock/bulk-ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request(new BigDecimal("24")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.successCount").value(0))
                .andExpect(jsonPath("$.failureCount").value(1))
                .andExpect(jsonPath("$.results[0].errorCode").value("OPENING_STOCK_INGEST_FAILED"));
    }

    @Test
    void bulkIngest_rejectsANonPositiveQuantity() throws Exception {
        mockMvc.perform(post("/v1/inventory/opening-stock/bulk-ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request(BigDecimal.ZERO))))
                .andExpect(status().isBadRequest());
    }
}
