package com.positivity.inventory.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.inventory.internal.entity.ApprovalThresholdConfig;
import com.positivity.inventory.internal.entity.CycleCountAdjustment;
import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.entity.ScrapRecord;
import com.positivity.inventory.internal.enums.AdjustmentStatus;
import com.positivity.inventory.internal.enums.ApprovalFlowType;
import com.positivity.inventory.internal.enums.ApprovalTier;
import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import com.positivity.inventory.internal.enums.ScrapStatus;
import com.positivity.inventory.internal.repository.ApprovalThresholdConfigRepository;
import com.positivity.inventory.internal.repository.CycleCountAdjustmentRepository;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.internal.repository.ScrapRecordRepository;
import com.positivity.inventory.internal.service.LedgerPostingService;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

/**
 * An unexpected ledger posting failure leaves the adjustment or scrap {@code FAILED}, with the cause
 * in {@code errorMessage} (#2170).
 *
 * <p>This has to go through the real transaction boundary. The posting exception rolls the approve or
 * create transaction back, and a {@code FAILED} save made inside that transaction used to be rolled
 * back with it, so the record stayed {@code PENDING_APPROVAL} (approve) or did not exist at all
 * (below-threshold create). A Mockito unit test cannot see a rollback, so these tests read back what
 * the database holds after the request.
 */
@DisplayName("Ledger posting failure persists FAILED (#2170)")
class LedgerPostingFailurePersistenceIT extends BaseContractIntegrationTest {

    private static final String CAUSE = "summary row lock timed out";
    private static final UUID LOCATION_ID = UUID.fromString("01960004-0001-7000-8000-000000002170");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CycleCountAdjustmentRepository adjustmentRepository;

    @Autowired
    private ScrapRecordRepository scrapRepository;

    @Autowired
    private ApprovalThresholdConfigRepository thresholdConfigRepository;

    @Autowired
    private InventoryLedgerEntryRepository ledgerEntryRepository;

    @MockitoBean
    private LedgerPostingService ledgerPostingService;

    @BeforeEach
    void setUp() {
        ledgerEntryRepository.deleteAll();
        adjustmentRepository.deleteAll();
        scrapRepository.deleteAll();
        thresholdConfigRepository.deleteAll();
        when(ledgerPostingService.post(any())).thenThrow(new IllegalStateException(CAUSE));
        when(ledgerPostingService.post(any(), anyBoolean())).thenThrow(new IllegalStateException(CAUSE));
    }

    // ─── Cycle count adjustments ──────────────────────────────────────────────

    @Test
    @DisplayName("approve: the adjustment is left FAILED with the cause, and approving again retries it")
    void approveAdjustment_postingFails_leavesFailedAndRetries() throws Exception {
        seedAdjustmentThreshold(1, "1.00", "1.00");
        UUID adjustmentId = createAdjustment("PENDING_APPROVAL");

        mockMvc.perform(approve("/v1/inventory/cycleCountAdjustments/{id}/approve", adjustmentId))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("ADJUSTMENT_LEDGER_POST_FAILED"));

        CycleCountAdjustment failed =
                adjustmentRepository.findById(adjustmentId).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(AdjustmentStatus.FAILED);
        assertThat(failed.getErrorMessage()).isEqualTo(CAUSE);
        assertThat(failed.getLedgerEntryId()).isNull();
        mockMvc.perform(withGatewayAuth(get("/v1/inventory/cycleCountAdjustments/{id}", adjustmentId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.errorMessage").value(CAUSE));

        // The failure was unexpected, so it is retryable: approving the FAILED adjustment posts it.
        doAnswer(invocation -> {
                    InventoryLedgerEntry entry = invocation.getArgument(0);
                    entry.setLedgerEntryId(UUID.randomUUID());
                    return entry;
                })
                .when(ledgerPostingService)
                .post(any());
        mockMvc.perform(approve("/v1/inventory/cycleCountAdjustments/{id}/approve", adjustmentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("POSTED"));
        CycleCountAdjustment posted =
                adjustmentRepository.findById(adjustmentId).orElseThrow();
        assertThat(posted.getStatus()).isEqualTo(AdjustmentStatus.POSTED);
        assertThat(posted.getErrorMessage()).isNull();
    }

    @Test
    @DisplayName("below-threshold create: a FAILED adjustment is recorded under the id the 500 names")
    void createAdjustment_autoApprovedPostingFails_recordsFailedAdjustment() throws Exception {
        seedAdjustmentThreshold(999_999, "999999.00", "999.00");

        String message = errorMessageOf(mockMvc.perform(withGatewayAuth(post("/v1/inventory/cycleCountAdjustments"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(adjustmentBody()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("ADJUSTMENT_LEDGER_POST_FAILED"))
                .andReturn());

        List<CycleCountAdjustment> rows = adjustmentRepository.findAll();
        assertThat(rows).hasSize(1);
        CycleCountAdjustment failed = rows.getFirst();
        assertThat(message).contains(failed.getAdjustmentId().toString());
        assertThat(failed.getStatus()).isEqualTo(AdjustmentStatus.FAILED);
        assertThat(failed.getErrorMessage()).isEqualTo(CAUSE);
        assertThat(failed.getApprovedByUserId()).isEqualTo("SYSTEM");
        assertThat(adjustmentRepository.findByStatus(AdjustmentStatus.FAILED)).hasSize(1);
    }

    // ─── Scrap ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("approve: the scrap is left FAILED with the cause, and approving again retries it")
    void approveScrap_postingFails_leavesFailedAndRetries() throws Exception {
        seedScrapThreshold();
        // No cost on record: an unknown value always needs approval.
        MvcResult created = mockMvc.perform(withGatewayAuth(post("/v1/inventory/scraps"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scrapBody(uniqueSku())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                .andReturn();
        UUID scrapId = idOf(created, "scrapId");

        mockMvc.perform(approve("/v1/inventory/scraps/{id}/approve", scrapId))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("SCRAP_LEDGER_POST_FAILED"));

        ScrapRecord failed = scrapRepository.findById(scrapId).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(ScrapStatus.FAILED);
        assertThat(failed.getErrorMessage()).isEqualTo(CAUSE);
        assertThat(failed.getApprovedBy()).isEqualTo("contract-test-user");

        // Approving the FAILED scrap retries the posting.
        doAnswer(invocation -> {
                    InventoryLedgerEntry entry = invocation.getArgument(0);
                    entry.setLedgerEntryId(UUID.randomUUID());
                    return entry;
                })
                .when(ledgerPostingService)
                .post(any(), anyBoolean());
        mockMvc.perform(approve("/v1/inventory/scraps/{id}/approve", scrapId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("POSTED"));
        ScrapRecord posted = scrapRepository.findById(scrapId).orElseThrow();
        assertThat(posted.getStatus()).isEqualTo(ScrapStatus.POSTED);
        assertThat(posted.getErrorMessage()).isNull();
    }

    @Test
    @DisplayName("below-threshold create: a FAILED scrap is recorded under the id the 500 names")
    void createScrap_autoApprovedPostingFails_recordsFailedScrap() throws Exception {
        seedScrapThreshold();
        String sku = uniqueSku();
        seedReceiptCost(sku);

        String message = errorMessageOf(mockMvc.perform(withGatewayAuth(post("/v1/inventory/scraps"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scrapBody(sku)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("SCRAP_LEDGER_POST_FAILED"))
                .andReturn());

        List<ScrapRecord> rows = scrapRepository.findAll();
        assertThat(rows).hasSize(1);
        ScrapRecord failed = rows.getFirst();
        assertThat(message).contains(failed.getScrapId().toString());
        assertThat(failed.getStatus()).isEqualTo(ScrapStatus.FAILED);
        assertThat(failed.getErrorMessage()).isEqualTo(CAUSE);
        assertThat(failed.getApprovedBy()).isEqualTo("SYSTEM");
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder approve(String path, UUID id) {
        return withGatewayAuth(post(path, id))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}");
    }

    private UUID createAdjustment(String expectedStatus) throws Exception {
        MvcResult created = mockMvc.perform(withGatewayAuth(post("/v1/inventory/cycleCountAdjustments"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(adjustmentBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value(expectedStatus))
                .andReturn();
        return idOf(created, "adjustmentId");
    }

    private String errorMessageOf(MvcResult result) throws Exception {
        return objectMapper
                .readTree(result.getResponse().getContentAsString())
                .path("message")
                .asString();
    }

    private UUID idOf(MvcResult result, String field) throws Exception {
        return UUID.fromString(objectMapper
                .readTree(result.getResponse().getContentAsString())
                .path(field)
                .asString());
    }

    private String adjustmentBody() throws Exception {
        return objectMapper.writeValueAsString(objectMapper
                .createObjectNode()
                .put("stockItemId", UUID.randomUUID().toString())
                .put("reasonCode", "CYCLE_COUNT_RECONCILIATION")
                .put("countedQuantity", 12)
                .put("quantityOnHandBefore", 10)
                .put("costAtTimeOfAdjustment", "10.00")
                .put("createdByUserId", "user-creator"));
    }

    private String scrapBody(String sku) throws Exception {
        return objectMapper.writeValueAsString(objectMapper
                .createObjectNode()
                .put("stockItemId", sku)
                .put("quantity", 1)
                .put("locationId", LOCATION_ID.toString())
                .put("storageLocationId", LOCATION_ID.toString())
                .put("reasonCode", "DAMAGED")
                .put("negativeStockOverride", false));
    }

    private void seedAdjustmentThreshold(int units, String value, String percentage) {
        thresholdConfigRepository.save(ApprovalThresholdConfig.builder()
                .approvalTier(ApprovalTier.TIER_1_MANAGER)
                .unitVarianceThreshold(units)
                .valueVarianceThreshold(new BigDecimal(value))
                .percentageVarianceThreshold(new BigDecimal(percentage))
                .active(true)
                .build());
    }

    private void seedScrapThreshold() {
        thresholdConfigRepository.save(ApprovalThresholdConfig.builder()
                .flowType(ApprovalFlowType.SCRAP)
                .approvalTier(ApprovalTier.TIER_1_MANAGER)
                .unitVarianceThreshold(0)
                .valueVarianceThreshold(new BigDecimal("100.00"))
                .percentageVarianceThreshold(BigDecimal.ZERO)
                .active(true)
                .build());
    }

    /** A receipt cost on record, so a one-unit scrap is valued below the threshold and auto-approves. */
    private void seedReceiptCost(String sku) {
        ledgerEntryRepository.save(InventoryLedgerEntry.builder()
                .stockItemId(sku)
                .eventType(InventoryLedgerEventType.GOODS_RECEIPT)
                .changeInQuantity(new BigDecimal("10"))
                .quantityAfter(new BigDecimal("10"))
                .unitCost(new BigDecimal("2.0000"))
                .locationId(LOCATION_ID)
                .transactionUserId("seed")
                .build());
    }

    private static String uniqueSku() {
        return "SCRAP-2170-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
