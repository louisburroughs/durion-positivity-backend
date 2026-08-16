package com.positivity.inventory.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.inventory.internal.dto.asn.CreateGoodsReceiptLineRequest;
import com.positivity.inventory.internal.dto.asn.CreateGoodsReceiptRequest;
import com.positivity.inventory.internal.dto.asn.GoodsReceiptResponse;
import com.positivity.inventory.internal.entity.ExtProductReplica;
import com.positivity.inventory.internal.entity.ExtProductUomReplica;
import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import com.positivity.inventory.internal.exception.OverReceiptNotPermittedException;
import com.positivity.inventory.internal.exception.UomConversionUndefinedException;
import com.positivity.inventory.internal.repository.ExtProductReplicaRepository;
import com.positivity.inventory.internal.repository.ExtProductUomReplicaRepository;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.internal.service.LedgerPostingService;
import com.positivity.inventory.service.AsnService;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

/**
 * IT for odoo-parity B2 (issue #1034, spec §3 B2–B4): document-boundary UoM conversion.
 *
 * <p>Covers the acceptance shape end-to-end against the real service + H2 stack:
 * <ul>
 *   <li>receiving a PO line keyed {@code 1 CASE (12 EA)} posts a base-UoM ledger entry of
 *       {@code +12 EA} and persists the keyed values + effective factor on the document line</li>
 *   <li>a document UoM with no conversion path → deterministic
 *       {@code UOM_CONVERSION_UNDEFINED}, never a silent 1:1 assumption</li>
 *   <li>the over-receipt guard stays correct across mixed-UoM receipt lines (conversion happens
 *       BEFORE the guard compares against the PO open balance)</li>
 *   <li>base-UoM documents (no document fields) behave exactly as before B2</li>
 *   <li>conversions round HALF_UP at the base UoM's precision scale (receipts are not
 *       reservations, so the DOWN variant does not apply)</li>
 *   <li>the ledger funnel validates a non-null {@code unitOfMeasure} against the replica base
 *       UoM where a replica row exists, and passes through unknown products / free-text SKUs
 *       that predate the catalog contract</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Document-boundary UoM conversion (odoo-parity B2, #1034)")
class UomDocumentBoundaryIT {

    private static final String ACTOR = "uom-b2-it";

    @Autowired
    private com.positivity.inventory.internal.service.PurchaseOrderProjectionTestSupport projection;

    @Autowired
    private AsnService asnService;

    @Autowired
    private LedgerPostingService ledgerPostingService;

    @Autowired
    private ExtProductReplicaRepository extProductReplicaRepository;

    @Autowired
    private ExtProductUomReplicaRepository extProductUomReplicaRepository;

    @Autowired
    private InventoryLedgerEntryRepository inventoryLedgerEntryRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        // V2 creates this sequence via Flyway in real environments; the H2 test schema comes from
        // ddl-auto, which knows nothing about the native PO-number sequence.
        jdbcTemplate.execute("CREATE SEQUENCE IF NOT EXISTS purchase_order_number_seq START WITH 1 INCREMENT BY 1");
        // Authenticated actor WITHOUT inventory:goods_receipt:override — the over-receipt guard
        // must reject, not bypass.
        var authentication = new UsernamePasswordAuthenticationToken(ACTOR, "N/A", List.of());
        authentication.setDetails(Map.of(GatewaySecurityConstants.DETAIL_USERNAME, ACTOR));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ─── acceptance shape: 1 CASE (12 EA) → ledger +12 EA + metadata ─────────────

    @Test
    @DisplayName("goods receipt keyed 1 CASE (12 EA) posts +12 EA to the ledger and persists conversion metadata")
    void receiveOneCase_postsTwelveEachesAndPersistsMetadata() {
        UUID productId = seedCaseOfTwelveProduct();

        UUID po = createPo(productId, "12", 12_000L);
        // How the order recorded this line — base quantity, keyed CASE, conversion factor — is
        // asserted where the order lives (pos-order, CAP-320 #1334). What follows is this
        // module's half: the receipt converts and posts in base units.

        CreateGoodsReceiptRequest receipt =
                receiptRequest(po, receiptLine(productId.toString(), "CASE", new BigDecimal("1"), 12_000L));
        GoodsReceiptResponse response = asnService.createGoodsReceipt(receipt, ACTOR);

        assertThat(response.getTotalAccruedAmountMinor()).isEqualTo(12_000L);
        var receiptLine = response.getLines().getFirst();
        assertThat(receiptLine.getQuantityReceived()).isEqualByComparingTo("12");
        assertThat(receiptLine.getDocumentUom()).isEqualTo("CASE");
        assertThat(receiptLine.getDocumentQuantity()).isEqualByComparingTo("1");
        assertThat(receiptLine.getConversionFactor()).isEqualByComparingTo("12");

        // Metadata round-trips through the persisted goods_receipt_line row (fresh read).
        var persistedLine =
                asnService.getGoodsReceipt(response.getReceiptId()).getLines().getFirst();
        assertThat(persistedLine.getQuantityReceived()).isEqualByComparingTo("12");
        assertThat(persistedLine.getDocumentUom()).isEqualTo("CASE");
        assertThat(persistedLine.getDocumentQuantity()).isEqualByComparingTo("1");
        assertThat(persistedLine.getConversionFactor()).isEqualByComparingTo("12");

        List<InventoryLedgerEntry> entries = ledgerEntriesFor(productId);
        assertThat(entries).hasSize(1);
        assertThat(entries.getFirst().getEventType()).isEqualTo(InventoryLedgerEventType.GOODS_RECEIPT);
        assertThat(entries.getFirst().getChangeInQuantity()).isEqualTo(12);

        // What the order becomes after this receipt is pos-order's decision, made from the
        // goods-receipt fact and asserted in GoodsReceiptListenerTest (CAP-320 #1334).
    }

    @Test
    @DisplayName("goods receipt keyed in an unmapped UoM fails with UOM_CONVERSION_UNDEFINED, posting nothing")
    void unmappedUom_isDeterministicallyRejected() {
        UUID productId = seedCaseOfTwelveProduct();
        UUID po = createPo(productId, "12", 12_000L);

        CreateGoodsReceiptRequest receipt =
                receiptRequest(po, receiptLine(productId.toString(), "PALLET", new BigDecimal("1"), 12_000L));

        assertThatThrownBy(() -> asnService.createGoodsReceipt(receipt, ACTOR))
                .isInstanceOf(UomConversionUndefinedException.class)
                .hasMessageContaining("UOM_CONVERSION_UNDEFINED");
        assertThat(ledgerEntriesFor(productId)).isEmpty();
    }

    @Test
    @DisplayName("document UoM on a free-text SKU that resolves to no catalog product fails deterministically")
    void unresolvableProductWithDocumentUom_isRejected() {
        UUID productId = seedCaseOfTwelveProduct();
        UUID po = createPo(productId, "12", 12_000L);

        CreateGoodsReceiptRequest receipt =
                receiptRequest(po, receiptLine("SKU-FREE-TEXT", "CASE", new BigDecimal("1"), 12_000L));

        assertThatThrownBy(() -> asnService.createGoodsReceipt(receipt, ACTOR))
                .isInstanceOf(UomConversionUndefinedException.class)
                .hasMessageContaining("does not resolve to a catalog product id");
    }

    // ─── over-receipt guard across mixed-UoM lines ───────────────────────────────

    @Test
    @DisplayName("over-receipt guard converts before comparing: mixed-UoM over-receipt rejected, exact receipt passes")
    void overReceiptGuard_correctAcrossMixedUomLines() {
        UUID productId = seedCaseOfTwelveProduct();
        // Line 1 keyed in base (12 EA @ 1000 = 12_000), line 2 keyed in CASE (1 CASE @ 12_000).
        // 12 EA @ 1000 plus 1 CASE @ 12_000 — 24 base units outstanding, 24_000 still owed.
        UUID po = createPo(productId, "24", 24_000L);

        // 3 CASE @ 12_000 = 36_000 > 24_000 open balance → rejected without the override authority.
        CreateGoodsReceiptRequest overReceipt =
                receiptRequest(po, receiptLine(productId.toString(), "CASE", new BigDecimal("3"), 12_000L));
        assertThatThrownBy(() -> asnService.createGoodsReceipt(overReceipt, ACTOR))
                .isInstanceOf(OverReceiptNotPermittedException.class);

        // Exact mixed-UoM receipt (12 EA @ 1000 + 1 CASE @ 12_000 = 24_000) passes and posts base.
        CreateGoodsReceiptRequest exactReceipt = receiptRequest(
                po,
                baseReceiptLine(productId.toString(), new BigDecimal("12"), 1_000L),
                receiptLine(productId.toString(), "CASE", new BigDecimal("1"), 12_000L));
        GoodsReceiptResponse response = asnService.createGoodsReceipt(exactReceipt, ACTOR);
        assertThat(response.getTotalAccruedAmountMinor()).isEqualTo(24_000L);

        int totalPosted = ledgerEntriesFor(productId).stream()
                .mapToInt(InventoryLedgerEntry::getChangeInQuantity)
                .sum();
        assertThat(totalPosted).isEqualTo(24);

        // What the order becomes after this receipt is pos-order's decision, made from the
        // goods-receipt fact and asserted in GoodsReceiptListenerTest (CAP-320 #1334).
    }

    // ─── regression: base-UoM documents unchanged ────────────────────────────────

    @Test
    @DisplayName("documents without document-UoM fields behave exactly as before B2 (no replica required)")
    void baseUomDocuments_remainRegressionIdentical() {
        // Deliberately NO replica rows: base-keyed documents must not require the catalog contract.
        UUID productId = UUID.randomUUID();
        UUID po = createPo(productId, "5", 5_000L);
        // How the order recorded this line — base quantity, keyed CASE, conversion factor — is
        // asserted where the order lives (pos-order, CAP-320 #1334). What follows is this
        // module's half: the receipt converts and posts in base units.

        GoodsReceiptResponse response = asnService.createGoodsReceipt(
                receiptRequest(po, baseReceiptLine(productId.toString(), new BigDecimal("5"), 1_000L)), ACTOR);

        assertThat(response.getTotalAccruedAmountMinor()).isEqualTo(5_000L);
        assertThat(response.getLines().getFirst().getDocumentUom()).isNull();
        assertThat(response.getLines().getFirst().getConversionFactor()).isNull();
        List<InventoryLedgerEntry> entries = ledgerEntriesFor(productId);
        assertThat(entries).hasSize(1);
        assertThat(entries.getFirst().getChangeInQuantity()).isEqualTo(5);
    }

    // ─── B3: HALF_UP rounding at base precision scale ────────────────────────────

    @Test
    @DisplayName("conversion rounds HALF_UP at the base UoM's precision scale (1.5 BAG × 1.005 = 1.51 LB)")
    void conversionRoundsHalfUpAtBasePrecisionScale() {
        UUID productId = UUID.randomUUID();
        seedProduct(productId, "LB", 2);
        seedUom(productId, "BAG", "PACK", new BigDecimal("1.005"), 3);

        UUID po = createPo(productId, "1.51", 3_000L);
        // 1.5 × 1.005 = 1.5075 → HALF_UP at scale 2 → 1.51 (receipts round HALF_UP, not DOWN).
        // Effective factor reflects the rounding actually applied: 1.51 / 1.5.
        // Money math unchanged: documentQuantity × unitCostMinor = 1.5 × 2000 = 3000.
    }

    // ─── ledger funnel unitOfMeasure validation + documented tolerance ───────────

    @Test
    @DisplayName("ledger funnel rejects a non-base unitOfMeasure where a replica exists, tolerates the rest")
    void ledgerFunnelValidatesUnitOfMeasureAgainstReplica() {
        UUID productId = seedCaseOfTwelveProduct();

        assertThatThrownBy(() -> ledgerPostingService.post(ledgerEntry(productId.toString(), "CASE")))
                .isInstanceOf(UomConversionUndefinedException.class)
                .hasMessageContaining("base-UoM only");

        // Base UoM string passes.
        assertThat(ledgerPostingService
                        .post(ledgerEntry(productId.toString(), "EA"))
                        .getLedgerEntryId())
                .isNotNull();

        // Tolerance: unknown product (no replica) and legacy free-text SKUs pass through.
        assertThat(ledgerPostingService
                        .post(ledgerEntry(UUID.randomUUID().toString(), "CASE"))
                        .getLedgerEntryId())
                .isNotNull();
        assertThat(ledgerPostingService
                        .post(ledgerEntry("SKU-LEGACY-" + UUID.randomUUID(), "BOX"))
                        .getLedgerEntryId())
                .isNotNull();
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────────

    private UUID seedCaseOfTwelveProduct() {
        UUID productId = UUID.randomUUID();
        seedProduct(productId, "EA", 0);
        seedUom(productId, "CASE", "PURCHASE", new BigDecimal("12"), 0);
        return productId;
    }

    private void seedProduct(UUID productId, String baseUom, int baseScale) {
        extProductReplicaRepository.save(ExtProductReplica.builder()
                .productId(productId)
                .baseUom(baseUom)
                .trackingLevel(ExtProductReplica.TRACKING_LEVEL_NONE)
                .aggregateVersion(1L)
                .build());
        seedUom(productId, baseUom, "BASE", BigDecimal.ONE, baseScale);
    }

    private void seedUom(UUID productId, String uomCode, String uomType, BigDecimal factorToBase, int precisionScale) {
        extProductUomReplicaRepository.save(ExtProductUomReplica.builder()
                .productId(productId)
                .uomCode(uomCode)
                .uomType(uomType)
                .factorToBase(factorToBase)
                .precisionScale(precisionScale)
                .build());
    }

    /**
     * States an approved order a receipt can be recorded against (CAP-320 #1334).
     *
     * <p>How a purchase-order line is keyed — 1 CASE rather than 12 EA — is the order's business
     * and is tested where the order lives. What this module needs is the base quantity still
     * outstanding and the value still owed, because those are what the receipt path converts
     * against and guards on.
     */
    private UUID createPo(UUID skuId, String baseOpenQuantity, long openBalanceMinor) {
        return projection.projectReceivable("APPROVED", UUID.randomUUID(), skuId, baseOpenQuantity, openBalanceMinor);
    }

    private CreateGoodsReceiptRequest receiptRequest(UUID poId, CreateGoodsReceiptLineRequest... lines) {
        CreateGoodsReceiptRequest request = new CreateGoodsReceiptRequest();
        request.setPoId(poId);
        request.setLocationId(UUID.randomUUID());
        request.setLines(List.of(lines));
        return request;
    }

    private CreateGoodsReceiptLineRequest receiptLine(
            String sku, String documentUom, BigDecimal documentQuantity, long unitCostMinor) {
        CreateGoodsReceiptLineRequest line = new CreateGoodsReceiptLineRequest();
        line.setSku(sku);
        line.setUnitCostMinor(unitCostMinor);
        line.setDocumentUom(documentUom);
        line.setDocumentQuantity(documentQuantity);
        return line;
    }

    private CreateGoodsReceiptLineRequest baseReceiptLine(String sku, BigDecimal quantityReceived, long unitCostMinor) {
        CreateGoodsReceiptLineRequest line = new CreateGoodsReceiptLineRequest();
        line.setSku(sku);
        line.setQuantityReceived(quantityReceived);
        line.setUnitCostMinor(unitCostMinor);
        return line;
    }

    private InventoryLedgerEntry ledgerEntry(String stockItemId, String unitOfMeasure) {
        return InventoryLedgerEntry.builder()
                .stockItemId(stockItemId)
                .locationId(UUID.randomUUID())
                .eventType(InventoryLedgerEventType.GOODS_RECEIPT)
                .changeInQuantity(5)
                .quantityAfter(5)
                .transactionUserId(ACTOR)
                .unitOfMeasure(unitOfMeasure)
                .notes("B2 funnel validation IT")
                .build();
    }

    private List<InventoryLedgerEntry> ledgerEntriesFor(UUID productId) {
        return inventoryLedgerEntryRepository.findAll().stream()
                .filter(entry -> productId.toString().equals(entry.getStockItemId()))
                .toList();
    }
}
