package com.positivity.inventory.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.domainevents.inventory.ScrapPostedV1;
import com.positivity.domainevents.inventory.TransferOrderUpdatedV1;
import com.positivity.inventory.internal.config.OutboxEventWriter;
import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.entity.LocationRefEntity;
import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.internal.repository.InventoryStockSummaryRepository;
import com.positivity.inventory.internal.repository.LocationRefRepository;
import com.positivity.inventory.internal.repository.OutboxEventRepository;
import com.positivity.inventory.internal.repository.ScrapRecordRepository;
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
import tools.jackson.databind.ObjectMapper;

/**
 * Short-close ITs (odoo-parity C3, issue #1039; spec C4).
 *
 * <p>The centerpiece is the conservation extension: after short-close under EITHER disposition
 * the destination in-transit balance for the order is exactly zero, LOST_IN_TRANSIT decreases
 * GLOBAL on-hand by the remainder while RETURNED_TO_SOURCE restores source on-hand, and in both
 * cases the drift verifier is clean and a full rebuild from the ledger reproduces identical
 * summaries — proving the chosen ledger shapes need no special reconstruction rules. Also
 * covers the terminal-status 409s, validation of the mandatory disposition/reason/notes, the
 * no-scrap-document rule (no ScrapPostedV1 fact), and permission gating.
 */
@DisplayName("Transfer Order Short-Close Behavior")
class TransferOrderShortCloseIT extends BaseContractIntegrationTest {

    @TestConfiguration
    static class OutboxTestConfig {
        @Bean
        OutboxEventWriter outboxEventWriter(
                Clock clock, ObjectMapper objectMapper, OutboxEventRepository outboxEventRepository) {
            return new OutboxEventWriter(clock, objectMapper, outboxEventRepository);
        }
    }

    private static final UUID SOURCE_SITE = UUID.fromString("01970003-0003-7000-8000-000000000001");
    private static final UUID DESTINATION_SITE = UUID.fromString("01970003-0003-7000-8000-000000000002");

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
    private ScrapRecordRepository scrapRecordRepository;

    @Autowired
    private LedgerPostingService ledgerPostingService;

    @Autowired
    private StockSummaryDriftVerifier driftVerifier;

    @Autowired
    private StockSummaryRebuildService rebuildService;

    @Autowired
    private ExpectedSupplyService expectedSupplyService;

    @BeforeEach
    void setUp() {
        outboxEventRepository.deleteAll();
        transferOrderRepository.deleteAll();
        summaryRepository.deleteAll();
        ledgerRepository.deleteAll();
        locationRefRepository.deleteAll();
        seedSite(SOURCE_SITE, "C3 Source Site");
        seedSite(DESTINATION_SITE, "C3 Destination Site");
    }

    // ─── Conservation extension: LOST_IN_TRANSIT (spec C4 centerpiece) ────────

    @Test
    @DisplayName("LOST_IN_TRANSIT short-close zeroes the in-transit remainder, drops global on-hand by it,"
            + " and ledger truth reproduces the balances (drift clean, rebuild identical)")
    void shortClose_lost_zeroesTransitAndDropsGlobalOnHand() throws Exception {
        String sku = uniqueSku();
        seedOnHand(sku, SOURCE_SITE, 10);
        String orderId = createOrder(sku, 6);
        UUID lineId = singleLineId(orderId);
        dispatch(orderId);
        receive(orderId, lineId, 2); // remainder 4 stays in transit

        mockMvc.perform(withGatewayAuth(post("/v1/inventory/transfer-orders/{id}/short-close", orderId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(shortCloseBody(
                                "LOST_IN_TRANSIT", "Carrier lost the pallet", "Claim filed with carrier")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SHORT_CLOSED"))
                .andExpect(jsonPath("$.shortCloseDisposition").value("LOST_IN_TRANSIT"))
                .andExpect(jsonPath("$.shortCloseReason").value("Carrier lost the pallet"))
                .andExpect(jsonPath("$.shortClosedBy").value("contract-test-user"))
                .andExpect(jsonPath("$.lines[0].receivedQty").value(2));

        // Source 4, transit zero, destination keeps only what physically arrived; the 4 lost
        // units left global stock entirely: total 10 − 4 = 6.
        assertBalances(sku, 4, 0, 2);
        assertThat(expectedIncoming(sku)).isZero();

        // Loss posted as SCRAP_OUT (reason LOST, joined to the order) behind a constructive
        // TRANSFER_IN that cancels the outstanding transit at the destination key.
        List<InventoryLedgerEntry> scrapEntries = entriesOf(sku, InventoryLedgerEventType.SCRAP_OUT);
        assertThat(scrapEntries).hasSize(1);
        InventoryLedgerEntry scrap = scrapEntries.getFirst();
        assertThat(scrap.getChangeInQuantity()).isEqualTo(-4);
        assertThat(scrap.getReasonCode()).isEqualTo("LOST");
        assertThat(scrap.getSourceTransactionId()).isEqualTo(orderId);
        assertThat(scrap.getLocationId()).isEqualTo(DESTINATION_SITE);
        assertThat(scrap.getUnitCost()).isEqualByComparingTo(new BigDecimal("2.0000"));
        assertThat(entriesOf(sku, InventoryLedgerEventType.TRANSFER_IN)).hasSize(2); // receive 2 + constructive 4

        // Ledger truth reproduces the same balances: verifier clean, rebuild identical.
        assertThat(driftVerifier.verify()).isZero();
        rebuildService.rebuildFromLedger();
        assertBalances(sku, 4, 0, 2);
        assertThat(driftVerifier.verify()).isZero();

        // No scrap document, no ScrapPostedV1 — the transfer fact carries the disposition.
        assertThat(scrapRecordRepository.findAll()).isEmpty();
        assertThat(outboxEventRepository.findAll())
                .noneMatch(row -> row.getPayload().contains(ScrapPostedV1.EVENT_TYPE));
        assertThat(outboxEventRepository.findAll())
                .anyMatch(row -> row.getPayload().contains(TransferOrderUpdatedV1.EVENT_TYPE)
                        && row.getPayload().contains("\"SHORT_CLOSED\"")
                        && row.getPayload().contains("\"LOST_IN_TRANSIT\""));
    }

    // ─── Conservation extension: RETURNED_TO_SOURCE ───────────────────────────

    @Test
    @DisplayName("RETURNED_TO_SOURCE short-close zeroes the in-transit remainder and restores source on-hand,"
            + " with drift clean and rebuild identical")
    void shortClose_returned_zeroesTransitAndRestoresSource() throws Exception {
        String sku = uniqueSku();
        seedOnHand(sku, SOURCE_SITE, 10);
        String orderId = createOrder(sku, 6);
        UUID lineId = singleLineId(orderId);
        dispatch(orderId);
        receive(orderId, lineId, 2); // remainder 4 stays in transit

        mockMvc.perform(withGatewayAuth(post("/v1/inventory/transfer-orders/{id}/short-close", orderId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(shortCloseBody(
                                "RETURNED_TO_SOURCE", "Destination refused delivery", "Returned on truck 42")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SHORT_CLOSED"))
                .andExpect(jsonPath("$.shortCloseDisposition").value("RETURNED_TO_SOURCE"));

        // Source restored to 4 + 4 = 8, transit zero, destination keeps the received 2:
        // nothing lost — global total is still 10.
        assertBalances(sku, 8, 0, 2);
        assertThat(expectedIncoming(sku)).isZero();

        // Return shape: dispatch OUT + return OUT; receive IN + constructive IN + source IN.
        assertThat(entriesOf(sku, InventoryLedgerEventType.TRANSFER_OUT)).hasSize(2);
        List<InventoryLedgerEntry> inEntries = entriesOf(sku, InventoryLedgerEventType.TRANSFER_IN);
        assertThat(inEntries).hasSize(3);
        assertThat(inEntries).allMatch(entry -> orderId.equals(entry.getSourceTransactionId()));
        // The source-keyed arrival lands on restored on-hand (running-delta bookkeeping).
        assertThat(inEntries.stream()
                        .filter(entry -> SOURCE_SITE.equals(entry.getLocationId()))
                        .map(InventoryLedgerEntry::getQuantityAfter)
                        .toList())
                .containsExactly(8);
        assertThat(entriesOf(sku, InventoryLedgerEventType.SCRAP_OUT)).isEmpty();

        assertThat(driftVerifier.verify()).isZero();
        rebuildService.rebuildFromLedger();
        assertBalances(sku, 8, 0, 2);
        assertThat(driftVerifier.verify()).isZero();

        assertThat(outboxEventRepository.findAll())
                .anyMatch(row -> row.getPayload().contains(TransferOrderUpdatedV1.EVENT_TYPE)
                        && row.getPayload().contains("\"SHORT_CLOSED\"")
                        && row.getPayload().contains("\"RETURNED_TO_SOURCE\""));
    }

    @Test
    @DisplayName("short-close straight from DISPATCHED (no receipt at all) resolves the full dispatched quantity")
    void shortClose_fromDispatched_resolvesFullQuantity() throws Exception {
        String sku = uniqueSku();
        seedOnHand(sku, SOURCE_SITE, 10);
        String orderId = createOrder(sku, 6);
        dispatch(orderId);

        mockMvc.perform(withGatewayAuth(post("/v1/inventory/transfer-orders/{id}/short-close", orderId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(shortCloseBody("RETURNED_TO_SOURCE", "Truck turned back", "Road closure")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SHORT_CLOSED"));

        assertBalances(sku, 10, 0, 0);
        assertThat(driftVerifier.verify()).isZero();
        rebuildService.rebuildFromLedger();
        assertBalances(sku, 10, 0, 0);
        assertThat(driftVerifier.verify()).isZero();
    }

    // ─── Terminal status: no further movement ─────────────────────────────────

    @Test
    @DisplayName("receive and dispatch on a SHORT_CLOSED order return 409")
    void receiveAndDispatch_onShortClosed_return409() throws Exception {
        String sku = uniqueSku();
        seedOnHand(sku, SOURCE_SITE, 10);
        String orderId = createOrder(sku, 6);
        dispatch(orderId);
        mockMvc.perform(withGatewayAuth(post("/v1/inventory/transfer-orders/{id}/short-close", orderId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(shortCloseBody("LOST_IN_TRANSIT", "Lost", "Whole shipment missing")))
                .andExpect(status().isOk());

        mockMvc.perform(withGatewayAuth(post("/v1/inventory/transfer-orders/{id}/receive", orderId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
        mockMvc.perform(withGatewayAuth(post("/v1/inventory/transfer-orders/{id}/dispatch", orderId)))
                .andExpect(status().isConflict());
        // Balances untouched by the rejected calls: everything already written off.
        assertBalances(sku, 4, 0, 0);
    }

    // ─── Status gates and validation ──────────────────────────────────────────

    @Test
    @DisplayName("short-close before dispatch returns 409")
    void shortClose_beforeDispatch_returns409() throws Exception {
        String sku = uniqueSku();
        seedOnHand(sku, SOURCE_SITE, 10);
        String orderId = createOrder(sku, 6);

        mockMvc.perform(withGatewayAuth(post("/v1/inventory/transfer-orders/{id}/short-close", orderId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(shortCloseBody("LOST_IN_TRANSIT", "Lost", "Notes")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    @DisplayName("short-close on a fully RECEIVED order returns 409 (terminal, no remainder)")
    void shortClose_onFullyReceived_returns409() throws Exception {
        String sku = uniqueSku();
        seedOnHand(sku, SOURCE_SITE, 10);
        String orderId = createOrder(sku, 6);
        dispatch(orderId);
        mockMvc.perform(withGatewayAuth(post("/v1/inventory/transfer-orders/{id}/receive", orderId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECEIVED"));

        mockMvc.perform(withGatewayAuth(post("/v1/inventory/transfer-orders/{id}/short-close", orderId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(shortCloseBody("LOST_IN_TRANSIT", "Lost", "Notes")))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("missing disposition, reason, or notes each return 400")
    void shortClose_missingMandatoryFields_returns400() throws Exception {
        String sku = uniqueSku();
        seedOnHand(sku, SOURCE_SITE, 10);
        String orderId = createOrder(sku, 6);
        dispatch(orderId);

        mockMvc.perform(withGatewayAuth(post("/v1/inventory/transfer-orders/{id}/short-close", orderId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Lost\",\"notes\":\"Notes\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(withGatewayAuth(post("/v1/inventory/transfer-orders/{id}/short-close", orderId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"disposition\":\"LOST_IN_TRANSIT\",\"notes\":\"Notes\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(withGatewayAuth(post("/v1/inventory/transfer-orders/{id}/short-close", orderId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"disposition\":\"LOST_IN_TRANSIT\",\"reason\":\"Lost\"}"))
                .andExpect(status().isBadRequest());

        // Nothing changed: still fully in transit.
        assertBalances(sku, 4, 6, 0);
    }

    @Test
    @DisplayName("RETURNED_TO_SOURCE against a no-longer-eligible source returns 422 and rolls back")
    void shortClose_returnedToIneligibleSource_returns422() throws Exception {
        String sku = uniqueSku();
        seedOnHand(sku, SOURCE_SITE, 10);
        String orderId = createOrder(sku, 6);
        dispatch(orderId);
        LocationRefEntity source =
                locationRefRepository.findByLocationId(SOURCE_SITE).orElseThrow();
        source.setStatus("INACTIVE");
        locationRefRepository.save(source);

        mockMvc.perform(withGatewayAuth(post("/v1/inventory/transfer-orders/{id}/short-close", orderId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(shortCloseBody("RETURNED_TO_SOURCE", "Refused", "Notes")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("TRANSFER_LOCATION_NOT_ELIGIBLE"));

        // Rolled back: order still DISPATCHED with the remainder in transit.
        mockMvc.perform(withGatewayAuth(get("/v1/inventory/transfer-orders/{id}", orderId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISPATCHED"));
        assertBalances(sku, 4, 6, 0);
    }

    // ─── Permission gating ────────────────────────────────────────────────────

    @Test
    @DisplayName("short-close without inventory:transfer:short_close is rejected with 403")
    void shortCloseWithoutPermission_returns403() throws Exception {
        String sku = uniqueSku();
        seedOnHand(sku, SOURCE_SITE, 10);
        String orderId = createOrder(sku, 6);
        dispatch(orderId);

        mockMvc.perform(withDispatchOnly(post("/v1/inventory/transfer-orders/{id}/short-close", orderId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(shortCloseBody("LOST_IN_TRANSIT", "Lost", "Notes")))
                .andExpect(status().isForbidden());
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private MockHttpServletRequestBuilder withDispatchOnly(MockHttpServletRequestBuilder requestBuilder) {
        return requestBuilder
                .header("X-User", "contract-test-user")
                .header("X-Authorities", "inventory:transfer:view,inventory:transfer:dispatch");
    }

    private String uniqueSku() {
        return "SKU-C3-" + UUID.randomUUID();
    }

    private void seedSite(UUID siteId, String name) {
        locationRefRepository.save(LocationRefEntity.builder()
                .locationId(siteId)
                .name(name)
                .status("ACTIVE")
                .active(true)
                .build());
    }

    private void seedOnHand(String sku, UUID locationId, int quantity) {
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

    private void dispatch(String orderId) throws Exception {
        mockMvc.perform(withGatewayAuth(post("/v1/inventory/transfer-orders/{id}/dispatch", orderId)))
                .andExpect(status().isOk());
    }

    private void receive(String orderId, UUID lineId, int quantity) throws Exception {
        mockMvc.perform(withGatewayAuth(post("/v1/inventory/transfer-orders/{id}/receive", orderId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[{\"lineId\":\"" + lineId + "\",\"quantity\":" + quantity + "}]}"))
                .andExpect(status().isOk());
    }

    private UUID singleLineId(String orderId) throws Exception {
        MvcResult result = mockMvc.perform(withGatewayAuth(get("/v1/inventory/transfer-orders/{id}", orderId)))
                .andExpect(status().isOk())
                .andReturn();
        return UUID.fromString(objectMapper
                .readTree(result.getResponse().getContentAsString())
                .get("lines")
                .get(0)
                .get("lineId")
                .asString());
    }

    private String shortCloseBody(String disposition, String reason, String notes) {
        return "{\"disposition\":\"" + disposition + "\",\"reason\":\"" + reason + "\",\"notes\":\"" + notes + "\"}";
    }

    /**
     * Asserts the three conserved balances for the SKU (source on-hand, destination in-transit,
     * destination on-hand) AND that total stock across ALL summary keys equals their sum —
     * short-close under either disposition leaks nothing to any other key.
     */
    private void assertBalances(String sku, long sourceOnHand, long destinationInTransit, long destinationOnHand) {
        assertThat(onHand(sku, SOURCE_SITE)).isEqualTo(sourceOnHand);
        assertThat(inTransit(sku, DESTINATION_SITE)).isEqualTo(destinationInTransit);
        assertThat(onHand(sku, DESTINATION_SITE)).isEqualTo(destinationOnHand);
        assertThat(inTransit(sku, SOURCE_SITE))
                .as("source key must carry no residual in-transit after any short-close shape")
                .isZero();
        assertThat(totalAcrossAllKeys(sku))
                .as("conservation: no stock outside source on-hand + destination in-transit + destination on-hand")
                .isEqualTo(sourceOnHand + destinationInTransit + destinationOnHand);
    }

    private long totalAcrossAllKeys(String sku) {
        return summaryRepository.findByStockItemId(sku).stream()
                .mapToLong(row -> row.getOnHand() + row.getInTransitQty())
                .sum();
    }

    private long onHand(String sku, UUID locationId) {
        return summaryRepository
                .findByStockItemIdAndLocationId(sku, locationId)
                .map(row -> row.getOnHand())
                .orElse(0L);
    }

    private long inTransit(String sku, UUID locationId) {
        return summaryRepository
                .findByStockItemIdAndLocationId(sku, locationId)
                .map(row -> row.getInTransitQty())
                .orElse(0L);
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
