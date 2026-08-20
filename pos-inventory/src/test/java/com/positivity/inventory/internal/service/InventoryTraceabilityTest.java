package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.inventory.internal.dto.traceability.LotTraceabilityResponse;
import com.positivity.inventory.internal.dto.traceability.SerialTraceabilityResponse;
import com.positivity.inventory.internal.dto.traceability.TraceabilityDocumentRef;
import com.positivity.inventory.internal.entity.AdvanceShippingNoticeEntity;
import com.positivity.inventory.internal.entity.GoodsReceiptEntity;
import com.positivity.inventory.internal.entity.GoodsReceiptLineEntity;
import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.entity.InventoryLot;
import com.positivity.inventory.internal.entity.InventorySerialUnit;
import com.positivity.inventory.internal.entity.WarrantyPartReturnHold;
import com.positivity.inventory.internal.enums.AsnStatus;
import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import com.positivity.inventory.internal.enums.InventoryLotStatus;
import com.positivity.inventory.internal.enums.InventorySerialStatus;
import com.positivity.inventory.internal.exception.ResourceNotFoundException;
import com.positivity.inventory.internal.repository.AsnRepository;
import com.positivity.inventory.internal.repository.GoodsReceiptRepository;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.internal.repository.InventoryLotRepository;
import com.positivity.inventory.internal.repository.InventorySerialUnitRepository;
import com.positivity.inventory.internal.repository.WarrantyPartReturnHoldRepository;
import com.positivity.inventory.service.InventoryTraceabilityService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * End-to-end tests for odoo-parity E5 (issue #1051): lot/serial traceability. Exercises upstream
 * PO/ASN/goods-receipt resolution, downstream movement-chain ordering over a full lifecycle
 * fixture (receive → putaway → pick/consume → partial return → scrap remainder), partial history,
 * the serial warranty-hold join, and 404 on unknown lot/serial — against the H2 stack.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Lot/serial traceability (odoo-parity E5, #1051)")
class InventoryTraceabilityTest {

    @Autowired
    private PurchaseOrderProjectionTestSupport projection;

    @Autowired
    private InventoryTraceabilityService traceabilityService;

    @Autowired
    private InventoryLotRepository lotRepository;

    @Autowired
    private InventorySerialUnitRepository serialRepository;

    @Autowired
    private InventoryLedgerEntryRepository ledgerRepository;

    @Autowired
    private GoodsReceiptRepository goodsReceiptRepository;

    @Autowired
    private AsnRepository asnRepository;

    @Autowired
    private WarrantyPartReturnHoldRepository warrantyPartReturnHoldRepository;

    private InventoryLedgerEntry saveEntry(
            String sku, UUID lotId, InventoryLedgerEventType type, int change, UUID from, UUID to, String sourceTxn) {
        return ledgerRepository.save(InventoryLedgerEntry.builder()
                .stockItemId(sku)
                .eventType(type)
                .changeInQuantity(BigDecimal.valueOf(change))
                .quantityAfter(new BigDecimal("0"))
                .lotId(lotId)
                .locationId(to != null ? to : from)
                .fromLocationId(from)
                .toLocationId(to)
                .transactionUserId("e5-test")
                .sourceTransactionId(sourceTxn)
                .notes(type.name() + " " + sourceTxn)
                .build());
    }

    /** Seeds a PO → ASN → goods-receipt chain whose receipt line matches (sku, lotNumber). */
    private GoodsReceiptEntity seedReceiptChain(String sku, String lotNumber, UUID vendorId, UUID locationId) {
        // The order is pos-order's; the trace resolves its number through the projection
        // (CAP-320 #1334), so that is what the fixture states.
        UUID poId = projection.project("APPROVED", locationId, null, UUID.randomUUID(), "10");

        AdvanceShippingNoticeEntity asn = asnRepository.save(AdvanceShippingNoticeEntity.builder()
                .asnReferenceNumber("ASN-" + UUID.randomUUID())
                .vendorId(vendorId)
                .status(AsnStatus.READY_FOR_RECEIPT)
                .purchaseOrderId(poId)
                .build());

        GoodsReceiptEntity receipt = GoodsReceiptEntity.builder()
                .receiptNumber("GR-" + UUID.randomUUID())
                .purchaseOrderId(poId)
                .asn(asn)
                .locationId(locationId)
                .build();
        GoodsReceiptLineEntity line = GoodsReceiptLineEntity.builder()
                .goodsReceipt(receipt)
                .sku(sku)
                .quantityReceived(new BigDecimal("10"))
                .unitCostMinor(100L)
                .lotNumber(lotNumber)
                .build();
        receipt.getLines().add(line);
        return goodsReceiptRepository.save(receipt);
    }

    // ─── lot: upstream resolution + full downstream chain ordering ───────────────

    @Test
    @DisplayName("Lot traceability resolves PO/ASN/receipt upstream and the ordered downstream chain")
    void lotFullLifecycle() {
        String sku = UUID.randomUUID().toString();
        String lotNumber = "LOT-" + UUID.randomUUID();
        UUID vendorId = UUID.randomUUID();
        UUID staging = UUID.randomUUID();
        UUID storage = UUID.randomUUID();

        GoodsReceiptEntity receipt = seedReceiptChain(sku, lotNumber, vendorId, staging);
        InventoryLot lot = lotRepository.save(InventoryLot.builder()
                .stockItemId(sku)
                .lotNumber(lotNumber)
                .receivedAt(Instant.parse("2026-07-01T00:00:00Z"))
                .vendorId(vendorId)
                .status(InventoryLotStatus.ACTIVE)
                .build());

        // Full lifecycle: receive → putaway (paired) → consume → partial return → scrap remainder.
        saveEntry(sku, lot.getLotId(), InventoryLedgerEventType.GOODS_RECEIPT, 10, null, staging, "GR");
        saveEntry(sku, lot.getLotId(), InventoryLedgerEventType.PUTAWAY, -10, staging, null, "PA");
        saveEntry(sku, lot.getLotId(), InventoryLedgerEventType.PUTAWAY, 10, null, storage, "PA");
        saveEntry(sku, lot.getLotId(), InventoryLedgerEventType.WORKORDER_CONSUMPTION, -6, storage, null, "WO");
        saveEntry(sku, lot.getLotId(), InventoryLedgerEventType.RETURN_TO_STOCK, 2, null, storage, "RET");
        saveEntry(sku, lot.getLotId(), InventoryLedgerEventType.SCRAP_OUT, -6, storage, null, "SCRAP");

        LotTraceabilityResponse trace = traceabilityService.getLotTraceability(lot.getLotId());

        assertThat(trace.getLotId()).isEqualTo(lot.getLotId());
        assertThat(trace.getLotNumber()).isEqualTo(lotNumber);
        assertThat(trace.getLotStatus()).isEqualTo(InventoryLotStatus.ACTIVE);

        // Upstream: vendor + receipt date + PO/ASN/goods-receipt documents in order.
        assertThat(trace.getUpstream().getVendorId()).isEqualTo(vendorId);
        assertThat(trace.getUpstream().getReceivedAt()).isEqualTo(Instant.parse("2026-07-01T00:00:00Z"));
        assertThat(trace.getUpstream().getDocuments())
                .extracting(TraceabilityDocumentRef::getDocumentType)
                .containsExactly("PURCHASE_ORDER", "ASN", "GOODS_RECEIPT");
        assertThat(trace.getUpstream().getDocuments())
                .extracting(TraceabilityDocumentRef::getDocumentId)
                .containsExactly(receipt.getPurchaseOrderId(), receipt.getAsn().getAsnId(), receipt.getReceiptId());

        // Downstream chain, oldest first, reconstructed from the lot-tagged ledger entries.
        assertThat(trace.getDownstream())
                .extracting(m -> m.getEventType())
                .containsExactly(
                        InventoryLedgerEventType.GOODS_RECEIPT,
                        InventoryLedgerEventType.PUTAWAY,
                        InventoryLedgerEventType.PUTAWAY,
                        InventoryLedgerEventType.WORKORDER_CONSUMPTION,
                        InventoryLedgerEventType.RETURN_TO_STOCK,
                        InventoryLedgerEventType.SCRAP_OUT);
        assertThat(trace.getDownstream().get(0).getSourceTransactionId()).isEqualTo("GR");
        assertThat(trace.getDownstream().get(0).getChangeInQuantity()).isEqualByComparingTo("10");
    }

    // ─── lot: partial history (receipt only, no documents) ───────────────────────

    @Test
    @DisplayName("Lot with only a receipt renders a single-movement chain and empty upstream documents")
    void lotPartialHistory() {
        String sku = UUID.randomUUID().toString();
        String lotNumber = "LOT-" + UUID.randomUUID();
        UUID location = UUID.randomUUID();

        InventoryLot lot = lotRepository.save(InventoryLot.builder()
                .stockItemId(sku)
                .lotNumber(lotNumber)
                .receivedAt(Instant.parse("2026-07-02T00:00:00Z"))
                .status(InventoryLotStatus.ACTIVE)
                .build());
        saveEntry(sku, lot.getLotId(), InventoryLedgerEventType.GOODS_RECEIPT, 5, null, location, "GR-ONLY");

        LotTraceabilityResponse trace = traceabilityService.getLotTraceability(lot.getLotId());

        assertThat(trace.getUpstream().getDocuments()).isEmpty();
        assertThat(trace.getUpstream().getVendorId()).isNull();
        assertThat(trace.getDownstream())
                .singleElement()
                .satisfies(m -> assertThat(m.getEventType()).isEqualTo(InventoryLedgerEventType.GOODS_RECEIPT));
    }

    // ─── serial: upstream via lot + receipt→issue chain + warranty hold join ─────

    @Test
    @DisplayName("Serial traceability resolves upstream via its lot, orders receipt→issue, and joins warranty holds")
    void serialFullLifecycle() {
        String sku = UUID.randomUUID().toString();
        String lotNumber = "LOT-" + UUID.randomUUID();
        String serialNumber = "SN-" + UUID.randomUUID();
        UUID vendorId = UUID.randomUUID();
        UUID location = UUID.randomUUID();
        String workorderId = "WO-" + UUID.randomUUID();

        seedReceiptChain(sku, lotNumber, vendorId, location);
        InventoryLot lot = lotRepository.save(InventoryLot.builder()
                .stockItemId(sku)
                .lotNumber(lotNumber)
                .receivedAt(Instant.parse("2026-07-03T00:00:00Z"))
                .vendorId(vendorId)
                .status(InventoryLotStatus.ACTIVE)
                .build());

        InventoryLedgerEntry receiptEntry =
                saveEntry(sku, lot.getLotId(), InventoryLedgerEventType.GOODS_RECEIPT, 1, null, location, "GR");
        InventoryLedgerEntry issueEntry =
                saveEntry(sku, lot.getLotId(), InventoryLedgerEventType.GOODS_ISSUE, -1, location, null, "WO");

        InventorySerialUnit unit = serialRepository.save(InventorySerialUnit.builder()
                .stockItemId(sku)
                .serialNumber(serialNumber)
                .status(InventorySerialStatus.ISSUED)
                .lotId(lot.getLotId())
                .receiptLedgerEntryId(receiptEntry.getLedgerEntryId())
                .consumptionLedgerEntryId(issueEntry.getLedgerEntryId())
                .workorderId(workorderId)
                .receivedAt(Instant.parse("2026-07-03T00:00:00Z"))
                .consumedAt(Instant.parse("2026-07-04T00:00:00Z"))
                .build());

        UUID partReturnId = UUID.randomUUID();
        warrantyPartReturnHoldRepository.save(WarrantyPartReturnHold.builder()
                .partReturnId(partReturnId)
                .claimId(UUID.randomUUID())
                .productEntityId(UUID.randomUUID())
                .serialNumber(serialNumber)
                .disposition("RETURN_TO_VENDOR")
                .status(WarrantyPartReturnHold.STATUS_REQUESTED)
                .aggregateVersion(1L)
                .requestedAt(Instant.parse("2026-07-05T00:00:00Z"))
                .build());

        SerialTraceabilityResponse trace = traceabilityService.getSerialTraceability(unit.getSerialUnitId());

        assertThat(trace.getSerialUnitId()).isEqualTo(unit.getSerialUnitId());
        assertThat(trace.getSerialNumber()).isEqualTo(serialNumber);
        assertThat(trace.getSerialStatus()).isEqualTo(InventorySerialStatus.ISSUED);
        assertThat(trace.getLotId()).isEqualTo(lot.getLotId());
        assertThat(trace.getWorkorderId()).isEqualTo(workorderId);

        assertThat(trace.getUpstream().getVendorId()).isEqualTo(vendorId);
        assertThat(trace.getUpstream().getDocuments())
                .extracting(TraceabilityDocumentRef::getDocumentType)
                .containsExactly("PURCHASE_ORDER", "ASN", "GOODS_RECEIPT");

        assertThat(trace.getDownstream())
                .extracting(m -> m.getEventType())
                .containsExactly(InventoryLedgerEventType.GOODS_RECEIPT, InventoryLedgerEventType.GOODS_ISSUE);

        assertThat(trace.getWarrantyHolds()).singleElement().satisfies(h -> {
            assertThat(h.getPartReturnId()).isEqualTo(partReturnId);
            assertThat(h.getStatus()).isEqualTo(WarrantyPartReturnHold.STATUS_REQUESTED);
            assertThat(h.getDisposition()).isEqualTo("RETURN_TO_VENDOR");
        });
    }

    // ─── serial: partial history (in stock, no lot, no consumption, no holds) ────

    @Test
    @DisplayName("Serial still in stock renders a receipt-only chain with no documents and no warranty holds")
    void serialPartialHistory() {
        String sku = UUID.randomUUID().toString();
        String serialNumber = "SN-" + UUID.randomUUID();
        UUID location = UUID.randomUUID();

        InventoryLedgerEntry receiptEntry =
                saveEntry(sku, null, InventoryLedgerEventType.GOODS_RECEIPT, 1, null, location, "GR");
        InventorySerialUnit unit = serialRepository.save(InventorySerialUnit.builder()
                .stockItemId(sku)
                .serialNumber(serialNumber)
                .status(InventorySerialStatus.IN_STOCK)
                .receiptLedgerEntryId(receiptEntry.getLedgerEntryId())
                .receivedAt(Instant.parse("2026-07-06T00:00:00Z"))
                .build());

        SerialTraceabilityResponse trace = traceabilityService.getSerialTraceability(unit.getSerialUnitId());

        assertThat(trace.getLotId()).isNull();
        assertThat(trace.getUpstream().getDocuments()).isEmpty();
        assertThat(trace.getUpstream().getReceivedAt()).isEqualTo(Instant.parse("2026-07-06T00:00:00Z"));
        assertThat(trace.getDownstream())
                .singleElement()
                .satisfies(m -> assertThat(m.getEventType()).isEqualTo(InventoryLedgerEventType.GOODS_RECEIPT));
        assertThat(trace.getWarrantyHolds()).isEmpty();
    }

    // ─── 404 on unknown lot / serial ─────────────────────────────────────────────

    @Test
    @DisplayName("Unknown lot and unknown serial both raise ResourceNotFoundException (→ 404)")
    void unknownSubjectsThrow() {
        assertThatThrownBy(() -> traceabilityService.getLotTraceability(UUID.randomUUID()))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> traceabilityService.getSerialTraceability(UUID.randomUUID()))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
