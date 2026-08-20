package com.positivity.inventory.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.inventory.internal.dto.asn.CreateGoodsReceiptLineRequest;
import com.positivity.inventory.internal.dto.asn.CreateGoodsReceiptRequest;
import com.positivity.inventory.internal.dto.asn.GoodsReceiptResponse;
import com.positivity.inventory.internal.dto.receiving.ReceiveItemsRequest;
import com.positivity.inventory.internal.dto.receiving.ReceiveLineRequest;
import com.positivity.inventory.internal.entity.ExtProductReplica;
import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.entity.InventoryLot;
import com.positivity.inventory.internal.entity.InventoryStockSummary;
import com.positivity.inventory.internal.entity.ReceivingLine;
import com.positivity.inventory.internal.entity.ReceivingSession;
import com.positivity.inventory.internal.enums.EntryMethod;
import com.positivity.inventory.internal.enums.InventoryLotStatus;
import com.positivity.inventory.internal.enums.ReceivingLineStatus;
import com.positivity.inventory.internal.enums.ReceivingSessionStatus;
import com.positivity.inventory.internal.enums.SourceDocumentType;
import com.positivity.inventory.internal.exception.LotNumberRequiredException;
import com.positivity.inventory.internal.repository.ExtProductReplicaRepository;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.internal.repository.InventoryLotRepository;
import com.positivity.inventory.internal.repository.InventoryStockSummaryRepository;
import com.positivity.inventory.internal.repository.ReceivingSessionRepository;
import com.positivity.inventory.service.AsnService;
import com.positivity.inventory.service.ReceivingService;
import com.positivity.security.common.GatewaySecurityConstants;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * IT for odoo-parity E1 (issue #1038, spec §6 E1): lot master + inbound capture.
 *
 * <p>Covers the acceptance shape end-to-end against the real service + H2 stack:
 * <ul>
 *   <li>a LOT-tracked receipt without a lot number is a deterministic 422
 *       ({@code LOT_NUMBER_REQUIRED}) on every inbound path, posting nothing</li>
 *   <li>a LOT-tracked receipt with a lot number find-or-creates the lot master row, stamps
 *       {@code lotId} on the ledger entries, and maintains BOTH summary rows (dual-row rule:
 *       the lot-agnostic row aggregates exactly as before E1, the per-lot row carries the
 *       lot's share)</li>
 *   <li>a second receipt of the same (stockItemId, lotNumber) reuses the existing lot</li>
 *   <li>untracked products (replica NONE, no replica, free-text SKU) are byte-identical to
 *       pre-E1: no validation, null {@code lotId} everywhere, a single lot-agnostic summary
 *       row</li>
 *   <li>SERIAL-tracked products enumerate serial units on receipt (E4, #1050); the lot gate stays
 *       inert for them (null lotId)</li>
 *   <li>the lot read API serves list filters and per-location on-hand from the per-lot rows
 *       under {@code inventory:on_hand:view}</li>
 * </ul>
 * Rebuild/drift parity of the per-lot rows is proven in {@code StockSummaryRebuildAndDriftTest},
 * which owns a clean-slate context.
 */
@DisplayName("Lot master & inbound capture (odoo-parity E1, #1038)")
class LotInboundCaptureIT extends BaseContractIntegrationTest {

    private static final String ACTOR = "lot-e1-it";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AsnService asnService;

    @Autowired
    private com.positivity.inventory.internal.service.PurchaseOrderProjectionTestSupport projection;

    @Autowired
    private ReceivingService receivingService;

    @Autowired
    private ExtProductReplicaRepository extProductReplicaRepository;

    @Autowired
    private InventoryLotRepository lotRepository;

    @Autowired
    private InventoryLedgerEntryRepository ledgerRepository;

    @Autowired
    private InventoryStockSummaryRepository summaryRepository;

    @Autowired
    private ReceivingSessionRepository receivingSessionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("CREATE SEQUENCE IF NOT EXISTS purchase_order_number_seq START WITH 1 INCREMENT BY 1");
        var authentication = new UsernamePasswordAuthenticationToken(ACTOR, "N/A", List.of());
        authentication.setDetails(Map.of(GatewaySecurityConstants.DETAIL_USERNAME, ACTOR));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ─── 422 without lot number, proven over HTTP ───────────────────────────────

    @Test
    @DisplayName("POST /goods-receipts for a LOT-tracked SKU without lotNumber → 422 LOT_NUMBER_REQUIRED")
    void goodsReceipt_lotTrackedWithoutLotNumber_returns422() throws Exception {
        UUID productId = seedProduct("LOT");
        UUID po = createApprovedPo(productId, 5, 1_000L);

        CreateGoodsReceiptRequest request = receiptRequest(po, UUID.randomUUID());
        request.setLines(List.of(receiptLine(productId.toString(), new BigDecimal("5"), 1_000L, null)));

        mockMvc.perform(withGatewayAuth(post("/v1/inventory/goods-receipts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LOT_NUMBER_REQUIRED"));

        assertThat(ledgerEntriesFor(productId)).isEmpty();
        assertThat(lotRepository.findAll((root, query, cb) -> cb.equal(root.get("stockItemId"), productId.toString())))
                .isEmpty();
    }

    // ─── happy path: lot created, ledger stamped, both summary rows correct ─────

    @Test
    @DisplayName("LOT-tracked goods receipt creates the lot, stamps ledger lotId, and maintains both summary rows")
    void goodsReceipt_lotTracked_createsLotAndMaintainsDualRows() {
        UUID productId = seedProduct("LOT");
        UUID locationId = UUID.randomUUID();
        UUID po = createApprovedPo(productId, 12, 1_000L);

        CreateGoodsReceiptRequest first = receiptRequest(po, locationId);
        first.setLines(List.of(receiptLine(productId.toString(), new BigDecimal("7"), 1_000L, "LOT-2026-A")));
        GoodsReceiptResponse firstResponse = asnService.createGoodsReceipt(first, ACTOR);
        assertThat(firstResponse.getReceiptId()).isNotNull();

        InventoryLot lot = lotRepository
                .findByStockItemIdAndLotNumber(productId.toString(), "LOT-2026-A")
                .orElseThrow();
        assertThat(lot.getStatus()).isEqualTo(InventoryLotStatus.ACTIVE);
        assertThat(lot.getReceivedAt()).isNotNull();
        assertThat(lot.getExpirationDate()).isNull();

        List<InventoryLedgerEntry> entries = ledgerEntriesFor(productId);
        assertThat(entries).hasSize(1);
        assertThat(entries.getFirst().getLotId()).isEqualTo(lot.getLotId());

        // Dual-row bookkeeping: agnostic row as before E1, per-lot row with the same delta.
        InventoryStockSummary agnostic = summaryRepository
                .findByStockItemIdAndLocationId(productId.toString(), locationId)
                .orElseThrow();
        assertThat(agnostic.getOnHand()).isEqualByComparingTo("7");
        InventoryStockSummary perLot = summaryRepository
                .findByKey(productId.toString(), locationId, lot.getLotId())
                .orElseThrow();
        assertThat(perLot.getOnHand()).isEqualByComparingTo("7");

        // Second receipt of the same lot number reuses the lot (find-or-create).
        CreateGoodsReceiptRequest second = receiptRequest(po, locationId);
        second.setLines(List.of(receiptLine(productId.toString(), new BigDecimal("5"), 1_000L, "LOT-2026-A")));
        asnService.createGoodsReceipt(second, ACTOR);

        assertThat(
                        lotRepository
                                .findAll((root, query, cb) -> cb.equal(root.get("stockItemId"), productId.toString()))
                                .stream()
                                .count())
                .isEqualTo(1);
        assertThat(summaryRepository
                        .findByKey(productId.toString(), locationId, lot.getLotId())
                        .orElseThrow()
                        .getOnHand())
                .isEqualByComparingTo("12");
        assertThat(summaryRepository
                        .findByStockItemIdAndLocationId(productId.toString(), locationId)
                        .orElseThrow()
                        .getOnHand())
                .isEqualByComparingTo("12");
    }

    // ─── receive-into-staging path ──────────────────────────────────────────────

    @Test
    @DisplayName("receive-into-staging gates LOT-tracked lines and stamps the staged ledger entry")
    void receivingIntoStaging_lotTracked_gatesAndStamps() {
        UUID productId = seedProduct("LOT");

        ReceivingSession withoutLot = seedReceivingSession(productId, new BigDecimal("4"));
        ReceiveItemsRequest missingLot = new ReceiveItemsRequest(List.of(new ReceiveLineRequest(
                withoutLot.getLines().getFirst().getLineId(), new BigDecimal("4"), null, null, null)));
        assertThatThrownBy(() -> receivingService.receiveItemsIntoStaging(withoutLot.getSessionId(), missingLot, ACTOR))
                .isInstanceOf(LotNumberRequiredException.class)
                .hasMessageContaining("LOT_NUMBER_REQUIRED");
        assertThat(ledgerEntriesFor(productId)).isEmpty();

        ReceivingSession session = seedReceivingSession(productId, new BigDecimal("4"));
        ReceiveItemsRequest withLot = new ReceiveItemsRequest(List.of(new ReceiveLineRequest(
                session.getLines().getFirst().getLineId(), new BigDecimal("4"), "LOT-STAGING-1", null, null)));
        receivingService.receiveItemsIntoStaging(session.getSessionId(), withLot, ACTOR);

        InventoryLot lot = lotRepository
                .findByStockItemIdAndLotNumber(productId.toString(), "LOT-STAGING-1")
                .orElseThrow();
        List<InventoryLedgerEntry> entries = ledgerEntriesFor(productId);
        assertThat(entries).hasSize(1);
        assertThat(entries.getFirst().getLotId()).isEqualTo(lot.getLotId());
        UUID stagingLocation = entries.getFirst().getLocationId();
        assertThat(summaryRepository
                        .findByKey(productId.toString(), stagingLocation, lot.getLotId())
                        .orElseThrow()
                        .getOnHand())
                .isEqualByComparingTo("4");

        // The keyed lot number is persisted on the receiving line for audit.
        String persistedLotNumber = jdbcTemplate.queryForObject(
                "SELECT lot_number FROM receiving_line WHERE line_id = ?",
                String.class,
                session.getLines().getFirst().getLineId());
        assertThat(persistedLotNumber).isEqualTo("LOT-STAGING-1");
    }

    // ─── PO-receive path ────────────────────────────────────────────────────────

    @Test
    @DisplayName("NONE-tracked receipt behaves byte-identically: null lotId, no lot row, single summary row")
    void untrackedReceipt_zeroBehaviorChange() {
        UUID trackedNone = seedProduct("NONE");
        UUID noReplica = UUID.randomUUID();
        String freeTextSku = "SKU-LEGACY-" + UUID.randomUUID();
        UUID locationId = UUID.randomUUID();

        for (String sku : List.of(trackedNone.toString(), noReplica.toString(), freeTextSku)) {
            UUID po = createApprovedPo(UUID.randomUUID(), 9, 1_000L);
            CreateGoodsReceiptRequest request = receiptRequest(po, locationId);
            // A keyed lot number on an untracked SKU stays document-only free text, as before E1.
            request.setLines(List.of(receiptLine(sku, new BigDecimal("9"), 1_000L, "LOT-IGNORED")));
            asnService.createGoodsReceipt(request, ACTOR);

            List<InventoryLedgerEntry> entries = ledgerRepository.findAll().stream()
                    .filter(entry -> sku.equals(entry.getStockItemId()))
                    .toList();
            assertThat(entries).hasSize(1);
            assertThat(entries.getFirst().getLotId()).isNull();
            assertThat(lotRepository.findByStockItemIdAndLotNumber(sku, "LOT-IGNORED"))
                    .isEmpty();
            // Exactly one summary row for the key — the lot-agnostic one.
            assertThat(summaryRepository.findByStockItemId(sku)).hasSize(1);
            assertThat(summaryRepository
                            .findByStockItemIdAndLocationId(sku, locationId)
                            .orElseThrow()
                            .getOnHand())
                    .isEqualByComparingTo("9");
        }
    }

    @Test
    @DisplayName("SERIAL-tracked receipt enumerates one serial unit per received unit; null lotId (serials != lots)")
    void serialTracked_enumeratesSerials() {
        UUID productId = seedProduct("SERIAL");
        UUID po = createApprovedPo(productId, 3, 1_000L);
        CreateGoodsReceiptRequest request = receiptRequest(po, UUID.randomUUID());
        CreateGoodsReceiptLineRequest line = receiptLine(productId.toString(), new BigDecimal("3"), 1_000L, null);
        line.setSerialNumbers(List.of("SN-E4-1", "SN-E4-2", "SN-E4-3"));
        request.setLines(List.of(line));

        asnService.createGoodsReceipt(request, ACTOR);

        List<InventoryLedgerEntry> entries = ledgerEntriesFor(productId);
        assertThat(entries).hasSize(1);
        assertThat(entries.getFirst().getLotId()).isNull();
    }

    @Test
    @DisplayName("SERIAL-tracked receipt whose serial count != quantity is rejected 422 SERIAL_COUNT_MISMATCH")
    void serialTracked_countMismatchRejected() {
        UUID productId = seedProduct("SERIAL");
        UUID po = createApprovedPo(productId, 3, 1_000L);
        CreateGoodsReceiptRequest request = receiptRequest(po, UUID.randomUUID());
        CreateGoodsReceiptLineRequest line = receiptLine(productId.toString(), new BigDecimal("3"), 1_000L, null);
        line.setSerialNumbers(List.of("SN-ONLY-1"));
        request.setLines(List.of(line));

        assertThatThrownBy(() -> asnService.createGoodsReceipt(request, ACTOR))
                .isInstanceOf(com.positivity.inventory.internal.exception.SerialCountMismatchException.class);
        assertThat(ledgerEntriesFor(productId)).isEmpty();
    }

    // ─── read API ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /lots filters by stockItemId/status/lotNumber; GET /lots/{id} serves per-location on-hand")
    void lotReadApi_listAndDetail() throws Exception {
        UUID productId = seedProduct("LOT");
        UUID locationId = UUID.randomUUID();
        UUID po = createApprovedPo(productId, 20, 1_000L);

        CreateGoodsReceiptRequest receipt = receiptRequest(po, locationId);
        receipt.setLines(List.of(
                receiptLine(productId.toString(), new BigDecimal("6"), 1_000L, "LOT-API-A"),
                receiptLine(productId.toString(), new BigDecimal("8"), 1_000L, "LOT-API-B")));
        asnService.createGoodsReceipt(receipt, ACTOR);

        mockMvc.perform(withGatewayAuth(get("/v1/inventory/lots").param("stockItemId", productId.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        mockMvc.perform(withGatewayAuth(get("/v1/inventory/lots")
                        .param("stockItemId", productId.toString())
                        .param("lotNumber", "LOT-API-B")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].lotNumber").value("LOT-API-B"))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"));

        mockMvc.perform(withGatewayAuth(get("/v1/inventory/lots")
                        .param("stockItemId", productId.toString())
                        .param("status", "QUARANTINED")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        UUID lotAId = lotRepository
                .findByStockItemIdAndLotNumber(productId.toString(), "LOT-API-A")
                .orElseThrow()
                .getLotId();
        mockMvc.perform(withGatewayAuth(get("/v1/inventory/lots/{lotId}", lotAId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lot.lotNumber").value("LOT-API-A"))
                .andExpect(jsonPath("$.locations.length()").value(1))
                .andExpect(jsonPath("$.locations[0].locationId").value(locationId.toString()))
                .andExpect(jsonPath("$.locations[0].onHand").value(6));

        mockMvc.perform(withGatewayAuth(get("/v1/inventory/lots/{lotId}", UUID.randomUUID())))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("lot endpoints require inventory:on_hand:view (DECISION-INVENTORY-011)")
    void lotReadApi_requiresOnHandView() throws Exception {
        mockMvc.perform(withCreateOnlyAuth(get("/v1/inventory/lots"))).andExpect(status().isForbidden());
        mockMvc.perform(withCreateOnlyAuth(get("/v1/inventory/lots/{lotId}", UUID.randomUUID())))
                .andExpect(status().isForbidden());
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

    /**
     * States an approved order a receipt can be recorded against (CAP-320 #1334).
     *
     * <p>The order itself is placed in pos-order; what matters here is what this module knows
     * about it, which is its projection.
     */
    private UUID createApprovedPo(UUID skuId, int quantity, long unitCostMinor) {
        return projection.projectReceivable(
                "APPROVED", UUID.randomUUID(), skuId, String.valueOf(quantity), quantity * unitCostMinor);
    }

    private CreateGoodsReceiptRequest receiptRequest(UUID poId, UUID locationId) {
        CreateGoodsReceiptRequest request = new CreateGoodsReceiptRequest();
        request.setPoId(poId);
        request.setLocationId(locationId);
        return request;
    }

    private CreateGoodsReceiptLineRequest receiptLine(
            String sku, BigDecimal quantityReceived, long unitCostMinor, String lotNumber) {
        CreateGoodsReceiptLineRequest line = new CreateGoodsReceiptLineRequest();
        line.setSku(sku);
        line.setQuantityReceived(quantityReceived);
        line.setUnitCostMinor(unitCostMinor);
        line.setLotNumber(lotNumber);
        return line;
    }

    private ReceivingSession seedReceivingSession(UUID productId, BigDecimal expectedQuantity) {
        ReceivingSession session = ReceivingSession.builder()
                .sourceDocumentId("PO-E1-" + UUID.randomUUID())
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

    private List<InventoryLedgerEntry> ledgerEntriesFor(UUID productId) {
        return ledgerRepository.findAll().stream()
                .filter(entry -> productId.toString().equals(entry.getStockItemId()))
                .toList();
    }
}
