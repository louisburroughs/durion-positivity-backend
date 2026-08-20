package com.positivity.inventory.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.domainevents.inventory.TransferOrderUpdatedV1;
import com.positivity.inventory.internal.config.OutboxEventWriter;
import com.positivity.inventory.internal.entity.ExtProductReplica;
import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.entity.InventoryLot;
import com.positivity.inventory.internal.entity.LocationRefEntity;
import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import com.positivity.inventory.internal.enums.InventoryLotStatus;
import com.positivity.inventory.internal.repository.ExtProductReplicaRepository;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.internal.repository.InventoryLotRepository;
import com.positivity.inventory.internal.repository.InventoryStockSummaryRepository;
import com.positivity.inventory.internal.repository.LocationRefRepository;
import com.positivity.inventory.internal.repository.OutboxEventRepository;
import com.positivity.inventory.internal.repository.TransferOrderRepository;
import com.positivity.inventory.internal.service.ExpectedSupplyService;
import com.positivity.inventory.internal.service.LedgerPostingService;
import com.positivity.inventory.internal.service.StockSummaryDriftVerifier;
import com.positivity.inventory.internal.service.StockSummaryRebuildService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
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
 * Dispatch/receive posting and in-transit tracking ITs (odoo-parity C2, issue #1036).
 *
 * <p>The centerpiece is the conservation invariant from spec §4: source on-hand + destination
 * in-transit + destination on-hand is constant across dispatch → partial receive → final
 * receive, with the drift verifier clean and the rebuild reproducing the same balances at
 * every step. Also covers the negative-stock block on dispatch, the receive-over-dispatched
 * 422, expected-supply {@code incomingQty} joining in-transit, and permission gating.
 */
@DisplayName("Transfer Order Dispatch/Receive and In-Transit Behavior")
class TransferOrderDispatchReceiveIT extends BaseContractIntegrationTest {

    @TestConfiguration
    static class OutboxTestConfig {
        @Bean
        OutboxEventWriter outboxEventWriter(
                Clock clock, ObjectMapper objectMapper, OutboxEventRepository outboxEventRepository) {
            return new OutboxEventWriter(clock, objectMapper, outboxEventRepository);
        }
    }

    private static final UUID SOURCE_SITE = UUID.fromString("01970003-0001-7000-8000-000000000001");
    private static final UUID DESTINATION_SITE = UUID.fromString("01970003-0001-7000-8000-000000000002");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TransferOrderRepository transferOrderRepository;

    @Autowired
    private LocationRefRepository locationRefRepository;

    @Autowired
    private InventoryLedgerEntryRepository ledgerRepository;

    @Autowired
    private InventoryStockSummaryRepository summaryRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private LedgerPostingService ledgerPostingService;

    @Autowired
    private StockSummaryDriftVerifier driftVerifier;

    @Autowired
    private StockSummaryRebuildService rebuildService;

    @Autowired
    private ExpectedSupplyService expectedSupplyService;

    @Autowired
    private ExtProductReplicaRepository extProductReplicaRepository;

    @Autowired
    private InventoryLotRepository lotRepository;

    @BeforeEach
    void setUp() {
        outboxEventRepository.deleteAll();
        transferOrderRepository.deleteAll();
        summaryRepository.deleteAll();
        ledgerRepository.deleteAll();
        locationRefRepository.deleteAll();
        seedSite(SOURCE_SITE, "C2 Source Site");
        seedSite(DESTINATION_SITE, "C2 Destination Site");
    }

    // ─── Conservation invariant (spec §4 centerpiece) ─────────────────────────

    @Test
    @DisplayName("conservation: source on-hand + destination in-transit + destination on-hand is constant"
            + " across dispatch, partial receive, and final receive")
    void conservationInvariant_holdsAcrossDispatchPartialAndFinalReceive() throws Exception {
        String sku = uniqueSku();
        seedOnHand(sku, SOURCE_SITE, new BigDecimal("10"));
        String orderId = createOrder(sku, 6);
        UUID lineId = singleLineId(orderId);

        // Step 1 — dispatch the full requested quantity.
        mockMvc.perform(withGatewayAuth(post("/v1/inventory/transfer-orders/{id}/dispatch", orderId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISPATCHED"))
                .andExpect(jsonPath("$.dispatchedBy").value("contract-test-user"))
                .andExpect(jsonPath("$.lines[0].dispatchedQty").value(6));
        assertBalances(sku, 4, 6, 0);
        assertThat(expectedIncoming(sku)).isEqualTo(6);

        List<InventoryLedgerEntry> outEntries = entriesOf(sku, InventoryLedgerEventType.TRANSFER_OUT);
        assertThat(outEntries).hasSize(1);
        assertThat(outEntries.getFirst().getSourceTransactionId()).isEqualTo(orderId);
        assertThat(outEntries.getFirst().getFromLocationId()).isEqualTo(SOURCE_SITE);
        assertThat(outEntries.getFirst().getToLocationId()).isEqualTo(DESTINATION_SITE);

        // Step 2 — partial receive (2 of 6): remainder stays in transit.
        mockMvc.perform(withGatewayAuth(post("/v1/inventory/transfer-orders/{id}/receive", orderId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(lineBody(lineId, 2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PARTIALLY_RECEIVED"))
                .andExpect(jsonPath("$.lines[0].receivedQty").value(2));
        assertBalances(sku, 4, 4, 2);
        assertThat(expectedIncoming(sku)).isEqualTo(4);

        // Step 3 — final receive (defaults to the full remainder).
        mockMvc.perform(withGatewayAuth(post("/v1/inventory/transfer-orders/{id}/receive", orderId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.lines[0].receivedQty").value(6));
        assertBalances(sku, 4, 0, 6);
        assertThat(expectedIncoming(sku)).isZero();

        List<InventoryLedgerEntry> inEntries = entriesOf(sku, InventoryLedgerEventType.TRANSFER_IN);
        assertThat(inEntries).hasSize(2);
        assertThat(inEntries).allMatch(entry -> orderId.equals(entry.getSourceTransactionId()));

        // Ledger truth reproduces the same balances: verifier clean, rebuild identical.
        assertThat(driftVerifier.verify()).isZero();
        rebuildService.rebuildFromLedger();
        assertBalances(sku, 4, 0, 6);
        assertThat(driftVerifier.verify()).isZero();

        assertThat(outboxEventRepository.findAll())
                .anyMatch(row -> row.getPayload().contains(TransferOrderUpdatedV1.EVENT_TYPE)
                        && row.getPayload().contains("\"RECEIVED\""));
    }

    // ─── Lot-tracked conservation walk (odoo-parity E2, #1042) ────────────────

    @Test
    @DisplayName("lot-tracked transfer: dispatch requires an ACTIVE lot; per-lot balances conserve across"
            + " dispatch, partial receive, and final receive exactly like the lot-agnostic ones")
    void lotTrackedTransfer_conservesPerLotBalances() throws Exception {
        UUID productId = UUID.randomUUID();
        extProductReplicaRepository.save(ExtProductReplica.builder()
                .productId(productId)
                .baseUom("EA")
                .trackingLevel("LOT")
                .aggregateVersion(1L)
                .build());
        String sku = productId.toString();
        InventoryLot lot = lotRepository.save(InventoryLot.builder()
                .stockItemId(sku)
                .lotNumber("LOT-C2-A")
                .receivedAt(Instant.parse("2026-01-01T00:00:00Z"))
                .status(InventoryLotStatus.ACTIVE)
                .build());
        seedLotOnHand(sku, SOURCE_SITE, lot.getLotId(), new BigDecimal("10"));
        String orderId = createOrder(sku, 6);
        UUID lineId = singleLineId(orderId);

        // LOT-tracked dispatch without a lot number → 422 LOT_NUMBER_REQUIRED (whole batch
        // rolled back), unknown lot → 422 LOT_UNKNOWN.
        mockMvc.perform(withGatewayAuth(post("/v1/inventory/transfer-orders/{id}/dispatch", orderId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(lineBody(lineId, 6)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LOT_NUMBER_REQUIRED"));
        mockMvc.perform(withGatewayAuth(post("/v1/inventory/transfer-orders/{id}/dispatch", orderId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(lotLineBody(lineId, 6, "LOT-NOPE")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LOT_UNKNOWN"));
        assertPerLotBalances(sku, lot.getLotId(), 10, 0, 0);

        // Valid dispatch: lot pinned on the line, per-lot balances mirror the agnostic walk.
        mockMvc.perform(withGatewayAuth(post("/v1/inventory/transfer-orders/{id}/dispatch", orderId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(lotLineBody(lineId, 6, "LOT-C2-A")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].lotNumber").value("LOT-C2-A"))
                .andExpect(jsonPath("$.lines[0].lotId").value(lot.getLotId().toString()));
        assertBalances(sku, 4, 6, 0);
        assertPerLotBalances(sku, lot.getLotId(), 4, 6, 0);

        // Partial receive (2 of 6): the arrival reuses the lot pinned at dispatch.
        mockMvc.perform(withGatewayAuth(post("/v1/inventory/transfer-orders/{id}/receive", orderId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(lineBody(lineId, 2)))
                .andExpect(status().isOk());
        assertBalances(sku, 4, 4, 2);
        assertPerLotBalances(sku, lot.getLotId(), 4, 4, 2);

        // Final receive: per-lot conservation completes; every transfer entry is lot-stamped.
        mockMvc.perform(withGatewayAuth(post("/v1/inventory/transfer-orders/{id}/receive", orderId)))
                .andExpect(status().isOk());
        assertBalances(sku, 4, 0, 6);
        assertPerLotBalances(sku, lot.getLotId(), 4, 0, 6);
        assertThat(entriesOf(sku, InventoryLedgerEventType.TRANSFER_OUT))
                .allMatch(entry -> lot.getLotId().equals(entry.getLotId()));
        assertThat(entriesOf(sku, InventoryLedgerEventType.TRANSFER_IN))
                .hasSize(2)
                .allMatch(entry -> lot.getLotId().equals(entry.getLotId()));
        assertThat(lotRepository.findById(lot.getLotId()).orElseThrow().getStatus())
                .isEqualTo(InventoryLotStatus.ACTIVE);

        // Ledger truth reproduces the per-lot rows too: verifier clean, rebuild identical.
        assertThat(driftVerifier.verify()).isZero();
        rebuildService.rebuildFromLedger();
        assertPerLotBalances(sku, lot.getLotId(), 4, 0, 6);
        assertThat(driftVerifier.verify()).isZero();
    }

    // ─── Negative stock: TRANSFER_OUT is BLOCKED below zero ──────────────────

    @Test
    @DisplayName("dispatch that would drive source on-hand below zero is blocked with 422 and rolls back")
    void dispatch_belowZero_blocked() throws Exception {
        String sku = uniqueSku();
        seedOnHand(sku, SOURCE_SITE, new BigDecimal("3"));
        String orderId = createOrder(sku, 6);

        mockMvc.perform(withGatewayAuth(post("/v1/inventory/transfer-orders/{id}/dispatch", orderId)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));

        // Whole transaction rolled back: still DRAFT, nothing dispatched, nothing in transit.
        mockMvc.perform(withGatewayAuth(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                        "/v1/inventory/transfer-orders/{id}", orderId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.lines[0].dispatchedQty").value(0));
        assertBalances(sku, 3, 0, 0);
        assertThat(entriesOf(sku, InventoryLedgerEventType.TRANSFER_OUT)).isEmpty();
    }

    // ─── Quantity bounds ──────────────────────────────────────────────────────

    @Test
    @DisplayName("dispatch quantity above requested returns 422 TRANSFER_DISPATCH_EXCEEDS_REQUESTED")
    void dispatch_overRequested_returns422() throws Exception {
        String sku = uniqueSku();
        seedOnHand(sku, SOURCE_SITE, new BigDecimal("20"));
        String orderId = createOrder(sku, 6);
        UUID lineId = singleLineId(orderId);

        mockMvc.perform(withGatewayAuth(post("/v1/inventory/transfer-orders/{id}/dispatch", orderId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(lineBody(lineId, 7)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("TRANSFER_DISPATCH_EXCEEDS_REQUESTED"));
    }

    @Test
    @DisplayName("receive quantity above the dispatched remainder returns 422 TRANSFER_RECEIVE_EXCEEDS_DISPATCHED")
    void receive_overDispatched_returns422() throws Exception {
        String sku = uniqueSku();
        seedOnHand(sku, SOURCE_SITE, new BigDecimal("10"));
        String orderId = createOrder(sku, 6);
        UUID lineId = singleLineId(orderId);
        mockMvc.perform(withGatewayAuth(post("/v1/inventory/transfer-orders/{id}/dispatch", orderId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(lineBody(lineId, 4)))
                .andExpect(status().isOk());

        mockMvc.perform(withGatewayAuth(post("/v1/inventory/transfer-orders/{id}/receive", orderId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(lineBody(lineId, 5)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("TRANSFER_RECEIVE_EXCEEDS_DISPATCHED"));

        // Rolled back: nothing received, remainder still fully in transit.
        assertBalances(sku, 6, 4, 0);
    }

    @Test
    @DisplayName("partial dispatch keeps in-transit at the dispatched (not requested) quantity")
    void partialDispatch_transitsOnlyDispatched() throws Exception {
        String sku = uniqueSku();
        seedOnHand(sku, SOURCE_SITE, new BigDecimal("10"));
        String orderId = createOrder(sku, 6);
        UUID lineId = singleLineId(orderId);

        mockMvc.perform(withGatewayAuth(post("/v1/inventory/transfer-orders/{id}/dispatch", orderId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(lineBody(lineId, 4)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].dispatchedQty").value(4));
        assertBalances(sku, 6, 4, 0);
        assertThat(expectedIncoming(sku)).isEqualTo(4);
    }

    // ─── Status gates ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("receive on a cancelled order returns 409")
    void receive_onCancelled_returns409() throws Exception {
        String sku = uniqueSku();
        seedOnHand(sku, SOURCE_SITE, new BigDecimal("10"));
        String orderId = createOrder(sku, 6);
        mockMvc.perform(withGatewayAuth(post("/v1/inventory/transfer-orders/{id}/cancel", orderId)))
                .andExpect(status().isOk());

        mockMvc.perform(withGatewayAuth(post("/v1/inventory/transfer-orders/{id}/receive", orderId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    @DisplayName("dispatch on an already dispatched order returns 409")
    void dispatch_twice_returns409() throws Exception {
        String sku = uniqueSku();
        seedOnHand(sku, SOURCE_SITE, new BigDecimal("10"));
        String orderId = createOrder(sku, 4);
        mockMvc.perform(withGatewayAuth(post("/v1/inventory/transfer-orders/{id}/dispatch", orderId)))
                .andExpect(status().isOk());

        mockMvc.perform(withGatewayAuth(post("/v1/inventory/transfer-orders/{id}/dispatch", orderId)))
                .andExpect(status().isConflict());
        assertBalances(sku, 6, 4, 0);
    }

    // ─── Permission gating ────────────────────────────────────────────────────

    @Test
    @DisplayName("dispatch without inventory:transfer:dispatch is rejected with 403")
    void dispatchWithoutPermission_returns403() throws Exception {
        String sku = uniqueSku();
        seedOnHand(sku, SOURCE_SITE, new BigDecimal("10"));
        String orderId = createOrder(sku, 4);

        mockMvc.perform(withViewOnly(post("/v1/inventory/transfer-orders/{id}/dispatch", orderId)))
                .andExpect(status().isForbidden());
        mockMvc.perform(withViewOnly(post("/v1/inventory/transfer-orders/{id}/receive", orderId)))
                .andExpect(status().isForbidden());
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private MockHttpServletRequestBuilder withViewOnly(MockHttpServletRequestBuilder requestBuilder) {
        return requestBuilder.header("X-User", "contract-test-user").header("X-Authorities", "inventory:transfer:view");
    }

    private String uniqueSku() {
        return "SKU-C2-" + UUID.randomUUID();
    }

    private void seedSite(UUID siteId, String name) {
        locationRefRepository.save(LocationRefEntity.builder()
                .locationId(siteId)
                .name(name)
                .status("ACTIVE")
                .active(true)
                .build());
    }

    private void seedOnHand(String sku, UUID locationId, BigDecimal quantity) {
        ledgerPostingService.post(InventoryLedgerEntry.builder()
                .stockItemId(sku)
                .locationId(locationId)
                .eventType(InventoryLedgerEventType.GOODS_RECEIPT)
                .changeInQuantity(quantity)
                .quantityAfter(quantity)
                .unitCost(new BigDecimal("2.0000"))
                .transactionUserId("contract-test-user")
                .timestamp(Instant.now())
                .build());
    }

    private String createOrder(String sku, int quantity) throws Exception {
        String body = "{\"sourceLocationId\":\"" + SOURCE_SITE + "\",\"destinationLocationId\":\"" + DESTINATION_SITE
                + "\",\"lines\":[{\"sku\":\"" + sku + "\",\"requestedQty\":" + quantity + "}]}";
        MvcResult result = mockMvc.perform(withGatewayAuth(post("/v1/inventory/transfer-orders"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper
                .readTree(result.getResponse().getContentAsString())
                .get("transferOrderId")
                .asString();
    }

    private UUID singleLineId(String orderId) throws Exception {
        MvcResult result = mockMvc.perform(
                        withGatewayAuth(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                "/v1/inventory/transfer-orders/{id}", orderId)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        return UUID.fromString(response.get("lines").get(0).get("lineId").asString());
    }

    private String lineBody(UUID lineId, int quantity) {
        return "{\"lines\":[{\"lineId\":\"" + lineId + "\",\"quantity\":" + quantity + "}]}";
    }

    private String lotLineBody(UUID lineId, int quantity, String lotNumber) {
        return "{\"lines\":[{\"lineId\":\"" + lineId + "\",\"quantity\":" + quantity + ",\"lotNumber\":\"" + lotNumber
                + "\"}]}";
    }

    /** Lot-tagged variant of {@link #seedOnHand} (odoo-parity E2, #1042). */
    private void seedLotOnHand(String sku, UUID locationId, UUID lotId, BigDecimal quantity) {
        ledgerPostingService.post(InventoryLedgerEntry.builder()
                .stockItemId(sku)
                .locationId(locationId)
                .eventType(InventoryLedgerEventType.GOODS_RECEIPT)
                .changeInQuantity(quantity)
                .quantityAfter(quantity)
                .lotId(lotId)
                .unitCost(new BigDecimal("2.0000"))
                .transactionUserId("contract-test-user")
                .timestamp(Instant.now())
                .build());
    }

    /**
     * Asserts the same three conserved balances as {@link #assertBalances} but over the
     * PER-LOT summary rows of one lot (odoo-parity E2, #1042): source per-lot on-hand,
     * destination per-lot in-transit, destination per-lot on-hand.
     */
    private void assertPerLotBalances(
            String sku, UUID lotId, long sourceOnHand, long destinationInTransit, long destinationOnHand) {
        assertThat(perLotRow(sku, SOURCE_SITE, lotId)
                        .map(row -> row.getOnHand())
                        .orElse(BigDecimal.ZERO))
                .isEqualTo(sourceOnHand);
        assertThat(perLotRow(sku, DESTINATION_SITE, lotId)
                        .map(row -> row.getInTransitQty())
                        .orElse(BigDecimal.ZERO))
                .isEqualTo(destinationInTransit);
        assertThat(perLotRow(sku, DESTINATION_SITE, lotId)
                        .map(row -> row.getOnHand())
                        .orElse(BigDecimal.ZERO))
                .isEqualTo(destinationOnHand);
    }

    private java.util.Optional<com.positivity.inventory.internal.entity.InventoryStockSummary> perLotRow(
            String sku, UUID locationId, UUID lotId) {
        return summaryRepository.findByKey(sku, locationId, lotId);
    }

    /**
     * Asserts the three conserved balances for the SKU: source on-hand, destination
     * in-transit, destination on-hand. Also asserts total stock across ALL summary keys of
     * the SKU equals the sum of the three — nothing leaked to any other key, so the
     * conservation invariant holds globally, not just over the asserted keys.
     */
    private void assertBalances(String sku, long sourceOnHand, long destinationInTransit, long destinationOnHand) {
        assertThat(onHand(sku, SOURCE_SITE)).isEqualTo(sourceOnHand);
        assertThat(inTransit(sku, DESTINATION_SITE)).isEqualTo(destinationInTransit);
        assertThat(onHand(sku, DESTINATION_SITE)).isEqualTo(destinationOnHand);
        assertThat(totalAcrossAllKeys(sku))
                .as("conservation: no stock outside source on-hand + destination in-transit + destination on-hand")
                .isEqualTo(sourceOnHand + destinationInTransit + destinationOnHand);
    }

    private BigDecimal totalAcrossAllKeys(String sku) {
        return summaryRepository.findByStockItemId(sku).stream()
                .map(row -> row.getOnHand().add(row.getInTransitQty()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal onHand(String sku, UUID locationId) {
        return summaryRepository
                .findByStockItemIdAndLocationId(sku, locationId)
                .map(row -> row.getOnHand())
                .orElse(BigDecimal.ZERO);
    }

    private BigDecimal inTransit(String sku, UUID locationId) {
        return summaryRepository
                .findByStockItemIdAndLocationId(sku, locationId)
                .map(row -> row.getInTransitQty())
                .orElse(BigDecimal.ZERO);
    }

    private long expectedIncoming(String sku) {
        return expectedSupplyService
                .expectedIncomingQuantity(sku, DESTINATION_SITE, null)
                .longValueExact();
    }

    private List<InventoryLedgerEntry> entriesOf(String sku, InventoryLedgerEventType eventType) {
        return ledgerRepository.findByStockItemIdOrderByTimestampDesc(sku).stream()
                .filter(entry -> entry.getEventType() == eventType)
                .toList();
    }
}
