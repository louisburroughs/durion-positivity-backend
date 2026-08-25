package com.positivity.inventory.internal.service;

import com.positivity.domainevents.order.PurchaseOrderUpdatedV1;
import com.positivity.inventory.internal.dto.asn.AsnLineResponse;
import com.positivity.inventory.internal.dto.asn.AsnResponse;
import com.positivity.inventory.internal.dto.asn.CreateAsnRequest;
import com.positivity.inventory.internal.dto.asn.CreateGoodsReceiptLineRequest;
import com.positivity.inventory.internal.dto.asn.CreateGoodsReceiptRequest;
import com.positivity.inventory.internal.dto.asn.GoodsReceiptLineResponse;
import com.positivity.inventory.internal.dto.asn.GoodsReceiptResponse;
import com.positivity.inventory.internal.entity.AdvanceShippingNoticeEntity;
import com.positivity.inventory.internal.entity.AsnLineEntity;
import com.positivity.inventory.internal.entity.ExtPurchaseOrderLineReplica;
import com.positivity.inventory.internal.entity.ExtPurchaseOrderReplica;
import com.positivity.inventory.internal.entity.GoodsReceiptEntity;
import com.positivity.inventory.internal.entity.GoodsReceiptLineEntity;
import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.enums.AsnStatus;
import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import com.positivity.inventory.internal.exception.DuplicateAsnException;
import com.positivity.inventory.internal.exception.InvalidPoReferenceException;
import com.positivity.inventory.internal.exception.OverReceiptNotPermittedException;
import com.positivity.inventory.internal.exception.ResourceNotFoundException;
import com.positivity.inventory.internal.repository.AsnLineRepository;
import com.positivity.inventory.internal.repository.AsnRepository;
import com.positivity.inventory.internal.repository.ExtPurchaseOrderLineRepository;
import com.positivity.inventory.internal.repository.ExtPurchaseOrderRepository;
import com.positivity.inventory.internal.repository.GoodsReceiptRepository;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
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
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AsnServiceImpl implements AsnService {
    private static final String OCCURRED_AT = "occurredAt";

    private static final String EVENT_TYPE = "eventType";

    private static final String ASN_ID = "asnId";

    private final Clock clock;
    private final AsnRepository asnRepository;
    private final AsnLineRepository asnLineRepository;
    private final GoodsReceiptRepository goodsReceiptRepository;
    private final ExtPurchaseOrderRepository purchaseOrderRepository;
    private final ExtPurchaseOrderLineRepository purchaseOrderLineRepository;
    private final InventoryLedgerEntryRepository inventoryLedgerEntryRepository;
    private final LedgerPostingService ledgerPostingService;
    private final InventoryFactPublisher inventoryFactPublisher;
    private final GoodsReceiptFactPublisher goodsReceiptFactPublisher;
    private final ApplicationEventPublisher eventPublisher;
    private final DocumentQuantityConverter documentQuantityConverter;
    private final InventoryLotCaptureService lotCaptureService;
    private final QuantityScaleGuard quantityScaleGuard;

    @Override
    @Transactional
    public @NonNull AsnResponse createAsn(@NonNull CreateAsnRequest request, @NonNull String actorId) {
        for (UUID poId : request.getRelatedPoIds()) {
            ExtPurchaseOrderReplica purchaseOrder = purchaseOrderRepository
                    .findById(poId)
                    .orElseThrow(() -> new InvalidPoReferenceException(
                            "INVALID_PO_REFERENCE: PO " + poId + " is unknown or not APPROVED"));
            // A shipping notice may only be raised against an order that has been committed to.
            // PARTIALLY_RECEIVED is deliberately excluded here: a second ASN against a part
            // delivered order is a different case from the first, and is not what this creates.
            if (!"APPROVED".equals(purchaseOrder.getStatus())) {
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
                .purchaseOrderId(
                        requireApprovedPurchaseOrder(request.getRelatedPoIds().get(0))
                                .getPurchaseOrderId())
                .shipDate(request.getShipDate())
                .expectedArrivalDate(request.getExpectedArrivalDate())
                .build();
        AdvanceShippingNoticeEntity savedAsn = asnRepository.save(asnEntity);
        AdvanceShippingNoticeEntity savedAsnRef = savedAsn;

        List<AsnLineEntity> lineEntities = request.getLineItems().stream()
                .map(line -> {
                    ExtPurchaseOrderLineReplica poLine = resolvePurchaseOrderLine(line.getPoLineId());
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
                            .purchaseOrderId(
                                    requireApprovedPurchaseOrder(line.getPoId()).getPurchaseOrderId())
                            .poLineId(poLine == null ? null : poLine.getLineId())
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
                EVENT_TYPE,
                "ASNLoaded",
                ASN_ID,
                persistedAsn.getAsnId().toString(),
                "vendorId",
                persistedAsn.getVendorId().toString(),
                "actorId",
                actorId,
                OCCURRED_AT,
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

        UUID poId = resolvePurchaseOrderId(asn, request);
        ExtPurchaseOrderReplica purchaseOrder = requireApprovedPurchaseOrder(poId);

        // odoo-parity B2 (#1034): convert optional document-UoM quantities to base BEFORE the
        // over-receipt guard and any posting; money math stays documentQuantity × unitCostMinor
        // (unitCostMinor refers to one document-UoM unit when a document UoM is keyed).
        // odoo-parity E1 (#1038): the tracking-level gate runs in the same pass — a LOT-tracked
        // line without a lotNumber fails deterministically (LOT_NUMBER_REQUIRED) before any
        // guard, event, or posting.
        List<ReceiptLineComputation> computedLines =
                computeReceiptLines(request.getLines(), purchaseOrder.getVendorId());
        long receiptTotalMinor = computedLines.stream()
                .mapToLong(ReceiptLineComputation::lineAccruedMinor)
                .sum();
        guardOverReceipt(receiptTotalMinor, purchaseOrder);

        GoodsReceiptEntity persistedReceipt =
                persistReceipt(request, purchaseOrder, asn, computedLines, receiptTotalMinor);

        publishReceiptCreated(persistedReceipt, poId, computedLines, actorId);
        postLedgerEntries(request, computedLines, persistedReceipt, actorId);

        // Receiving states what arrived; pos-order decides what that means for the order's
        // outstanding quantities and status (CAP-320 #1334). Writing the order here is what made
        // two modules writers of one aggregate, and is exactly what this split removes.
        goodsReceiptFactPublisher.publish(
                persistedReceipt,
                computedLines.stream()
                        .map(computed -> new GoodsReceiptFactPublisher.GoodsReceiptLineFact(
                                computed.poLine() == null
                                        ? null
                                        : computed.poLine().getLineId(),
                                computed.request().getSku(),
                                computed.baseQuantity(),
                                computed.lineAccruedMinor()))
                        .toList());

        if (asn != null) {
            applyReceiptToAsn(asn, computedLines);
            asnRepository.save(asn);
        }

        publishReceiptCompleted(persistedReceipt, poId, receiptTotalMinor, actorId);
        return toGoodsReceiptResponse(persistedReceipt);
    }

    /**
     * The purchase order this receipt belongs to.
     *
     * <p>An ASN names it directly when it can; otherwise the first of its lines that names one
     * wins, and a receipt with no ASN falls back to what the request supplied.
     */
    private UUID resolvePurchaseOrderId(
            @Nullable AdvanceShippingNoticeEntity asn, @NonNull CreateGoodsReceiptRequest request) {
        if (asn == null) {
            return request.getPoId();
        }
        if (asn.getPurchaseOrderId() != null) {
            return asn.getPurchaseOrderId();
        }
        return asn.getLines().stream()
                .map(AsnLineEntity::getPurchaseOrderId)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(request.getPoId());
    }

    /** Receiving more value than the order still has open needs an explicit override authority. */
    private void guardOverReceipt(long receiptTotalMinor, @NonNull ExtPurchaseOrderReplica purchaseOrder) {
        long currentOpenBalance = safeLong(purchaseOrder.getOpenBalanceMinor());
        if (receiptTotalMinor > currentOpenBalance
                && !SecurityContextHelper.hasAuthority("inventory:goods_receipt:override")) {
            throw new OverReceiptNotPermittedException("Receiving " + receiptTotalMinor + " exceeds the "
                    + currentOpenBalance + " outstanding on purchase order " + purchaseOrder.getPurchaseOrderId());
        }
    }

    private GoodsReceiptEntity persistReceipt(
            @NonNull CreateGoodsReceiptRequest request,
            @NonNull ExtPurchaseOrderReplica purchaseOrder,
            @Nullable AdvanceShippingNoticeEntity asn,
            @NonNull List<ReceiptLineComputation> computedLines,
            long receiptTotalMinor) {
        GoodsReceiptEntity receiptEntity = GoodsReceiptEntity.builder()
                .receiptNumber(generateReceiptNumber())
                .purchaseOrderId(purchaseOrder.getPurchaseOrderId())
                .asn(asn)
                .locationId(request.getLocationId())
                .totalAccruedAmountMinor(receiptTotalMinor)
                .build();

        List<GoodsReceiptLineEntity> lineEntities = new ArrayList<>();
        for (ReceiptLineComputation computed : computedLines) {
            CreateGoodsReceiptLineRequest line = computed.request();
            DocumentQuantityConverter.DocumentConversion conversion = computed.conversion();
            lineEntities.add(GoodsReceiptLineEntity.builder()
                    .goodsReceipt(receiptEntity)
                    .poLineId(
                            computed.poLine() == null ? null : computed.poLine().getLineId())
                    .sku(line.getSku())
                    .quantityReceived(computed.baseQuantity())
                    .unitCostMinor(line.getUnitCostMinor())
                    .lineAccruedAmountMinor(computed.lineAccruedMinor())
                    .lotNumber(line.getLotNumber())
                    .documentUom(conversion == null ? null : conversion.documentUom())
                    .documentQuantity(conversion == null ? null : conversion.documentQuantity())
                    .conversionFactor(conversion == null ? null : conversion.conversionFactor())
                    .build());
        }
        receiptEntity.setLines(lineEntities);
        return goodsReceiptRepository.save(receiptEntity);
    }

    /** Posts one base-UoM ledger row per received line and marks it for fact publication. */
    private void postLedgerEntries(
            @NonNull CreateGoodsReceiptRequest request,
            @NonNull List<ReceiptLineComputation> computedLines,
            @NonNull GoodsReceiptEntity persistedReceipt,
            @NonNull String actorId) {
        for (ReceiptLineComputation computed : computedLines) {
            // Ledger rows stay base-UoM only (spec B2): the converted base quantity posts here.
            // lotId is null for untracked products (E1 zero-change guarantee).
            InventoryLedgerEntry entry = InventoryLedgerEntry.builder()
                    .stockItemId(computed.request().getSku())
                    .locationId(request.getLocationId())
                    .toLocationId(request.getLocationId())
                    .eventType(InventoryLedgerEventType.GOODS_RECEIPT)
                    .changeInQuantity(computed.baseQuantity())
                    .quantityAfter(calculateQuantityAfter(
                            computed.request().getSku(), request.getLocationId(), computed.baseQuantity()))
                    .lotId(computed.lotId())
                    // odoo-parity E4 (#1050): the funnel enumerates these serials for SERIAL-tracked
                    // products (422 SERIAL_COUNT_MISMATCH if the count != received qty); ignored otherwise.
                    .serialNumbers(
                            computed.request().getSerialNumbers() == null
                                    ? java.util.List.of()
                                    : computed.request().getSerialNumbers())
                    .transactionUserId(actorId)
                    .sourceTransactionId(persistedReceipt.getReceiptId().toString())
                    .notes("Goods receipt " + persistedReceipt.getReceiptNumber())
                    .build();
            ledgerPostingService.post(entry);
            inventoryFactPublisher.markEntry(entry);
        }
    }

    private void publishReceiptCreated(
            @NonNull GoodsReceiptEntity persistedReceipt,
            @NonNull UUID poId,
            @NonNull List<ReceiptLineComputation> computedLines,
            @NonNull String actorId) {
        eventPublisher.publishEvent(Map.of(
                "type",
                "ReceiptCreated",
                EVENT_TYPE,
                "ReceiptCreated",
                "receiptId",
                persistedReceipt.getReceiptId().toString(),
                "poId",
                poId.toString(),
                ASN_ID,
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
                OCCURRED_AT,
                Instant.now(clock).toString()));
    }

    private void publishReceiptCompleted(
            @NonNull GoodsReceiptEntity persistedReceipt,
            @NonNull UUID poId,
            long receiptTotalMinor,
            @NonNull String actorId) {
        eventPublisher.publishEvent(Map.of(
                "type",
                "ReceiptCompleted",
                EVENT_TYPE,
                "ReceiptCompleted",
                "receiptId",
                persistedReceipt.getReceiptId().toString(),
                "poId",
                poId.toString(),
                ASN_ID,
                persistedReceipt.getAsn() == null
                        ? ""
                        : persistedReceipt.getAsn().getAsnId().toString(),
                "totalAccruedAmountMinor",
                receiptTotalMinor,
                "actorId",
                actorId,
                OCCURRED_AT,
                Instant.now(clock).toString()));
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
     * document-UoM conversion, the base quantity that posts to the ledger, the accrued
     * amount computed from the costed (document-unit) quantity, and the lot resolved by the
     * tracking-level gate (odoo-parity E1, #1038; null for untracked products).
     */
    private record ReceiptLineComputation(
            CreateGoodsReceiptLineRequest request,
            ExtPurchaseOrderLineReplica poLine,
            DocumentQuantityConverter.DocumentConversion conversion,
            BigDecimal baseQuantity,
            long lineAccruedMinor,
            UUID lotId) {}

    private List<ReceiptLineComputation> computeReceiptLines(
            @NonNull List<CreateGoodsReceiptLineRequest> lines, UUID vendorId) {
        List<ReceiptLineComputation> computed = new ArrayList<>(lines.size());
        for (CreateGoodsReceiptLineRequest line : lines) {
            ExtPurchaseOrderLineReplica poLine = resolvePurchaseOrderLine(line.getPoLineId());
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
            // ADR-0055 (#1414): the base quantity may carry decimals only to the scale the
            // product declares. Checked here, where the product id is already resolved, rather
            // than at the ledger builder — a receipt that cannot post must be refused before any
            // accrual or lot is computed against it.
            baseQuantity = quantityScaleGuard.requirePostable(
                    resolveProductId(poLine, line.getSku()), line.getSku(), "quantityReceived", baseQuantity);
            // unitCostMinor refers to one document-UoM unit when a document UoM is keyed, so the
            // money math is documentQuantity × unitCostMinor either way.
            BigDecimal costedQuantity = conversion != null ? conversion.documentQuantity() : baseQuantity;
            long lineAccruedMinor = costedQuantity
                    .multiply(BigDecimal.valueOf(line.getUnitCostMinor()))
                    .setScale(0, RoundingMode.HALF_EVEN)
                    .longValue();
            // odoo-parity E1 (#1038): LOT-tracked SKUs require a lotNumber (422 otherwise) and
            // find-or-create the lot; untracked SKUs pass through with a null lot unchanged.
            UUID lotId = lotCaptureService.resolveReceiptLot(line.getSku(), line.getLotNumber(), vendorId);
            computed.add(new ReceiptLineComputation(line, poLine, conversion, baseQuantity, lineAccruedMinor, lotId));
        }
        return computed;
    }

    /**
     * Resolves the catalog product id for a document line: the linked PO line's skuId when
     * present, otherwise the free-text SKU parsed as a UUID; {@code null} when neither resolves
     * (a document UoM on such a line raises {@code UOM_CONVERSION_UNDEFINED}).
     */
    private UUID resolveProductId(ExtPurchaseOrderLineReplica poLine, String sku) {
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

    private BigDecimal calculateQuantityAfter(
            @NonNull String stockItemId, @NonNull UUID locationId, @NonNull BigDecimal quantityDelta) {
        BigDecimal onHand = inventoryLedgerEntryRepository.calculateOnHandQuantityAtLocation(stockItemId, locationId);
        return Quantities.nz(onHand).add(quantityDelta);
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
                        .poId(line.getPurchaseOrderId())
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
                        .poLineId(line.getPoLineId())
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
                .poId(entity.getPurchaseOrderId())
                .asnId(entity.getAsn() == null ? null : entity.getAsn().getAsnId())
                .locationId(entity.getLocationId())
                .totalAccruedAmountMinor(entity.getTotalAccruedAmountMinor())
                .createdBy(entity.getCreatedBy())
                .createdAt(entity.getCreatedAt())
                .lines(lineResponses)
                .build();
    }

    /**
     * The order an ASN or receipt may be raised against, read from the projection (CAP-320 #1334).
     *
     * <p>pos-order owns the order now, so this asks the replica rather than a table here or a call
     * there (ADR-0044 R1/R3). An order the projection has not caught up with is treated as unknown:
     * refusing a receipt that is merely early is recoverable, while accepting one against an order
     * that was never approved is not.
     */
    private @NonNull ExtPurchaseOrderReplica requireApprovedPurchaseOrder(@NonNull UUID poId) {
        ExtPurchaseOrderReplica purchaseOrder = purchaseOrderRepository
                .findById(poId)
                .orElseThrow(() -> new InvalidPoReferenceException(
                        "INVALID_PO_REFERENCE: PO " + poId + " is unknown or not APPROVED"));
        if (!PurchaseOrderUpdatedV1.OPEN_SUPPLY_STATUSES.contains(purchaseOrder.getStatus())) {
            throw new InvalidPoReferenceException("INVALID_PO_REFERENCE: PO " + poId + " is unknown or not APPROVED");
        }
        return purchaseOrder;
    }

    private ExtPurchaseOrderLineReplica resolvePurchaseOrderLine(UUID poLineId) {
        if (poLineId == null) {
            return null;
        }
        return purchaseOrderLineRepository
                .findById(poLineId)
                .orElseThrow(() -> new ResourceNotFoundException("PurchaseOrderLine", poLineId.toString()));
    }
}
