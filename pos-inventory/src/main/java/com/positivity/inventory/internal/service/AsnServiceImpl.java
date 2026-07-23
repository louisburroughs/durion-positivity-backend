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
import com.positivity.inventory.internal.entity.PurchaseOrderLineEntity;
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
import com.positivity.inventory.internal.repository.PurchaseOrderLineRepository;
import com.positivity.inventory.internal.repository.PurchaseOrderRepository;
import com.positivity.inventory.service.AsnService;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.shared.id.UUIDv7Generator;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
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
    private final Clock clock;
    private final AsnRepository asnRepository;
    private final AsnLineRepository asnLineRepository;
    private final GoodsReceiptRepository goodsReceiptRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseOrderLineRepository purchaseOrderLineRepository;
    private final InventoryLedgerEntryRepository inventoryLedgerEntryRepository;
    private final LedgerPostingService ledgerPostingService;
    private final InventoryFactPublisher inventoryFactPublisher;
    private final ApplicationEventPublisher eventPublisher;
    private final DocumentQuantityConverter documentQuantityConverter;

    @Override
    @Transactional
    public @NonNull AsnResponse createAsn(@NonNull CreateAsnRequest request, @NonNull String actorId) {
        for (UUID poId : request.getRelatedPoIds()) {
            PurchaseOrderEntity purchaseOrder = purchaseOrderRepository
                    .findById(poId)
                    .orElseThrow(() -> new InvalidPoReferenceException(
                            "INVALID_PO_REFERENCE: PO " + poId + " is unknown or not APPROVED"));
            if (purchaseOrder.getStatus() != PurchaseOrderStatus.APPROVED) {
                throw new InvalidPoReferenceException(
                        "INVALID_PO_REFERENCE: PO " + poId + " is unknown or not APPROVED");
            }
        }

        asnRepository
                .findByVendorIdAndAsnReferenceNumber(request.getVendorId(), request.getAsnReferenceNumber())
                .ifPresent(existing -> {
                    throw new DuplicateAsnException("DUPLICATE_ASN: ASN with this reference already exists");
                });

        AdvanceShippingNoticeEntity asnEntity = AdvanceShippingNoticeEntity.builder()
                .asnReferenceNumber(request.getAsnReferenceNumber())
                .vendorId(request.getVendorId())
                .status(AsnStatus.LOADED)
                .purchaseOrder(
                        requireApprovedPurchaseOrder(request.getRelatedPoIds().get(0)))
                .shipDate(request.getShipDate())
                .expectedArrivalDate(request.getExpectedArrivalDate())
                .build();
        AdvanceShippingNoticeEntity savedAsn = asnRepository.save(asnEntity);
        AdvanceShippingNoticeEntity savedAsnRef = savedAsn;

        List<AsnLineEntity> lineEntities = request.getLineItems().stream()
                .map(line -> {
                    PurchaseOrderLineEntity poLine = resolvePurchaseOrderLine(line.getPoLineId());
                    // odoo-parity B2 (#1034): an optional document UoM derives the base
                    // quantityShipped; the keyed values are kept for audit.
                    DocumentQuantityConverter.DocumentConversion conversion = documentQuantityConverter
                            .convertIfPresent(
                                    resolveProductId(poLine, line.getSku()),
                                    line.getSku(),
                                    line.getDocumentUom(),
                                    line.getDocumentQuantity())
                            .orElse(null);
                    BigDecimal quantityShipped =
                            conversion != null ? conversion.baseQuantity() : line.getQuantityShipped();
                    if (quantityShipped == null) {
                        throw new IllegalArgumentException(
                                "quantityShipped is required when documentUom/documentQuantity are absent");
                    }
                    return AsnLineEntity.builder()
                            .asn(savedAsnRef)
                            .purchaseOrder(requireApprovedPurchaseOrder(line.getPoId()))
                            .poLine(poLine)
                            .sku(line.getSku())
                            .quantityShipped(quantityShipped)
                            .quantityReceived(BigDecimal.ZERO)
                            .unitOfMeasure(line.getUnitOfMeasure())
                            .unitCostMinor(line.getUnitCostMinor())
                            .lotNumber(line.getLotNumber())
                            .documentUom(conversion == null ? null : conversion.documentUom())
                            .documentQuantity(conversion == null ? null : conversion.documentQuantity())
                            .conversionFactor(conversion == null ? null : conversion.conversionFactor())
                            .build();
                })
                .toList();
        List<AsnLineEntity> savedLines = asnLineRepository.saveAll(lineEntities);
        savedAsnRef.setLines(savedLines);
        AdvanceShippingNoticeEntity persistedAsn = savedAsnRef;

        eventPublisher.publishEvent(Map.of(
                "type",
                "ASNLoaded",
                "eventType",
                "ASNLoaded",
                "asnId",
                persistedAsn.getAsnId().toString(),
                "vendorId",
                persistedAsn.getVendorId().toString(),
                "actorId",
                actorId,
                "occurredAt",
                Instant.now(clock).toString()));

        return toAsnResponse(persistedAsn);
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull AsnResponse getAsn(@NonNull UUID asnId) {
        AdvanceShippingNoticeEntity entity =
                asnRepository.findById(asnId).orElseThrow(() -> new ResourceNotFoundException("Asn", asnId.toString()));
        return toAsnResponse(entity);
    }

    @Override
    @Transactional
    public @NonNull GoodsReceiptResponse createGoodsReceipt(
            @NonNull CreateGoodsReceiptRequest request, @NonNull String actorId) {
        AdvanceShippingNoticeEntity asn = null;
        if (request.getAsnId() != null) {
            asn = asnRepository
                    .findById(request.getAsnId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Asn", request.getAsnId().toString()));
        }

        UUID poId = asn != null
                ? (asn.getPurchaseOrder() != null
                        ? asn.getPurchaseOrder().getPurchaseOrderId()
                        : asn.getLines().stream()
                                .map(AsnLineEntity::getPurchaseOrder)
                                .filter(po -> po != null && po.getPurchaseOrderId() != null)
                                .map(PurchaseOrderEntity::getPurchaseOrderId)
                                .findFirst()
                                .orElse(request.getPoId()))
                : request.getPoId();

        PurchaseOrderEntity purchaseOrder = purchaseOrderRepository
                .findById(poId)
                .orElseThrow(() -> new InvalidPoReferenceException(
                        "INVALID_PO_REFERENCE: PO " + poId + " is unknown or not APPROVED"));
        if (purchaseOrder.getStatus() != PurchaseOrderStatus.APPROVED
                && purchaseOrder.getStatus() != PurchaseOrderStatus.PARTIALLY_RECEIVED) {
            throw new InvalidPoReferenceException("INVALID_PO_REFERENCE: PO " + poId + " is unknown or not APPROVED");
        }

        // odoo-parity B2 (#1034): convert optional document-UoM quantities to base BEFORE the
        // over-receipt guard and any posting; money math stays documentQuantity × unitCostMinor
        // (unitCostMinor refers to one document-UoM unit when a document UoM is keyed).
        List<ReceiptLineComputation> computedLines = computeReceiptLines(request.getLines());
        long receiptTotalMinor = computedLines.stream()
                .mapToLong(ReceiptLineComputation::lineAccruedMinor)
                .sum();
        long currentOpenBalance = safeLong(purchaseOrder.getOpenBalanceMinor());
        if (receiptTotalMinor > currentOpenBalance
                && !SecurityContextHelper.hasAuthority("inventory:goods_receipt:override")) {
            throw new OverReceiptNotPermittedException("OVER_RECEIPT_NOT_PERMITTED");
        }

        GoodsReceiptEntity receiptEntity = GoodsReceiptEntity.builder()
                .receiptNumber(generateReceiptNumber())
                .purchaseOrder(purchaseOrder)
                .asn(asn)
                .locationId(request.getLocationId())
                .totalAccruedAmountMinor(receiptTotalMinor)
                .build();

        List<GoodsReceiptLineEntity> lineEntities = new ArrayList<>();
        for (ReceiptLineComputation computed : computedLines) {
            CreateGoodsReceiptLineRequest line = computed.request();
            DocumentQuantityConverter.DocumentConversion conversion = computed.conversion();
            GoodsReceiptLineEntity receiptLine = GoodsReceiptLineEntity.builder()
                    .goodsReceipt(receiptEntity)
                    .poLine(computed.poLine())
                    .sku(line.getSku())
                    .quantityReceived(computed.baseQuantity())
                    .unitCostMinor(line.getUnitCostMinor())
                    .lineAccruedAmountMinor(computed.lineAccruedMinor())
                    .lotNumber(line.getLotNumber())
                    .documentUom(conversion == null ? null : conversion.documentUom())
                    .documentQuantity(conversion == null ? null : conversion.documentQuantity())
                    .conversionFactor(conversion == null ? null : conversion.conversionFactor())
                    .build();
            lineEntities.add(receiptLine);
        }

        receiptEntity.setLines(lineEntities);
        GoodsReceiptEntity persistedReceipt = goodsReceiptRepository.save(receiptEntity);

        eventPublisher.publishEvent(Map.of(
                "type",
                "ReceiptCreated",
                "eventType",
                "ReceiptCreated",
                "receiptId",
                persistedReceipt.getReceiptId().toString(),
                "poId",
                poId.toString(),
                "asnId",
                persistedReceipt.getAsn() == null
                        ? ""
                        : persistedReceipt.getAsn().getAsnId().toString(),
                "lineItems",
                computedLines.stream()
                        .map(computed -> Map.of(
                                "sku",
                                computed.request().getSku(),
                                "quantityReceived",
                                computed.baseQuantity().toPlainString(),
                                "unitCostMinor",
                                computed.request().getUnitCostMinor() == null
                                        ? 0L
                                        : computed.request().getUnitCostMinor()))
                        .toList(),
                "createdBy",
                actorId,
                "occurredAt",
                Instant.now(clock).toString()));

        for (ReceiptLineComputation computed : computedLines) {
            // Ledger rows stay base-UoM only (spec B2): the converted base quantity posts here.
            InventoryLedgerEntry entry = InventoryLedgerEntry.builder()
                    .stockItemId(computed.request().getSku())
                    .locationId(request.getLocationId())
                    .toLocationId(request.getLocationId())
                    .eventType(InventoryLedgerEventType.GOODS_RECEIPT)
                    .changeInQuantity(toWholeQuantity(computed.baseQuantity()))
                    .quantityAfter(calculateQuantityAfter(
                            computed.request().getSku(), request.getLocationId(), computed.baseQuantity()))
                    .transactionUserId(actorId)
                    .sourceTransactionId(persistedReceipt.getReceiptId().toString())
                    .notes("Goods receipt " + persistedReceipt.getReceiptNumber())
                    .build();
            ledgerPostingService.post(entry);
            inventoryFactPublisher.markEntry(entry);
        }

        long nextOpenBalance = Math.max(0L, currentOpenBalance - receiptTotalMinor);
        purchaseOrder.setOpenBalanceMinor(nextOpenBalance);
        purchaseOrder.setStatus(
                nextOpenBalance == 0L ? PurchaseOrderStatus.FULLY_RECEIVED : PurchaseOrderStatus.PARTIALLY_RECEIVED);
        purchaseOrderRepository.save(purchaseOrder);

        if (asn != null) {
            applyReceiptToAsn(asn, computedLines);
            asnRepository.save(asn);
        }

        eventPublisher.publishEvent(Map.of(
                "type",
                "ReceiptCompleted",
                "eventType",
                "ReceiptCompleted",
                "receiptId",
                persistedReceipt.getReceiptId().toString(),
                "poId",
                poId.toString(),
                "asnId",
                persistedReceipt.getAsn() == null
                        ? ""
                        : persistedReceipt.getAsn().getAsnId().toString(),
                "totalAccruedAmountMinor",
                receiptTotalMinor,
                "actorId",
                actorId,
                "occurredAt",
                Instant.now(clock).toString()));

        return toGoodsReceiptResponse(persistedReceipt);
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull GoodsReceiptResponse getGoodsReceipt(@NonNull UUID receiptId) {
        GoodsReceiptEntity entity = goodsReceiptRepository
                .findById(receiptId)
                .orElseThrow(() -> new ResourceNotFoundException("GoodsReceipt", receiptId.toString()));
        return toGoodsReceiptResponse(entity);
    }

    private void applyReceiptToAsn(
            @NonNull AdvanceShippingNoticeEntity asn, @NonNull List<ReceiptLineComputation> lines) {
        // Base quantities on both sides: ASN quantityShipped is stored in base UoM and the
        // receipt lines were converted to base before this comparison (odoo-parity B2, #1034).
        Map<String, BigDecimal> receiptBySku = new HashMap<>();
        for (ReceiptLineComputation line : lines) {
            receiptBySku.merge(line.request().getSku(), line.baseQuantity(), BigDecimal::add);
        }

        for (AsnLineEntity asnLine : asn.getLines()) {
            BigDecimal currentReceived =
                    asnLine.getQuantityReceived() == null ? BigDecimal.ZERO : asnLine.getQuantityReceived();
            BigDecimal increment = receiptBySku.getOrDefault(asnLine.getSku(), BigDecimal.ZERO);
            asnLine.setQuantityReceived(currentReceived.add(increment));
        }

        boolean allReceived = asn.getLines().stream().allMatch(line -> {
            BigDecimal shipped = line.getQuantityShipped() == null ? BigDecimal.ZERO : line.getQuantityShipped();
            BigDecimal received = line.getQuantityReceived() == null ? BigDecimal.ZERO : line.getQuantityReceived();
            return received.compareTo(shipped) >= 0;
        });
        asn.setStatus(allReceived ? AsnStatus.FULLY_RECEIVED : AsnStatus.PARTIALLY_RECEIVED);
    }

    /**
     * Per-line receipt derivation (odoo-parity B2, #1034): the resolved PO line, the optional
     * document-UoM conversion, the base quantity that posts to the ledger, and the accrued
     * amount computed from the costed (document-unit) quantity.
     */
    private record ReceiptLineComputation(
            CreateGoodsReceiptLineRequest request,
            PurchaseOrderLineEntity poLine,
            DocumentQuantityConverter.DocumentConversion conversion,
            BigDecimal baseQuantity,
            long lineAccruedMinor) {}

    private List<ReceiptLineComputation> computeReceiptLines(@NonNull List<CreateGoodsReceiptLineRequest> lines) {
        List<ReceiptLineComputation> computed = new ArrayList<>(lines.size());
        for (CreateGoodsReceiptLineRequest line : lines) {
            PurchaseOrderLineEntity poLine = resolvePurchaseOrderLine(line.getPoLineId());
            DocumentQuantityConverter.DocumentConversion conversion = documentQuantityConverter
                    .convertIfPresent(
                            resolveProductId(poLine, line.getSku()),
                            line.getSku(),
                            line.getDocumentUom(),
                            line.getDocumentQuantity())
                    .orElse(null);
            BigDecimal baseQuantity = conversion != null ? conversion.baseQuantity() : line.getQuantityReceived();
            if (baseQuantity == null) {
                throw new IllegalArgumentException(
                        "quantityReceived is required when documentUom/documentQuantity are absent");
            }
            // unitCostMinor refers to one document-UoM unit when a document UoM is keyed, so the
            // money math is documentQuantity × unitCostMinor either way.
            BigDecimal costedQuantity = conversion != null ? conversion.documentQuantity() : baseQuantity;
            long lineAccruedMinor = costedQuantity
                    .multiply(BigDecimal.valueOf(line.getUnitCostMinor()))
                    .setScale(0, RoundingMode.HALF_EVEN)
                    .longValue();
            computed.add(new ReceiptLineComputation(line, poLine, conversion, baseQuantity, lineAccruedMinor));
        }
        return computed;
    }

    /**
     * Resolves the catalog product id for a document line: the linked PO line's skuId when
     * present, otherwise the free-text SKU parsed as a UUID; {@code null} when neither resolves
     * (a document UoM on such a line raises {@code UOM_CONVERSION_UNDEFINED}).
     */
    private UUID resolveProductId(PurchaseOrderLineEntity poLine, String sku) {
        if (poLine != null && poLine.getSkuId() != null) {
            return poLine.getSkuId();
        }
        if (sku == null || sku.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(sku.trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private int toWholeQuantity(@NonNull BigDecimal quantity) {
        try {
            return quantity.intValueExact();
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException("quantity must be a whole number", ex);
        }
    }

    private int calculateQuantityAfter(
            @NonNull String stockItemId, @NonNull UUID locationId, @NonNull BigDecimal quantityDelta) {
        Integer onHand = inventoryLedgerEntryRepository.calculateOnHandQuantityAtLocation(stockItemId, locationId);
        int current = onHand == null ? 0 : onHand;
        return current + toWholeQuantity(quantityDelta);
    }

    private long safeLong(Long value) {
        return value == null ? 0L : value;
    }

    private String generateReceiptNumber() {
        return "GR-" + UUIDv7Generator.generate().toString().substring(0, 8).toUpperCase();
    }

    private @NonNull AsnResponse toAsnResponse(@NonNull AdvanceShippingNoticeEntity entity) {
        List<AsnLineResponse> lineResponses = entity.getLines().stream()
                .map(line -> AsnLineResponse.builder()
                        .asnLineId(line.getAsnLineId())
                        .poId(
                                line.getPurchaseOrder() == null
                                        ? null
                                        : line.getPurchaseOrder().getPurchaseOrderId())
                        .sku(line.getSku())
                        .quantityShipped(line.getQuantityShipped())
                        .quantityReceived(line.getQuantityReceived())
                        .unitOfMeasure(line.getUnitOfMeasure())
                        .lotNumber(line.getLotNumber())
                        .documentUom(line.getDocumentUom())
                        .documentQuantity(line.getDocumentQuantity())
                        .conversionFactor(line.getConversionFactor())
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
                        .poLineId(
                                line.getPoLine() == null
                                        ? null
                                        : line.getPoLine().getLineId())
                        .sku(line.getSku())
                        .quantityReceived(line.getQuantityReceived())
                        .unitCostMinor(line.getUnitCostMinor())
                        .lineAccruedAmountMinor(line.getLineAccruedAmountMinor())
                        .documentUom(line.getDocumentUom())
                        .documentQuantity(line.getDocumentQuantity())
                        .conversionFactor(line.getConversionFactor())
                        .build())
                .toList();

        return GoodsReceiptResponse.builder()
                .receiptId(entity.getReceiptId())
                .receiptNumber(entity.getReceiptNumber())
                .poId(
                        entity.getPurchaseOrder() == null
                                ? null
                                : entity.getPurchaseOrder().getPurchaseOrderId())
                .asnId(entity.getAsn() == null ? null : entity.getAsn().getAsnId())
                .locationId(entity.getLocationId())
                .totalAccruedAmountMinor(entity.getTotalAccruedAmountMinor())
                .createdBy(entity.getCreatedBy())
                .createdAt(entity.getCreatedAt())
                .lines(lineResponses)
                .build();
    }

    private @NonNull PurchaseOrderEntity requireApprovedPurchaseOrder(@NonNull UUID poId) {
        PurchaseOrderEntity purchaseOrder = purchaseOrderRepository
                .findById(poId)
                .orElseThrow(() -> new InvalidPoReferenceException(
                        "INVALID_PO_REFERENCE: PO " + poId + " is unknown or not APPROVED"));
        if (purchaseOrder.getStatus() != PurchaseOrderStatus.APPROVED
                && purchaseOrder.getStatus() != PurchaseOrderStatus.PARTIALLY_RECEIVED) {
            throw new InvalidPoReferenceException("INVALID_PO_REFERENCE: PO " + poId + " is unknown or not APPROVED");
        }
        return purchaseOrder;
    }

    private PurchaseOrderLineEntity resolvePurchaseOrderLine(UUID poLineId) {
        if (poLineId == null) {
            return null;
        }
        return purchaseOrderLineRepository
                .findById(poLineId)
                .orElseThrow(() -> new ResourceNotFoundException("PurchaseOrderLine", poLineId.toString()));
    }
}
