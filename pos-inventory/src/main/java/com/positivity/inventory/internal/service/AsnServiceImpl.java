package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.dto.asn.AsnLineResponse;
import com.positivity.inventory.internal.dto.asn.AsnResponse;
import com.positivity.inventory.internal.dto.asn.CreateAsnRequest;
import com.positivity.inventory.internal.dto.asn.CreateGoodsReceiptLineRequest;
import com.positivity.inventory.internal.dto.asn.CreateGoodsReceiptRequest;
import com.positivity.inventory.internal.dto.asn.GoodsReceiptLineResponse;
import com.positivity.inventory.internal.dto.asn.GoodsReceiptResponse;
import com.positivity.inventory.internal.entity.AdvanceShippingNoticeEntity;
import com.positivity.inventory.internal.entity.AsnLineEntity;
import com.positivity.inventory.internal.entity.GoodsReceiptEntity;
import com.positivity.inventory.internal.entity.GoodsReceiptLineEntity;
import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.entity.PurchaseOrderEntity;
import com.positivity.inventory.internal.enums.AsnStatus;
import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import com.positivity.inventory.internal.enums.PurchaseOrderStatus;
import com.positivity.inventory.internal.exception.DuplicateAsnException;
import com.positivity.inventory.internal.exception.InvalidPoReferenceException;
import com.positivity.inventory.internal.exception.OverReceiptNotPermittedException;
import com.positivity.inventory.internal.exception.ResourceNotFoundException;
import com.positivity.inventory.internal.repository.AsnLineRepository;
import com.positivity.inventory.internal.repository.AsnRepository;
import com.positivity.inventory.internal.repository.GoodsReceiptRepository;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.internal.repository.PurchaseOrderRepository;
import com.positivity.inventory.service.AsnService;
import com.positivity.security.common.SecurityContextHelper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AsnServiceImpl implements AsnService {

    private final AsnRepository asnRepository;
    private final AsnLineRepository asnLineRepository;
    private final GoodsReceiptRepository goodsReceiptRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final InventoryLedgerEntryRepository inventoryLedgerEntryRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    @Transactional
    public @NonNull AsnResponse createAsn(@NonNull CreateAsnRequest request, @NonNull String actorId) {
        for (UUID poId : request.getRelatedPoIds()) {
            PurchaseOrderEntity purchaseOrder = purchaseOrderRepository.findById(poId)
                    .orElseThrow(() -> new InvalidPoReferenceException(
                            "INVALID_PO_REFERENCE: PO " + poId + " is unknown or not APPROVED"));
            if (purchaseOrder.getStatus() != PurchaseOrderStatus.APPROVED) {
                throw new InvalidPoReferenceException(
                        "INVALID_PO_REFERENCE: PO " + poId + " is unknown or not APPROVED");
            }
        }

        asnRepository.findByVendorIdAndAsnReferenceNumber(request.getVendorId(), request.getAsnReferenceNumber())
                .ifPresent(existing -> {
                    throw new DuplicateAsnException("DUPLICATE_ASN: ASN with this reference already exists");
                });

        AdvanceShippingNoticeEntity asnEntity = AdvanceShippingNoticeEntity.builder()
                .asnReferenceNumber(request.getAsnReferenceNumber())
                .vendorId(request.getVendorId())
                .status(AsnStatus.LOADED)
                .poId(request.getRelatedPoIds().get(0))
                .shipDate(request.getShipDate())
                .expectedArrivalDate(request.getExpectedArrivalDate())
                .build();
        AdvanceShippingNoticeEntity savedAsn = asnRepository.save(asnEntity);
        AdvanceShippingNoticeEntity savedAsnRef = savedAsn;

        List<AsnLineEntity> lineEntities = request.getLineItems().stream()
                .map(line -> AsnLineEntity.builder()
                        .asn(savedAsnRef)
                        .poId(line.getPoId())
                        .poLineId(line.getPoLineId())
                        .sku(line.getSku())
                        .quantityShipped(line.getQuantityShipped())
                        .quantityReceived(BigDecimal.ZERO)
                        .unitOfMeasure(line.getUnitOfMeasure())
                        .unitCostMinor(line.getUnitCostMinor())
                        .lotNumber(line.getLotNumber())
                        .build())
                .toList();
        List<AsnLineEntity> savedLines = asnLineRepository.saveAll(lineEntities);
        savedAsnRef.setLines(savedLines);
        AdvanceShippingNoticeEntity persistedAsn = savedAsnRef;

        eventPublisher.publishEvent(Map.of(
                "type", "ASNLoaded",
                "eventType", "ASNLoaded",
                "asnId", persistedAsn.getAsnId().toString(),
                "vendorId", persistedAsn.getVendorId().toString(),
                "actorId", actorId,
                "occurredAt", Instant.now().toString()));

        return toAsnResponse(persistedAsn);
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull AsnResponse getAsn(@NonNull UUID asnId) {
        AdvanceShippingNoticeEntity entity = asnRepository.findById(asnId)
                .orElseThrow(() -> new ResourceNotFoundException("Asn", asnId.toString()));
        return toAsnResponse(entity);
    }

    @Override
    @Transactional
    public @NonNull GoodsReceiptResponse createGoodsReceipt(
            @NonNull CreateGoodsReceiptRequest request,
            @NonNull String actorId) {
        AdvanceShippingNoticeEntity asn = null;
        if (request.getAsnId() != null) {
            asn = asnRepository.findById(request.getAsnId())
                    .orElseThrow(() -> new ResourceNotFoundException("Asn", request.getAsnId().toString()));
        }

        UUID poId = asn != null
                ? (asn.getPoId() != null
                        ? asn.getPoId()
                        : asn.getLines().stream().findFirst().map(AsnLineEntity::getPoId).orElse(request.getPoId()))
                : request.getPoId();

        PurchaseOrderEntity purchaseOrder = purchaseOrderRepository.findById(poId)
                .orElseThrow(() -> new InvalidPoReferenceException(
                        "INVALID_PO_REFERENCE: PO " + poId + " is unknown or not APPROVED"));
        if (purchaseOrder.getStatus() != PurchaseOrderStatus.APPROVED
                && purchaseOrder.getStatus() != PurchaseOrderStatus.PARTIALLY_RECEIVED) {
            throw new InvalidPoReferenceException(
                    "INVALID_PO_REFERENCE: PO " + poId + " is unknown or not APPROVED");
        }

        long receiptTotalMinor = request.getLines().stream()
                .mapToLong(this::lineAccruedAmountMinor)
                .sum();
        long currentOpenBalance = safeLong(purchaseOrder.getOpenBalanceMinor());
        if (receiptTotalMinor > currentOpenBalance
                && !SecurityContextHelper.hasAuthority("inventory:goods_receipt:override")) {
            throw new OverReceiptNotPermittedException("OVER_RECEIPT_NOT_PERMITTED");
        }

        GoodsReceiptEntity receiptEntity = GoodsReceiptEntity.builder()
                .receiptNumber(generateReceiptNumber())
                .poId(poId)
                .asnId(asn != null ? asn.getAsnId() : request.getAsnId())
                .locationId(request.getLocationId())
                .totalAccruedAmountMinor(receiptTotalMinor)
                .build();

        List<GoodsReceiptLineEntity> lineEntities = new ArrayList<>();
        for (CreateGoodsReceiptLineRequest line : request.getLines()) {
            long lineAccruedMinor = lineAccruedAmountMinor(line);
            GoodsReceiptLineEntity receiptLine = GoodsReceiptLineEntity.builder()
                    .goodsReceipt(receiptEntity)
                    .poLineId(line.getPoLineId())
                    .sku(line.getSku())
                    .quantityReceived(line.getQuantityReceived())
                    .unitCostMinor(line.getUnitCostMinor())
                    .lineAccruedAmountMinor(lineAccruedMinor)
                    .lotNumber(line.getLotNumber())
                    .build();
            lineEntities.add(receiptLine);
        }

        receiptEntity.setLines(lineEntities);
        GoodsReceiptEntity persistedReceipt = goodsReceiptRepository.save(receiptEntity);

        eventPublisher.publishEvent(Map.of(
                "type", "ReceiptCreated",
                "eventType", "ReceiptCreated",
                "receiptId", persistedReceipt.getReceiptId().toString(),
                "poId", poId.toString(),
                "asnId", persistedReceipt.getAsnId() == null ? "" : persistedReceipt.getAsnId().toString(),
                "lineItems", request.getLines().stream()
                        .map(line -> Map.of(
                                "sku", line.getSku(),
                                "quantityReceived", line.getQuantityReceived().toPlainString(),
                                "unitCostMinor", line.getUnitCostMinor() == null ? 0L : line.getUnitCostMinor()))
                        .toList(),
                "createdBy", actorId,
                "occurredAt", Instant.now().toString()));

        for (CreateGoodsReceiptLineRequest line : request.getLines()) {
            InventoryLedgerEntry entry = InventoryLedgerEntry.builder()
                    .stockItemId(line.getSku())
                    .locationId(request.getLocationId())
                    .toLocationId(request.getLocationId())
                    .eventType(InventoryLedgerEventType.GOODS_RECEIPT)
                    .changeInQuantity(toWholeQuantity(line.getQuantityReceived()))
                    .quantityAfter(
                            calculateQuantityAfter(line.getSku(), request.getLocationId(), line.getQuantityReceived()))
                    .transactionUserId(actorId)
                    .sourceTransactionId(persistedReceipt.getReceiptId().toString())
                    .notes("Goods receipt " + persistedReceipt.getReceiptNumber())
                    .build();
            inventoryLedgerEntryRepository.save(entry);
        }

        long nextOpenBalance = Math.max(0L, currentOpenBalance - receiptTotalMinor);
        purchaseOrder.setOpenBalanceMinor(nextOpenBalance);
        purchaseOrder.setStatus(
                nextOpenBalance == 0L ? PurchaseOrderStatus.FULLY_RECEIVED : PurchaseOrderStatus.PARTIALLY_RECEIVED);
        purchaseOrderRepository.save(purchaseOrder);

        if (asn != null) {
            applyReceiptToAsn(asn, request.getLines());
            asnRepository.save(asn);
        }

        eventPublisher.publishEvent(Map.of(
                "type", "ReceiptCompleted",
                "eventType", "ReceiptCompleted",
                "receiptId", persistedReceipt.getReceiptId().toString(),
                "poId", poId.toString(),
                "asnId", persistedReceipt.getAsnId() == null ? "" : persistedReceipt.getAsnId().toString(),
                "totalAccruedAmountMinor", receiptTotalMinor,
                "actorId", actorId,
                "occurredAt", Instant.now().toString()));

        return toGoodsReceiptResponse(persistedReceipt);
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull GoodsReceiptResponse getGoodsReceipt(@NonNull UUID receiptId) {
        GoodsReceiptEntity entity = goodsReceiptRepository.findById(receiptId)
                .orElseThrow(() -> new ResourceNotFoundException("GoodsReceipt", receiptId.toString()));
        return toGoodsReceiptResponse(entity);
    }

    private void applyReceiptToAsn(
            @NonNull AdvanceShippingNoticeEntity asn,
            @NonNull List<CreateGoodsReceiptLineRequest> lines) {
        Map<String, BigDecimal> receiptBySku = new HashMap<>();
        for (CreateGoodsReceiptLineRequest line : lines) {
            receiptBySku.merge(
                    line.getSku(),
                    line.getQuantityReceived(),
                    BigDecimal::add);
        }

        for (AsnLineEntity asnLine : asn.getLines()) {
            BigDecimal currentReceived = asnLine.getQuantityReceived() == null ? BigDecimal.ZERO
                    : asnLine.getQuantityReceived();
            BigDecimal increment = receiptBySku.getOrDefault(asnLine.getSku(), BigDecimal.ZERO);
            asnLine.setQuantityReceived(currentReceived.add(increment));
        }

        boolean allReceived = asn.getLines().stream()
                .allMatch(line -> {
                    BigDecimal shipped = line.getQuantityShipped() == null ? BigDecimal.ZERO
                            : line.getQuantityShipped();
                    BigDecimal received = line.getQuantityReceived() == null ? BigDecimal.ZERO
                            : line.getQuantityReceived();
                    return received.compareTo(shipped) >= 0;
                });
        asn.setStatus(allReceived ? AsnStatus.FULLY_RECEIVED : AsnStatus.PARTIALLY_RECEIVED);
    }

    private long lineAccruedAmountMinor(@NonNull CreateGoodsReceiptLineRequest line) {
        return line.getQuantityReceived()
                .multiply(BigDecimal.valueOf(line.getUnitCostMinor()))
                .setScale(0, RoundingMode.HALF_EVEN)
                .longValue();
    }

    private int toWholeQuantity(@NonNull BigDecimal quantity) {
        try {
            return quantity.intValueExact();
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException("quantity must be a whole number", ex);
        }
    }

    private int calculateQuantityAfter(@NonNull String stockItemId, @NonNull UUID locationId,
            @NonNull BigDecimal quantityDelta) {
        Integer onHand = inventoryLedgerEntryRepository.calculateOnHandQuantityAtLocation(stockItemId, locationId);
        int current = onHand == null ? 0 : onHand;
        return current + toWholeQuantity(quantityDelta);
    }

    private long safeLong(Long value) {
        return value == null ? 0L : value;
    }

    private String generateReceiptNumber() {
        return "GR-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private @NonNull AsnResponse toAsnResponse(@NonNull AdvanceShippingNoticeEntity entity) {
        List<AsnLineResponse> lineResponses = entity.getLines().stream()
                .map(line -> AsnLineResponse.builder()
                        .asnLineId(line.getAsnLineId())
                        .poId(line.getPoId())
                        .sku(line.getSku())
                        .quantityShipped(line.getQuantityShipped())
                        .quantityReceived(line.getQuantityReceived())
                        .unitOfMeasure(line.getUnitOfMeasure())
                        .lotNumber(line.getLotNumber())
                        .build())
                .toList();

        return AsnResponse.builder()
                .asnId(entity.getAsnId())
                .asnReferenceNumber(entity.getAsnReferenceNumber())
                .vendorId(entity.getVendorId())
                .status(entity.getStatus())
                .shipDate(entity.getShipDate())
                .expectedArrivalDate(entity.getExpectedArrivalDate())
                .createdBy(entity.getCreatedBy())
                .createdAt(entity.getCreatedAt())
                .lineItems(lineResponses)
                .build();
    }

    private @NonNull GoodsReceiptResponse toGoodsReceiptResponse(@NonNull GoodsReceiptEntity entity) {
        List<GoodsReceiptLineResponse> lineResponses = entity.getLines().stream()
                .map(line -> GoodsReceiptLineResponse.builder()
                        .receiptLineId(line.getReceiptLineId())
                        .poLineId(line.getPoLineId())
                        .sku(line.getSku())
                        .quantityReceived(line.getQuantityReceived())
                        .unitCostMinor(line.getUnitCostMinor())
                        .lineAccruedAmountMinor(line.getLineAccruedAmountMinor())
                        .build())
                .toList();

        return GoodsReceiptResponse.builder()
                .receiptId(entity.getReceiptId())
                .receiptNumber(entity.getReceiptNumber())
                .poId(entity.getPoId())
                .asnId(entity.getAsnId())
                .locationId(entity.getLocationId())
                .totalAccruedAmountMinor(entity.getTotalAccruedAmountMinor())
                .createdBy(entity.getCreatedBy())
                .createdAt(entity.getCreatedAt())
                .lines(lineResponses)
                .build();
    }
}
