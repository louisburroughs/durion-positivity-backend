package com.positivity.inventory.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.inventory.internal.dto.consumption.ConsumeItemLine;
import com.positivity.inventory.internal.dto.consumption.ConsumeItemsRequest;
import com.positivity.inventory.internal.dto.picklist.ConfirmPickTaskRequest;
import com.positivity.inventory.internal.dto.picklist.GeneratePickListRequest;
import com.positivity.inventory.internal.dto.picklist.PickListResponse;
import com.positivity.inventory.internal.dto.receiving.CrossDockRequest;
import com.positivity.inventory.internal.dto.returns.ReturnItemLine;
import com.positivity.inventory.internal.dto.returns.ReturnItemsRequest;
import com.positivity.inventory.internal.entity.ExtProductReplica;
import com.positivity.inventory.internal.entity.ExtWorkorderPartReplica;
import com.positivity.inventory.internal.entity.ExtWorkorderReplica;
import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.entity.InventoryLot;
import com.positivity.inventory.internal.entity.PickListEntity;
import com.positivity.inventory.internal.entity.PickTaskEntity;
import com.positivity.inventory.internal.entity.ReceivingLine;
import com.positivity.inventory.internal.entity.ReceivingSession;
import com.positivity.inventory.internal.enums.EntryMethod;
import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import com.positivity.inventory.internal.enums.InventoryLotStatus;
import com.positivity.inventory.internal.enums.PickListStatus;
import com.positivity.inventory.internal.enums.PickTaskStatus;
import com.positivity.inventory.internal.enums.ReceivingLineStatus;
import com.positivity.inventory.internal.enums.ReceivingSessionStatus;
import com.positivity.inventory.internal.enums.ScrapReasonCode;
import com.positivity.inventory.internal.enums.SourceDocumentType;
import com.positivity.inventory.internal.exception.LotInsufficientStockException;
import com.positivity.inventory.internal.exception.LotNumberRequiredException;
import com.positivity.inventory.internal.exception.LotUnknownException;
import com.positivity.inventory.internal.repository.ExtProductReplicaRepository;
import com.positivity.inventory.internal.repository.ExtWorkorderPartReplicaRepository;
import com.positivity.inventory.internal.repository.ExtWorkorderReplicaRepository;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.internal.repository.InventoryLotRepository;
import com.positivity.inventory.internal.repository.InventoryStockSummaryRepository;
import com.positivity.inventory.internal.repository.PickListRepository;
import com.positivity.inventory.internal.repository.PickTaskRepository;
import com.positivity.inventory.internal.repository.ReceivingSessionRepository;
import com.positivity.inventory.internal.service.LedgerPostingService;
import com.positivity.inventory.internal.service.ReturnServiceImpl;
import com.positivity.inventory.service.ConsumptionService;
import com.positivity.inventory.service.PickListGenerationService;
import com.positivity.inventory.service.ReceivingService;
import com.positivity.security.common.GatewaySecurityConstants;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * IT for odoo-parity E2 (issue #1042, spec §6 E2): lot-aware outbound flows.
 *
 * <p>Covers the acceptance shape end-to-end against the real service + H2 stack:
 * <ul>
 *   <li>pick confirmation of a LOT-tracked SKU without a lot number → 422
 *       {@code LOT_NUMBER_REQUIRED}; unknown lot → {@code LOT_UNKNOWN}; QUARANTINED lot →
 *       {@code LOT_NOT_AVAILABLE}; a valid ACTIVE lot is recorded as {@code pickedLotId}</li>
 *   <li>consumption decrements the EXACT per-lot row via the pick→consumption linkage
 *       ({@code pickedLotId}); the per-lot floor blocks over-consumption of one lot while
 *       another lot still has stock ({@code LOT_INSUFFICIENT_STOCK}); a lot drawn to exactly
 *       zero flips ACTIVE → CONSUMED</li>
 *   <li>returns re-increment the per-lot row and flip CONSUMED → ACTIVE; a missing/unknown
 *       lot on a tracked return line is a 422</li>
 *   <li>scrap requires and stamps the lot on its {@code SCRAP_OUT} posting (both over HTTP
 *       and on the ledger row)</li>
 *   <li>cross-dock is now lot-gated: both paired entries carry the same {@code lotId}, the
 *       per-lot row nets to zero (no phantom per-lot on-hand), and the lot reads CONSUMED</li>
 *   <li>FIFO lot suggestion ordering vector: earliest {@code receivedAt} ACTIVE lot with
 *       stock at the suggested location wins; QUARANTINED lots are skipped</li>
 * </ul>
 * The lot-tracked transfer conservation walk lives in {@code TransferOrderDispatchReceiveIT}
 * next to the lot-agnostic invariant it mirrors. Untracked SKUs are byte-identical to pre-E2 —
 * the unmodified pre-E2 suites passing is the regression proof.
 */
@DisplayName("Lot-aware outbound flows (odoo-parity E2, #1042)")
class LotOutboundFlowsIT extends BaseContractIntegrationTest {

    private static final String ACTOR = "lot-e2-it";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private tools.jackson.databind.ObjectMapper objectMapper;

    @Autowired
    private ExtProductReplicaRepository extProductReplicaRepository;

    @Autowired
    private InventoryLotRepository lotRepository;

    @Autowired
    private InventoryLedgerEntryRepository ledgerRepository;

    @Autowired
    private InventoryStockSummaryRepository summaryRepository;

    @Autowired
    private LedgerPostingService ledgerPostingService;

    @Autowired
    private PickListRepository pickListRepository;

    @Autowired
    private PickTaskRepository pickTaskRepository;

    @Autowired
    private PickListGenerationService pickListGenerationService;

    @Autowired
    private ConsumptionService consumptionService;

    @Autowired
    private ReturnServiceImpl returnService;

    @Autowired
    private ReceivingService receivingService;

    @Autowired
    private ReceivingSessionRepository receivingSessionRepository;

    @Autowired
    private ExtWorkorderReplicaRepository extWorkorderReplicaRepository;

    @Autowired
    private ExtWorkorderPartReplicaRepository extWorkorderPartReplicaRepository;

    @BeforeEach
    void setUp() {
        var authentication = new UsernamePasswordAuthenticationToken(ACTOR, "N/A", List.of());
        authentication.setDetails(Map.of(GatewaySecurityConstants.DETAIL_USERNAME, ACTOR));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ─── Pick confirmation ──────────────────────────────────────────────────────

    @Test
    @DisplayName("pick confirm: tracked without lot → 422 LOT_NUMBER_REQUIRED; unknown → LOT_UNKNOWN;"
            + " quarantined → LOT_NOT_AVAILABLE; valid ACTIVE lot recorded as pickedLotId")
    void pickConfirm_lotGates() throws Exception {
        UUID productId = seedProduct("LOT");
        UUID locationId = UUID.randomUUID();
        InventoryLot active = seedLot(productId, "LOT-PICK-A", Instant.parse("2026-01-01T00:00:00Z"));
        InventoryLot quarantined = seedLot(productId, "LOT-PICK-Q", Instant.parse("2026-01-02T00:00:00Z"));
        quarantined.setStatus(InventoryLotStatus.QUARANTINED);
        lotRepository.save(quarantined);

        PickTaskEntity task = seedPickTask(productId, locationId, 5);
        UUID pickListId = task.getPickList().getPickListId();
        UUID taskId = task.getPickTaskId();

        mockMvc.perform(confirmRequest(pickListId, taskId, productId, locationId, 5, null))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LOT_NUMBER_REQUIRED"));

        mockMvc.perform(confirmRequest(pickListId, taskId, productId, locationId, 5, "LOT-NOPE"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LOT_UNKNOWN"));

        mockMvc.perform(confirmRequest(pickListId, taskId, productId, locationId, 5, "LOT-PICK-Q"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LOT_NOT_AVAILABLE"));

        // Nothing stuck: task still PENDING after the rejections.
        assertThat(pickTaskRepository.findById(taskId).orElseThrow().getStatus())
                .isEqualTo(PickTaskStatus.PENDING);

        mockMvc.perform(confirmRequest(pickListId, taskId, productId, locationId, 5, "LOT-PICK-A"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PICKED"))
                .andExpect(jsonPath("$.pickedLotId").value(active.getLotId().toString()));

        assertThat(pickTaskRepository.findById(taskId).orElseThrow().getPickedLotId())
                .isEqualTo(active.getLotId());
    }

    @Test
    @DisplayName("pick confirm: untracked SKU ignores a keyed lot number (byte-identical pre-E2 behavior)")
    void pickConfirm_untracked_ignoresLotNumber() throws Exception {
        UUID productId = seedProduct("NONE");
        UUID locationId = UUID.randomUUID();
        PickTaskEntity task = seedPickTask(productId, locationId, 3);

        mockMvc.perform(confirmRequest(
                        task.getPickList().getPickListId(),
                        task.getPickTaskId(),
                        productId,
                        locationId,
                        3,
                        "FREE-TEXT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PICKED"))
                .andExpect(jsonPath("$.pickedLotId").value(org.hamcrest.Matchers.nullValue()));
    }

    // ─── Consumption: exact per-lot decrement, floor, CONSUMED flip ─────────────

    @Test
    @DisplayName("consumption decrements the exact per-lot row via the pick's lot; the per-lot floor blocks"
            + " over-consumption of one lot while another has stock; exact zero flips the lot CONSUMED")
    void consumption_perLotDecrementFloorAndConsumedFlip() {
        UUID productId = seedProduct("LOT");
        InventoryLot lotA = seedLot(productId, "LOT-CONS-A", Instant.parse("2026-02-01T00:00:00Z"));
        InventoryLot lotB = seedLot(productId, "LOT-CONS-B", Instant.parse("2026-02-02T00:00:00Z"));
        // Consumption posts at the NULL-location key (pre-E2 shape, unchanged) — seed the lot
        // stock there so the walk exercises the exact rows consumption touches.
        seedLotStock(productId, null, lotA.getLotId(), 5);
        seedLotStock(productId, null, lotB.getLotId(), 5);

        UUID workorderId = UUID.randomUUID();
        PickTaskEntity task = seedPickedTask(productId, 5, lotA.getLotId());

        consumptionService.consumePickedItems(new ConsumeItemsRequest(
                workorderId, null, List.of(new ConsumeItemLine(task.getPickTaskId(), productId, 3))));

        // Exact per-lot decrement: lot A 5 -> 2, lot B untouched, agnostic row 10 -> 7.
        assertThat(perLotOnHand(productId, null, lotA.getLotId())).isEqualTo(2);
        assertThat(perLotOnHand(productId, null, lotB.getLotId())).isEqualTo(5);
        assertThat(summaryRepository
                        .findByStockItemIdAndLocationIdIsNull(productId.toString())
                        .orElseThrow()
                        .getOnHand())
                .isEqualTo(7);
        List<InventoryLedgerEntry> consumptionEntries =
                entriesOf(productId, InventoryLedgerEventType.WORKORDER_CONSUMPTION);
        assertThat(consumptionEntries).hasSize(1);
        assertThat(consumptionEntries.getFirst().getLotId()).isEqualTo(lotA.getLotId());

        // Per-lot floor: 3 more from lot A (only 2 left) is rejected even though lot B has 5.
        PickTaskEntity overTask = seedPickedTask(productId, 5, lotA.getLotId());
        ConsumeItemsRequest overRequest = new ConsumeItemsRequest(
                workorderId, null, List.of(new ConsumeItemLine(overTask.getPickTaskId(), productId, 3)));
        assertThatThrownBy(() -> consumptionService.consumePickedItems(overRequest))
                .isInstanceOf(LotInsufficientStockException.class)
                .hasMessageContaining("LOT_INSUFFICIENT_STOCK");
        assertThat(perLotOnHand(productId, null, lotA.getLotId())).isEqualTo(2);
        assertThat(lotRepository.findById(lotA.getLotId()).orElseThrow().getStatus())
                .isEqualTo(InventoryLotStatus.ACTIVE);

        // Draw lot A to exactly zero: status flips ACTIVE -> CONSUMED; lot B stays ACTIVE.
        consumptionService.consumePickedItems(new ConsumeItemsRequest(
                workorderId, null, List.of(new ConsumeItemLine(overTask.getPickTaskId(), productId, 2))));
        assertThat(perLotOnHand(productId, null, lotA.getLotId())).isZero();
        assertThat(lotRepository.findById(lotA.getLotId()).orElseThrow().getStatus())
                .isEqualTo(InventoryLotStatus.CONSUMED);
        assertThat(lotRepository.findById(lotB.getLotId()).orElseThrow().getStatus())
                .isEqualTo(InventoryLotStatus.ACTIVE);
    }

    @Test
    @DisplayName("consumption: tracked SKU whose pick recorded no lot and whose line keys none → LOT_NUMBER_REQUIRED;"
            + " an explicit line lotNumber overrides the pick's lot")
    void consumption_lotSourceRules() {
        UUID productId = seedProduct("LOT");
        InventoryLot lotA = seedLot(productId, "LOT-SRC-A", Instant.parse("2026-02-01T00:00:00Z"));
        InventoryLot lotB = seedLot(productId, "LOT-SRC-B", Instant.parse("2026-02-02T00:00:00Z"));
        seedLotStock(productId, null, lotA.getLotId(), 4);
        seedLotStock(productId, null, lotB.getLotId(), 4);

        UUID workorderId = UUID.randomUUID();
        // Pre-E2 task shape: PICKED but no pickedLotId (e.g. confirmed before E2).
        PickTaskEntity legacyTask = seedPickedTask(productId, 4, null);
        ConsumeItemsRequest noLot = new ConsumeItemsRequest(
                workorderId, null, List.of(new ConsumeItemLine(legacyTask.getPickTaskId(), productId, 2)));
        assertThatThrownBy(() -> consumptionService.consumePickedItems(noLot))
                .isInstanceOf(LotNumberRequiredException.class);

        // Explicit line lotNumber fills the gap (same validation as everywhere else).
        consumptionService.consumePickedItems(new ConsumeItemsRequest(
                workorderId,
                null,
                List.of(new ConsumeItemLine(legacyTask.getPickTaskId(), productId, 2, "LOT-SRC-B"))));
        assertThat(perLotOnHand(productId, null, lotB.getLotId())).isEqualTo(2);
        assertThat(perLotOnHand(productId, null, lotA.getLotId())).isEqualTo(4);
    }

    // ─── Returns: re-increment + CONSUMED → ACTIVE ──────────────────────────────

    @Test
    @DisplayName("return of a tracked SKU requires an existing lot; re-increments its per-lot row and flips"
            + " CONSUMED → ACTIVE")
    void returns_reincrementAndReactivate() {
        UUID productId = seedProduct("LOT");
        InventoryLot lot = seedLot(productId, "LOT-RET-A", Instant.parse("2026-03-01T00:00:00Z"));
        seedLotStock(productId, null, lot.getLotId(), 3);

        UUID workorderId = UUID.randomUUID();
        PickTaskEntity task = seedPickedTask(productId, 3, lot.getLotId());
        consumptionService.consumePickedItems(new ConsumeItemsRequest(
                workorderId, null, List.of(new ConsumeItemLine(task.getPickTaskId(), productId, 3))));
        assertThat(lotRepository.findById(lot.getLotId()).orElseThrow().getStatus())
                .isEqualTo(InventoryLotStatus.CONSUMED);

        // Tracked return without a lot number → 422 LOT_NUMBER_REQUIRED.
        ReturnItemsRequest noLot =
                new ReturnItemsRequest(workorderId, "DAMAGED", List.of(new ReturnItemLine(productId, 2, null, null)));
        assertThatThrownBy(() -> returnService.returnItemsToStock(noLot))
                .isInstanceOf(LotNumberRequiredException.class);

        // Unknown lot → 422 LOT_UNKNOWN (returns never create lots).
        ReturnItemsRequest unknownLot = new ReturnItemsRequest(
                workorderId, "DAMAGED", List.of(new ReturnItemLine(productId, 2, null, null, "LOT-NOPE")));
        assertThatThrownBy(() -> returnService.returnItemsToStock(unknownLot)).isInstanceOf(LotUnknownException.class);

        // Valid return against the CONSUMED lot: per-lot row re-increments, lot reactivates.
        returnService.returnItemsToStock(new ReturnItemsRequest(
                workorderId, "DAMAGED", List.of(new ReturnItemLine(productId, 2, null, null, "LOT-RET-A"))));
        assertThat(perLotOnHand(productId, null, lot.getLotId())).isEqualTo(2);
        assertThat(lotRepository.findById(lot.getLotId()).orElseThrow().getStatus())
                .isEqualTo(InventoryLotStatus.ACTIVE);
        List<InventoryLedgerEntry> returnEntries = entriesOf(productId, InventoryLedgerEventType.RETURN_TO_STOCK);
        assertThat(returnEntries).hasSize(1);
        assertThat(returnEntries.getFirst().getLotId()).isEqualTo(lot.getLotId());
    }

    // ─── Scrap ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("scrap of a tracked SKU without lotNumber → 422 over HTTP; with a valid lot the SCRAP_OUT"
            + " posting is stamped and the record carries the lot")
    void scrap_lotGateAndStamp() throws Exception {
        UUID productId = seedProduct("LOT");
        UUID locationId = UUID.randomUUID();
        InventoryLot lot = seedLot(productId, "LOT-SCRAP-A", Instant.parse("2026-04-01T00:00:00Z"));
        seedLotStock(productId, locationId, lot.getLotId(), 6);

        mockMvc.perform(withGatewayAuth(post("/v1/inventory/scraps"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scrapBody(productId, locationId, 2, null)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LOT_NUMBER_REQUIRED"));

        mockMvc.perform(withGatewayAuth(post("/v1/inventory/scraps"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scrapBody(productId, locationId, 2, "LOT-SCRAP-A")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("POSTED"))
                .andExpect(jsonPath("$.lotId").value(lot.getLotId().toString()));

        List<InventoryLedgerEntry> scrapEntries = entriesOf(productId, InventoryLedgerEventType.SCRAP_OUT);
        assertThat(scrapEntries).hasSize(1);
        assertThat(scrapEntries.getFirst().getLotId()).isEqualTo(lot.getLotId());
        assertThat(perLotOnHand(productId, locationId, lot.getLotId())).isEqualTo(4);
    }

    // ─── Cross-dock ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("cross-dock of a tracked product is lot-gated; both paired entries carry the same lotId and the"
            + " per-lot row nets to zero (no phantom per-lot on-hand)")
    void crossDock_lotGatedBothEntriesStamped() {
        UUID productId = seedProduct("LOT");
        UUID workorderId = UUID.randomUUID();
        UUID workorderLineId = seedWorkorderLine(workorderId, productId);
        ReceivingSession session = seedReceivingSession(productId, new BigDecimal("4"));
        UUID lineId = session.getLines().getFirst().getLineId();

        CrossDockRequest withoutLot = new CrossDockRequest(
                workorderId.toString(), workorderLineId.toString(), new BigDecimal("4"), "no lot keyed");
        assertThatThrownBy(() ->
                        receivingService.crossDockLineToWorkorder(session.getSessionId(), lineId, withoutLot, ACTOR))
                .isInstanceOf(LotNumberRequiredException.class);

        receivingService.crossDockLineToWorkorder(
                session.getSessionId(),
                lineId,
                new CrossDockRequest(
                        workorderId.toString(), workorderLineId.toString(), new BigDecimal("4"), null, "LOT-XD-1"),
                ACTOR);

        InventoryLot lot = lotRepository
                .findByStockItemIdAndLotNumber(productId.toString(), "LOT-XD-1")
                .orElseThrow();
        List<InventoryLedgerEntry> entries = ledgerRepository.findAll().stream()
                .filter(entry -> productId.toString().equals(entry.getStockItemId()))
                .toList();
        assertThat(entries).hasSize(2);
        assertThat(entries).allMatch(entry -> lot.getLotId().equals(entry.getLotId()));
        assertThat(entries)
                .extracting(InventoryLedgerEntry::getEventType)
                .containsExactlyInAnyOrder(
                        InventoryLedgerEventType.GOODS_RECEIPT, InventoryLedgerEventType.GOODS_ISSUE);

        // No phantom per-lot on-hand: the paired entries net the per-lot row to zero, and the
        // reconciler marks the lot CONSUMED (its stock went straight to the workorder).
        assertThat(summaryRepository.findByLotId(lot.getLotId()))
                .allMatch(row -> row.getOnHand() == 0 && row.getInTransitQty() == 0);
        assertThat(lotRepository.findById(lot.getLotId()).orElseThrow().getStatus())
                .isEqualTo(InventoryLotStatus.CONSUMED);
    }

    // ─── FIFO lot suggestion ────────────────────────────────────────────────────

    @Test
    @DisplayName("pick generation suggests the FIFO lot (earliest receivedAt, ACTIVE, with stock at the suggested"
            + " location); QUARANTINED lots are skipped; untracked SKUs get no suggestion")
    void pickGeneration_fifoLotSuggestion() {
        UUID productId = seedProduct("LOT");
        UUID locationId = UUID.randomUUID();
        InventoryLot oldest = seedLot(productId, "LOT-FIFO-1", Instant.parse("2026-01-01T00:00:00Z"));
        InventoryLot middle = seedLot(productId, "LOT-FIFO-2", Instant.parse("2026-02-01T00:00:00Z"));
        InventoryLot newest = seedLot(productId, "LOT-FIFO-3", Instant.parse("2026-03-01T00:00:00Z"));
        seedLotStock(productId, locationId, oldest.getLotId(), 4);
        seedLotStock(productId, locationId, middle.getLotId(), 4);
        seedLotStock(productId, locationId, newest.getLotId(), 4);

        assertThat(taskFor(generate(productId)).getSuggestedLotNumber()).isEqualTo("LOT-FIFO-1");

        // Quarantining the oldest lot moves the suggestion to the next-oldest ACTIVE lot.
        oldest.setStatus(InventoryLotStatus.QUARANTINED);
        lotRepository.save(oldest);
        assertThat(taskFor(generate(productId)).getSuggestedLotNumber()).isEqualTo("LOT-FIFO-2");

        // Untracked SKUs get no suggestion.
        UUID untracked = seedProduct("NONE");
        seedLotStock(untracked, locationId, null, 4);
        assertThat(taskFor(generate(untracked)).getSuggestedLotNumber()).isNull();
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────────

    private UUID seedProduct(String trackingLevel) {
        UUID productId = UUID.randomUUID();
        extProductReplicaRepository.save(ExtProductReplica.builder()
                .productId(productId)
                .baseUom("EA")
                .trackingLevel(trackingLevel)
                .aggregateVersion(1L)
                .build());
        return productId;
    }

    private InventoryLot seedLot(UUID productId, String lotNumber, Instant receivedAt) {
        return lotRepository.save(InventoryLot.builder()
                .stockItemId(productId.toString())
                .lotNumber(lotNumber)
                .receivedAt(receivedAt)
                .status(InventoryLotStatus.ACTIVE)
                .build());
    }

    /** Posts a lot-tagged GOODS_RECEIPT through the funnel so both summary rows exist. */
    private void seedLotStock(UUID productId, UUID locationId, UUID lotId, int quantity) {
        ledgerPostingService.post(InventoryLedgerEntry.builder()
                .stockItemId(productId.toString())
                .locationId(locationId)
                .eventType(InventoryLedgerEventType.GOODS_RECEIPT)
                .changeInQuantity(quantity)
                .quantityAfter(quantity)
                .lotId(lotId)
                .unitCost(new BigDecimal("3.0000"))
                .transactionUserId(ACTOR)
                .timestamp(Instant.now())
                .build());
    }

    private PickTaskEntity seedPickTask(UUID productId, UUID locationId, int quantityRequired) {
        PickListEntity pickList = pickListRepository.save(PickListEntity.builder()
                .workorderId(UUID.randomUUID())
                .status(PickListStatus.READY_TO_PICK)
                .build());
        return pickTaskRepository.save(PickTaskEntity.builder()
                .pickList(pickList)
                .productId(productId)
                .sku(productId.toString())
                .quantityRequired(quantityRequired)
                .suggestedLocationId(locationId)
                .status(PickTaskStatus.PENDING)
                .build());
    }

    private PickTaskEntity seedPickedTask(UUID productId, int quantityPicked, UUID pickedLotId) {
        PickTaskEntity task = seedPickTask(productId, UUID.randomUUID(), quantityPicked);
        task.setStatus(PickTaskStatus.PICKED);
        task.setQuantityPicked(quantityPicked);
        task.setPickedLotId(pickedLotId);
        return pickTaskRepository.save(task);
    }

    private UUID seedWorkorderLine(UUID workorderId, UUID productId) {
        extWorkorderReplicaRepository.save(ExtWorkorderReplica.builder()
                .workorderId(workorderId)
                .workorderNumber("WO-E2")
                .status("IN_PROGRESS")
                .aggregateVersion(1L)
                .build());
        return extWorkorderPartReplicaRepository
                .save(ExtWorkorderPartReplica.builder()
                        .workorderLineId(UUID.randomUUID())
                        .workorderId(workorderId)
                        .productEntityId(productId)
                        .quantity(new BigDecimal("4"))
                        .build())
                .getWorkorderLineId();
    }

    private ReceivingSession seedReceivingSession(UUID productId, BigDecimal expectedQuantity) {
        ReceivingSession session = ReceivingSession.builder()
                .sourceDocumentId("PO-E2-" + UUID.randomUUID())
                .sourceDocumentType(SourceDocumentType.PO)
                .status(ReceivingSessionStatus.OPEN)
                .entryMethod(EntryMethod.MANUAL)
                .createdByUserId(ACTOR)
                .build();
        ReceivingLine line = ReceivingLine.builder()
                .session(session)
                .productId(productId.toString())
                .expectedQuantity(expectedQuantity)
                .receivedQuantity(BigDecimal.ZERO)
                .status(ReceivingLineStatus.EXPECTED)
                .build();
        session.setLines(new java.util.ArrayList<>(List.of(line)));
        return receivingSessionRepository.save(session);
    }

    private PickListResponse generate(UUID productId) {
        GeneratePickListRequest request = new GeneratePickListRequest();
        request.setWorkorderId(UUID.randomUUID());
        GeneratePickListRequest.PickLineItem line = new GeneratePickListRequest.PickLineItem();
        line.setSku(productId.toString());
        line.setQuantity(2);
        request.setLineItems(List.of(line));
        return pickListGenerationService.generatePickList(request);
    }

    private PickTaskEntity taskFor(PickListResponse pickList) {
        return pickTaskRepository
                .findByPickList_PickListId(pickList.getPickListId())
                .getFirst();
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder confirmRequest(
            UUID pickListId, UUID taskId, UUID skuId, UUID locationId, int quantity, String lotNumber)
            throws Exception {
        ConfirmPickTaskRequest request = new ConfirmPickTaskRequest(skuId, locationId, quantity, lotNumber);
        return withGatewayAuth(post("/v1/inventory/pick-lists/{pickListId}/tasks/{taskId}/confirm", pickListId, taskId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request));
    }

    private String scrapBody(UUID productId, UUID locationId, int quantity, String lotNumber) {
        String lotPart = lotNumber == null ? "" : ",\"lotNumber\":\"" + lotNumber + "\"";
        return "{\"stockItemId\":\"" + productId + "\",\"quantity\":" + quantity + ",\"locationId\":\"" + locationId
                + "\",\"reasonCode\":\"" + ScrapReasonCode.DAMAGED.name() + "\"" + lotPart + "}";
    }

    private long perLotOnHand(UUID productId, UUID locationId, UUID lotId) {
        return summaryRepository
                .findByKey(productId.toString(), locationId, lotId)
                .map(row -> row.getOnHand())
                .orElse(0L);
    }

    private List<InventoryLedgerEntry> entriesOf(UUID productId, InventoryLedgerEventType eventType) {
        return ledgerRepository.findAll().stream()
                .filter(entry -> productId.toString().equals(entry.getStockItemId()))
                .filter(entry -> entry.getEventType() == eventType)
                .toList();
    }
}
