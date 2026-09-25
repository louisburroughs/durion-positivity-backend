package com.positivity.inventory.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.inventory.internal.dto.asn.CreateGoodsReceiptLineRequest;
import com.positivity.inventory.internal.dto.asn.CreateGoodsReceiptRequest;
import com.positivity.inventory.internal.entity.ExtProductReplica;
import com.positivity.inventory.internal.entity.ExtProductUomReplica;
import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.entity.SkuCostState;
import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import com.positivity.inventory.internal.receiving.service.AsnService;
import com.positivity.inventory.internal.repository.ExtProductReplicaRepository;
import com.positivity.inventory.internal.repository.ExtProductUomReplicaRepository;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.internal.repository.SkuCostStateRepository;
import com.positivity.inventory.internal.service.LedgerPostingService;
import com.positivity.inventory.internal.service.PurchaseOrderProjectionTestSupport;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

/**
 * Goods receipts cost what they receive (#2203, ADR-0048 IMP-002): every {@code GOODS_RECEIPT}
 * row carries the line's document unit cost per base unit, so AVERAGE gives a never-costed SKU a
 * cost, blends later receipts into it, and every later posting of the SKU is costed.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Goods receipt document cost (#2203)")
class GoodsReceiptCostingIT {

    private static final String ACTOR = "receipt-costing-it";

    @Autowired
    private PurchaseOrderProjectionTestSupport projection;

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
    private SkuCostStateRepository costStateRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        // The H2 test schema comes from ddl-auto, which knows nothing about the native sequence.
        jdbcTemplate.execute("CREATE SEQUENCE IF NOT EXISTS purchase_order_number_seq START WITH 1 INCREMENT BY 1");
        var authentication = new UsernamePasswordAuthenticationToken(ACTOR, "N/A", List.of());
        authentication.setDetails(Map.of(GatewaySecurityConstants.DETAIL_USERNAME, ACTOR));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("receiving a never-costed SKU at 1250 minor stamps 12.5000 and seeds the average")
    void receipt_newSku_stampsDocumentCostAndSeedsAverage() {
        UUID productId = seedProduct();
        UUID po = projection.projectReceivable("APPROVED", UUID.randomUUID(), productId, "10", 12_500L);

        asnService.createGoodsReceipt(receipt(po, baseLine(productId, "10", 1_250L)), ACTOR);

        List<InventoryLedgerEntry> receipts = receiptsFor(productId);
        assertThat(receipts).hasSize(1);
        assertThat(receipts.getFirst().getUnitCost()).isEqualByComparingTo("12.5000");
        assertThat(costState(productId).getAvgCost()).isEqualByComparingTo("12.5");
    }

    @Test
    @DisplayName("a second receipt at a different cost blends into the weighted average")
    void secondReceipt_blendsWeightedAverage() {
        UUID productId = seedProduct();
        UUID po = projection.projectReceivable("APPROVED", UUID.randomUUID(), productId, "40", 100_000L);

        asnService.createGoodsReceipt(receipt(po, baseLine(productId, "10", 1_000L)), ACTOR);
        asnService.createGoodsReceipt(receipt(po, baseLine(productId, "30", 2_000L)), ACTOR);

        // (10 × 10.00 + 30 × 20.00) / 40 = 17.50
        SkuCostState state = costState(productId);
        assertThat(state.getAvgCost()).isEqualByComparingTo("17.5");
        assertThat(state.getOnHandQty()).isEqualByComparingTo("40");
    }

    @Test
    @DisplayName("a receipt keyed in CASE (12 EA) stamps the per-base-unit cost")
    void caseKeyedReceipt_stampsPerBaseUnitCost() {
        UUID productId = seedProduct();
        seedUom(productId, "CASE", "PURCHASE", new BigDecimal("12"));
        UUID po = projection.projectReceivable("APPROVED", UUID.randomUUID(), productId, "12", 12_000L);

        CreateGoodsReceiptLineRequest line = new CreateGoodsReceiptLineRequest();
        line.setSku(productId.toString());
        line.setDocumentUom("CASE");
        line.setDocumentQuantity(BigDecimal.ONE);
        line.setUnitCostMinor(12_000L);
        asnService.createGoodsReceipt(receipt(po, line), ACTOR);

        InventoryLedgerEntry posted = receiptsFor(productId).getFirst();
        assertThat(posted.getChangeInQuantity()).isEqualByComparingTo("12");
        // 120.00 per case / 12 each per case
        assertThat(posted.getUnitCost()).isEqualByComparingTo("10.0000");
        assertThat(costState(productId).getAvgCost()).isEqualByComparingTo("10");
    }

    @Test
    @DisplayName(
            "after a priced receipt, later postings are costed at the average and the receipt-cost lookup finds it")
    void afterPricedReceipt_downstreamPostingsAreCosted() {
        UUID productId = seedProduct();
        UUID po = projection.projectReceivable("APPROVED", UUID.randomUUID(), productId, "10", 12_500L);
        CreateGoodsReceiptRequest request = receipt(po, baseLine(productId, "10", 1_250L));
        asnService.createGoodsReceipt(request, ACTOR);

        // A write-off after the receipt (a count variance, an approved adjustment, a scrap) is stamped
        // at the running average, so its fact carries a cost instead of costSource NONE.
        InventoryLedgerEntry writeOff = ledgerPostingService.post(InventoryLedgerEntry.builder()
                .stockItemId(productId.toString())
                .locationId(request.getLocationId())
                .fromLocationId(request.getLocationId())
                .eventType(InventoryLedgerEventType.ADJUSTMENT_OUT)
                .changeInQuantity(new BigDecimal("-2"))
                .quantityAfter(new BigDecimal("8"))
                .transactionUserId(ACTOR)
                .build());
        assertThat(writeOff.getUnitCost()).isEqualByComparingTo("12.5");

        // The scrap cost snapshot and transfer-order costing read the latest receipt cost.
        assertThat(inventoryLedgerEntryRepository.findLatestUnitCostByStockItemAndEventType(
                        productId.toString(), InventoryLedgerEventType.GOODS_RECEIPT, PageRequest.of(0, 1)))
                .singleElement()
                .satisfies(cost -> assertThat(cost).isEqualByComparingTo("12.5"));
    }

    private UUID seedProduct() {
        UUID productId = UUID.randomUUID();
        extProductReplicaRepository.save(ExtProductReplica.builder()
                .productId(productId)
                .baseUom("EA")
                .trackingLevel(ExtProductReplica.TRACKING_LEVEL_NONE)
                .aggregateVersion(1L)
                .build());
        seedUom(productId, "EA", "BASE", BigDecimal.ONE);
        return productId;
    }

    private void seedUom(UUID productId, String uomCode, String uomType, BigDecimal factorToBase) {
        extProductUomReplicaRepository.save(ExtProductUomReplica.builder()
                .productId(productId)
                .uomCode(uomCode)
                .uomType(uomType)
                .factorToBase(factorToBase)
                .precisionScale(0)
                .build());
    }

    private static CreateGoodsReceiptRequest receipt(UUID poId, CreateGoodsReceiptLineRequest line) {
        CreateGoodsReceiptRequest request = new CreateGoodsReceiptRequest();
        request.setPoId(poId);
        request.setLocationId(UUID.randomUUID());
        request.setLines(List.of(line));
        return request;
    }

    private static CreateGoodsReceiptLineRequest baseLine(UUID productId, String quantity, long unitCostMinor) {
        CreateGoodsReceiptLineRequest line = new CreateGoodsReceiptLineRequest();
        line.setSku(productId.toString());
        line.setQuantityReceived(new BigDecimal(quantity));
        line.setUnitCostMinor(unitCostMinor);
        return line;
    }

    private List<InventoryLedgerEntry> receiptsFor(UUID productId) {
        return inventoryLedgerEntryRepository.findAll().stream()
                .filter(entry -> productId.toString().equals(entry.getStockItemId()))
                .filter(entry -> entry.getEventType() == InventoryLedgerEventType.GOODS_RECEIPT)
                .toList();
    }

    private SkuCostState costState(UUID productId) {
        return costStateRepository.findByStockItemId(productId.toString()).orElseThrow();
    }
}
