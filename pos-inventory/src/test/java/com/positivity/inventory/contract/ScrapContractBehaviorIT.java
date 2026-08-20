package com.positivity.inventory.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.domainevents.inventory.ScrapPostedV1;
import com.positivity.inventory.internal.config.OutboxEventWriter;
import com.positivity.inventory.internal.entity.ApprovalThresholdConfig;
import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.entity.InventoryStockSummary;
import com.positivity.inventory.internal.entity.OutboxEvent;
import com.positivity.inventory.internal.enums.ApprovalFlowType;
import com.positivity.inventory.internal.enums.ApprovalTier;
import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import com.positivity.inventory.internal.repository.ApprovalThresholdConfigRepository;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.internal.repository.InventoryStockSummaryRepository;
import com.positivity.inventory.internal.repository.OutboxEventRepository;
import com.positivity.inventory.internal.repository.ScrapRecordRepository;
import com.positivity.inventory.internal.service.LedgerPostingService;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Contract behavior ITs for the scrap workflow (odoo-parity D1, issue #1030).
 *
 * <p>Covers the acceptance shape from spec §5: below-threshold scraps auto-post SCRAP_OUT and
 * decrement the stock summary (with a {@link ScrapPostedV1} fact in the outbox), above-threshold
 * scraps require approval before posting, rejection leaves stock untouched, insufficient
 * on-hand yields the guided-reconciliation 422, and the authorized negative-stock override
 * drives on-hand below zero (SCRAP_OUT is the matrix's BLOCKED_OVERRIDABLE row).
 */
@DisplayName("Scrap Contract Behavior")
class ScrapContractBehaviorIT extends BaseContractIntegrationTest {

    @TestConfiguration
    static class OutboxTestConfig {
        @Bean
        OutboxEventWriter outboxEventWriter(
                Clock clock, ObjectMapper objectMapper, OutboxEventRepository outboxEventRepository) {
            return new OutboxEventWriter(clock, objectMapper, outboxEventRepository);
        }
    }

    private static final UUID LOCATION_ID = UUID.fromString("01960004-0001-7000-8000-000000000009");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ScrapRecordRepository scrapRepository;

    @Autowired
    private ApprovalThresholdConfigRepository thresholdConfigRepository;

    @Autowired
    private InventoryLedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private InventoryStockSummaryRepository stockSummaryRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private LedgerPostingService ledgerPostingService;

    @BeforeEach
    void setUp() {
        outboxEventRepository.deleteAll();
        ledgerEntryRepository.deleteAll();
        stockSummaryRepository.deleteAll();
        scrapRepository.deleteAll();
        thresholdConfigRepository.deleteAll();
        seedScrapThresholds();
    }

    // ─── Below threshold: auto-approve, post, decrement, fact ─────────────────

    @Test
    @DisplayName("below-threshold scrap auto-posts SCRAP_OUT, decrements the summary, and queues the fact")
    void belowThresholdScrap_autoPostsAndDecrements() throws Exception {
        String sku = uniqueSku();
        seedReceipt(sku, new BigDecimal("10"), new BigDecimal("2.0000"));

        MvcResult result = mockMvc.perform(withGatewayAuth(post("/v1/inventory/scraps"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(sku, 3, "DAMAGED", null, false)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("POSTED"))
                .andExpect(jsonPath("$.approvedBy").value("SYSTEM"))
                .andExpect(jsonPath("$.unitCostSnapshot").exists())
                .andExpect(jsonPath("$.costSource").value("LATEST_RECEIPT"))
                .andExpect(jsonPath("$.ledgerEntryId").exists())
                .andReturn();
        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        String scrapId = response.get("scrapId").asString();

        List<InventoryLedgerEntry> scrapEntries =
                ledgerEntryRepository.findByStockItemIdOrderByTimestampDesc(sku).stream()
                        .filter(entry -> entry.getEventType() == InventoryLedgerEventType.SCRAP_OUT)
                        .toList();
        assertThat(scrapEntries).hasSize(1);
        assertThat(scrapEntries.getFirst().getChangeInQuantity()).isEqualByComparingTo("-3");
        assertThat(scrapEntries.getFirst().getReasonCode()).isEqualTo("DAMAGED");
        assertThat(scrapEntries.getFirst().getSourceTransactionId()).isEqualTo(scrapId);

        assertThat(onHand(sku)).isEqualTo(7);

        List<OutboxEvent> factRows = outboxEventRepository.findAll().stream()
                .filter(row -> row.getPayload().contains(ScrapPostedV1.EVENT_TYPE))
                .toList();
        assertThat(factRows).hasSize(1);
        assertThat(factRows.getFirst().getPayload()).contains(sku);
        assertThat(factRows.getFirst().getPayload()).contains(scrapId);
    }

    // ─── Above threshold: pending, then approve posts ─────────────────────────

    @Test
    @DisplayName("above-threshold scrap requires approval, then approve posts SCRAP_OUT")
    void aboveThresholdScrap_requiresApprovalThenPosts() throws Exception {
        String sku = uniqueSku();
        seedReceipt(sku, new BigDecimal("10"), new BigDecimal("200.0000"));

        MvcResult created = mockMvc.perform(withGatewayAuth(post("/v1/inventory/scraps"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(sku, 3, "RECALLED", null, false)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.requiredApprovalTier").value("TIER_1_MANAGER"))
                .andReturn();
        String scrapId = objectMapper
                .readTree(created.getResponse().getContentAsString())
                .get("scrapId")
                .asString();

        // Nothing posted while pending.
        assertThat(onHand(sku)).isEqualTo(10);

        mockMvc.perform(withGatewayAuth(post("/v1/inventory/scraps/{id}/approve", scrapId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("POSTED"))
                .andExpect(jsonPath("$.approvedBy").value("contract-test-user"));

        assertThat(onHand(sku)).isEqualTo(7);
        assertThat(outboxEventRepository.findAll())
                .anyMatch(row -> row.getPayload().contains(ScrapPostedV1.EVENT_TYPE));
    }

    // ─── Reject leaves stock untouched ────────────────────────────────────────

    @Test
    @DisplayName("reject leaves stock untouched")
    void rejectScrap_leavesStockUntouched() throws Exception {
        String sku = uniqueSku();
        seedReceipt(sku, new BigDecimal("10"), new BigDecimal("200.0000"));

        MvcResult created = mockMvc.perform(withGatewayAuth(post("/v1/inventory/scraps"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(sku, 3, "EXPIRED", null, false)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                .andReturn();
        String scrapId = objectMapper
                .readTree(created.getResponse().getContentAsString())
                .get("scrapId")
                .asString();

        mockMvc.perform(withGatewayAuth(post("/v1/inventory/scraps/{id}/reject", scrapId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rejectionReason\":\"Recovered and restocked\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectedBy").value("contract-test-user"));

        assertThat(onHand(sku)).isEqualTo(10);
        assertThat(ledgerEntryRepository.findByStockItemIdOrderByTimestampDesc(sku))
                .noneMatch(entry -> entry.getEventType() == InventoryLedgerEventType.SCRAP_OUT);
        assertThat(outboxEventRepository.findAll())
                .noneMatch(row -> row.getPayload().contains(ScrapPostedV1.EVENT_TYPE));
    }

    // ─── Insufficient stock: guided-reconciliation 422 ────────────────────────

    @Test
    @DisplayName("insufficient on-hand yields the deterministic SCRAP_INSUFFICIENT_STOCK 422 and rolls back")
    void insufficientStock_yieldsGuided422() throws Exception {
        String sku = uniqueSku();
        seedReceipt(sku, new BigDecimal("2"), new BigDecimal("2.0000"));

        mockMvc.perform(withGatewayAuth(post("/v1/inventory/scraps"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(sku, 5, "DAMAGED", null, false)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SCRAP_INSUFFICIENT_STOCK"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("cycle count")));

        // The whole create transaction rolled back: no scrap document, stock untouched.
        assertThat(scrapRepository.findAll()).isEmpty();
        assertThat(onHand(sku)).isEqualTo(2);
    }

    // ─── Override path: authorized override drives on-hand negative ───────────

    @Test
    @DisplayName("authorized negative-stock override posts and drives on-hand below zero")
    void authorizedOverride_postsNegative() throws Exception {
        String sku = uniqueSku();
        seedReceipt(sku, new BigDecimal("2"), new BigDecimal("2.0000"));

        mockMvc.perform(withScrapOverrideAuth(post("/v1/inventory/scraps"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(sku, 5, "DAMAGED", null, true)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("POSTED"));

        // SCRAP_OUT is BLOCKED_OVERRIDABLE: the explicit authorized override
        // permits the negative balance (NegativeStockPolicy matrix, K1).
        assertThat(onHand(sku)).isEqualTo(-3);
    }

    @Test
    @DisplayName("override flag without inventory:adjustment:override is rejected with 403")
    void overrideWithoutPermission_returns403() throws Exception {
        String sku = uniqueSku();
        seedReceipt(sku, new BigDecimal("2"), new BigDecimal("2.0000"));

        mockMvc.perform(withGatewayAuth(post("/v1/inventory/scraps"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(sku, 5, "DAMAGED", null, true)))
                .andExpect(status().isForbidden());

        assertThat(onHand(sku)).isEqualTo(2);
    }

    // ─── Validation and permission gating ─────────────────────────────────────

    @Test
    @DisplayName("OTHER reason without notes returns 400")
    void otherWithoutNotes_returns400() throws Exception {
        String sku = uniqueSku();
        seedReceipt(sku, new BigDecimal("10"), new BigDecimal("2.0000"));

        mockMvc.perform(withGatewayAuth(post("/v1/inventory/scraps"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(sku, 1, "OTHER", null, false)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("create without inventory:scrap:create is rejected with 403")
    void createWithoutPermission_returns403() throws Exception {
        mockMvc.perform(withCreateOnlyAuth(post("/v1/inventory/scraps"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(uniqueSku(), 1, "DAMAGED", null, false)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("list filters by status and returns the scrap document")
    void listScraps_filtersByStatus() throws Exception {
        String sku = uniqueSku();
        seedReceipt(sku, new BigDecimal("10"), new BigDecimal("200.0000"));
        mockMvc.perform(withGatewayAuth(post("/v1/inventory/scraps"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(sku, 3, "CONTAMINATED", null, false)))
                .andExpect(status().isCreated());

        mockMvc.perform(withGatewayAuth(get("/v1/inventory/scraps").param("status", "PENDING_APPROVAL")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].stockItemId").value(sku))
                .andExpect(jsonPath("$[0].reasonCode").value("CONTAMINATED"));

        mockMvc.perform(withGatewayAuth(get("/v1/inventory/scraps").param("status", "POSTED")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private MockHttpServletRequestBuilder withScrapOverrideAuth(MockHttpServletRequestBuilder requestBuilder) {
        return requestBuilder
                .header("X-User", "contract-test-user")
                .header(
                        "X-Authorities",
                        String.join(
                                ",",
                                "inventory:scrap:create",
                                "inventory:scrap:view",
                                "inventory:scrap:approve",
                                "inventory:adjustment:override"));
    }

    private void seedScrapThresholds() {
        thresholdConfigRepository.save(ApprovalThresholdConfig.builder()
                .flowType(ApprovalFlowType.SCRAP)
                .approvalTier(ApprovalTier.TIER_1_MANAGER)
                .unitVarianceThreshold(0)
                .valueVarianceThreshold(new BigDecimal("100.00"))
                .percentageVarianceThreshold(BigDecimal.ZERO)
                .active(true)
                .build());
        thresholdConfigRepository.save(ApprovalThresholdConfig.builder()
                .flowType(ApprovalFlowType.SCRAP)
                .approvalTier(ApprovalTier.TIER_2_DIRECTOR)
                .unitVarianceThreshold(0)
                .valueVarianceThreshold(new BigDecimal("1000.00"))
                .percentageVarianceThreshold(BigDecimal.ZERO)
                .active(true)
                .build());
    }

    private void seedReceipt(String sku, BigDecimal quantity, BigDecimal unitCost) {
        ledgerPostingService.post(InventoryLedgerEntry.builder()
                .stockItemId(sku)
                .eventType(InventoryLedgerEventType.GOODS_RECEIPT)
                .changeInQuantity(quantity)
                .quantityAfter(quantity)
                .unitCost(unitCost)
                .locationId(LOCATION_ID)
                .transactionUserId("seed")
                .build());
    }

    private BigDecimal onHand(String sku) {
        return stockSummaryRepository.findAll().stream()
                .filter(row -> sku.equals(row.getStockItemId()))
                .map(InventoryStockSummary::getOnHand)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private String createBody(String sku, int quantity, String reasonCode, String notes, boolean override)
            throws Exception {
        var node = objectMapper.createObjectNode();
        node.put("stockItemId", sku);
        node.put("quantity", quantity);
        node.put("locationId", LOCATION_ID.toString());
        node.put("storageLocationId", LOCATION_ID.toString());
        node.put("reasonCode", reasonCode);
        if (notes != null) {
            node.put("notes", notes);
        }
        node.put("negativeStockOverride", override);
        return objectMapper.writeValueAsString(node);
    }

    private String uniqueSku() {
        return "SCRAP-IT-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
