package com.positivity.inventory.internal.tracking.service;

import com.positivity.inventory.internal.dto.traceability.LotTraceabilityResponse;
import com.positivity.inventory.internal.dto.traceability.SerialTraceabilityResponse;
import com.positivity.inventory.internal.dto.traceability.TraceabilityDocumentRef;
import com.positivity.inventory.internal.dto.traceability.TraceabilityMovement;
import com.positivity.inventory.internal.dto.traceability.TraceabilityUpstream;
import com.positivity.inventory.internal.dto.traceability.WarrantyHoldRef;
import com.positivity.inventory.internal.entity.AdvanceShippingNoticeEntity;
import com.positivity.inventory.internal.entity.ExtPurchaseOrderReplica;
import com.positivity.inventory.internal.entity.GoodsReceiptEntity;
import com.positivity.inventory.internal.entity.GoodsReceiptLineEntity;
import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.entity.InventoryLot;
import com.positivity.inventory.internal.entity.InventorySerialUnit;
import com.positivity.inventory.internal.entity.WarrantyPartReturnHold;
import com.positivity.inventory.internal.exception.ResourceNotFoundException;
import com.positivity.inventory.internal.repository.ExtPurchaseOrderRepository;
import com.positivity.inventory.internal.repository.GoodsReceiptLineRepository;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.internal.repository.InventoryLotRepository;
import com.positivity.inventory.internal.repository.InventorySerialUnitRepository;
import com.positivity.inventory.internal.repository.WarrantyPartReturnHoldRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Assembles lot/serial traceability (odoo-parity E5, issue #1051) as a read over the append-only
 * ledger and the lot/serial linkages. Upstream resolves the PO/ASN/goods-receipt origin from the
 * goods-receipt lines that match the lot's (sku, lotNumber); downstream reconstructs the movement
 * chain from the lot-tagged ledger entries (chronological), or, for a serial, from its receipt and
 * consumption ledger linkages plus any warranty hold joined by serial number.
 *
 * <p>Read-only: no {@code @EmitEvent} (the {@code inventory:ledger:view} read precedent, mirroring
 * {@link InventorySerialUnitServiceImpl} and the ledger controller). The per-entity chain is
 * bounded, so it is returned complete rather than cursor-paginated.
 */
@Service
@RequiredArgsConstructor
public class InventoryTraceabilityServiceImpl implements InventoryTraceabilityService {

    private static final String DOC_PURCHASE_ORDER = "PURCHASE_ORDER";
    private static final String DOC_ASN = "ASN";
    private static final String DOC_GOODS_RECEIPT = "GOODS_RECEIPT";

    private final InventoryLotRepository lotRepository;
    private final ExtPurchaseOrderRepository extPurchaseOrderRepository;
    private final InventorySerialUnitRepository serialRepository;
    private final InventoryLedgerEntryRepository ledgerRepository;
    private final GoodsReceiptLineRepository goodsReceiptLineRepository;
    private final WarrantyPartReturnHoldRepository warrantyPartReturnHoldRepository;

    @Override
    @Transactional(readOnly = true)
    public @NonNull LotTraceabilityResponse getLotTraceability(@NonNull UUID lotId) {
        InventoryLot lot = lotRepository
                .findById(lotId)
                .orElseThrow(() -> new ResourceNotFoundException("InventoryLot", lotId.toString()));

        List<TraceabilityMovement> downstream =
                ledgerRepository.findByLotIdOrderByTimestampAscLedgerEntryIdAsc(lotId).stream()
                        .map(this::toMovement)
                        .toList();

        TraceabilityUpstream upstream = TraceabilityUpstream.builder()
                .vendorId(lot.getVendorId())
                .receivedAt(lot.getReceivedAt())
                .documents(resolveDocuments(lot.getStockItemId(), lot.getLotNumber()))
                .build();

        return LotTraceabilityResponse.builder()
                .lotId(lot.getLotId())
                .stockItemId(lot.getStockItemId())
                .lotNumber(lot.getLotNumber())
                .lotStatus(lot.getStatus())
                .upstream(upstream)
                .downstream(downstream)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull SerialTraceabilityResponse getSerialTraceability(@NonNull UUID serialUnitId) {
        InventorySerialUnit unit = serialRepository
                .findById(serialUnitId)
                .orElseThrow(() -> new ResourceNotFoundException("InventorySerialUnit", serialUnitId.toString()));

        // Upstream origin: the serial's own receipt time, enriched with vendor/documents from its
        // lot when lot-linked (a SERIAL unit may also belong to a lot).
        InventoryLot lot = unit.getLotId() == null
                ? null
                : lotRepository.findById(unit.getLotId()).orElse(null);
        TraceabilityUpstream upstream = TraceabilityUpstream.builder()
                .vendorId(lot == null ? null : lot.getVendorId())
                .receivedAt(unit.getReceivedAt())
                .documents(lot == null ? List.of() : resolveDocuments(lot.getStockItemId(), lot.getLotNumber()))
                .build();

        // Downstream chain: the serial's receipt and consumption ledger linkages, chronologically.
        List<UUID> ledgerIds = new ArrayList<>(2);
        if (unit.getReceiptLedgerEntryId() != null) {
            ledgerIds.add(unit.getReceiptLedgerEntryId());
        }
        if (unit.getConsumptionLedgerEntryId() != null) {
            ledgerIds.add(unit.getConsumptionLedgerEntryId());
        }
        List<TraceabilityMovement> downstream = ledgerRepository.findAllById(ledgerIds).stream()
                .sorted(Comparator.comparing(
                                InventoryLedgerEntry::getTimestamp, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(InventoryLedgerEntry::getLedgerEntryId))
                .map(this::toMovement)
                .toList();

        List<WarrantyHoldRef> warrantyHolds =
                warrantyPartReturnHoldRepository.findBySerialNumber(unit.getSerialNumber()).stream()
                        .sorted(Comparator.comparing(
                                WarrantyPartReturnHold::getRequestedAt,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                        .map(this::toWarrantyHold)
                        .toList();

        return SerialTraceabilityResponse.builder()
                .serialUnitId(unit.getSerialUnitId())
                .stockItemId(unit.getStockItemId())
                .serialNumber(unit.getSerialNumber())
                .serialStatus(unit.getStatus())
                .lotId(unit.getLotId())
                .workorderId(unit.getWorkorderId())
                .upstream(upstream)
                .downstream(downstream)
                .warrantyHolds(warrantyHolds)
                .build();
    }

    /**
     * Resolves the PO/ASN/goods-receipt origin documents for a lot's (sku, lotNumber): each goods
     * receipt line that received this lot contributes its purchase order, ASN (when present), and
     * the goods receipt itself, deduplicated and PO→ASN→receipt ordered per receipt.
     */
    private List<TraceabilityDocumentRef> resolveDocuments(String stockItemId, String lotNumber) {
        if (stockItemId == null || lotNumber == null || lotNumber.isBlank()) {
            return List.of();
        }
        Map<String, TraceabilityDocumentRef> documents = new LinkedHashMap<>();
        goodsReceiptLineRepository.findBySkuAndLotNumber(stockItemId, lotNumber).stream()
                .sorted(Comparator.comparing(
                        line -> receiptSortKey(line), Comparator.nullsLast(Comparator.naturalOrder())))
                .forEach(line -> collectDocuments(line, documents));
        return List.copyOf(documents.values());
    }

    private static String receiptSortKey(GoodsReceiptLineEntity line) {
        GoodsReceiptEntity receipt = line.getGoodsReceipt();
        if (receipt == null || receipt.getReceiptId() == null) {
            return null;
        }
        return receipt.getReceiptId().toString();
    }

    private void collectDocuments(GoodsReceiptLineEntity line, Map<String, TraceabilityDocumentRef> documents) {
        GoodsReceiptEntity receipt = line.getGoodsReceipt();
        if (receipt == null) {
            return;
        }
        UUID purchaseOrderId = receipt.getPurchaseOrderId();
        if (purchaseOrderId != null) {
            // The PO number is the order's to state, so it comes from the projection. When the
            // replica has not caught up the trace still names the order by id rather than dropping
            // it: a document reference with no human-readable number is far less misleading than a
            // trace that omits the purchase order entirely.
            String poNumber = extPurchaseOrderRepository
                    .findById(purchaseOrderId)
                    .map(ExtPurchaseOrderReplica::getPoNumber)
                    .orElse(null);
            putDocument(documents, DOC_PURCHASE_ORDER, purchaseOrderId, poNumber);
        }
        AdvanceShippingNoticeEntity asn = receipt.getAsn();
        if (asn != null && asn.getAsnId() != null) {
            putDocument(documents, DOC_ASN, asn.getAsnId(), asn.getAsnReferenceNumber());
        }
        if (receipt.getReceiptId() != null) {
            putDocument(documents, DOC_GOODS_RECEIPT, receipt.getReceiptId(), receipt.getReceiptNumber());
        }
    }

    private void putDocument(Map<String, TraceabilityDocumentRef> documents, String type, UUID id, String reference) {
        documents.computeIfAbsent(
                type + "|" + id,
                key -> TraceabilityDocumentRef.builder()
                        .documentType(type)
                        .documentId(id)
                        .reference(reference)
                        .build());
    }

    private TraceabilityMovement toMovement(InventoryLedgerEntry entry) {
        return TraceabilityMovement.builder()
                .ledgerEntryId(entry.getLedgerEntryId())
                .eventType(entry.getEventType())
                .timestamp(entry.getTimestamp())
                .changeInQuantity(entry.getChangeInQuantity())
                .quantityAfter(entry.getQuantityAfter())
                .locationId(entry.getLocationId())
                .fromLocationId(entry.getFromLocationId())
                .toLocationId(entry.getToLocationId())
                .lotId(entry.getLotId())
                .sourceTransactionId(entry.getSourceTransactionId())
                .notes(entry.getNotes())
                .build();
    }

    private WarrantyHoldRef toWarrantyHold(WarrantyPartReturnHold hold) {
        return WarrantyHoldRef.builder()
                .partReturnId(hold.getPartReturnId())
                .claimId(hold.getClaimId())
                .status(hold.getStatus())
                .disposition(hold.getDisposition())
                .requestedAt(hold.getRequestedAt())
                .shippedAt(hold.getShippedAt())
                .build();
    }
}
