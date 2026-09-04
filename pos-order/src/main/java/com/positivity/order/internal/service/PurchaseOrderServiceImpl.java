package com.positivity.order.internal.service;

import com.positivity.order.internal.dto.purchaseorder.ApprovePurchaseOrderRequest;
import com.positivity.order.internal.dto.purchaseorder.CreatePurchaseOrderRequest;
import com.positivity.order.internal.dto.purchaseorder.ListPurchaseOrdersRequest;
import com.positivity.order.internal.dto.purchaseorder.PurchaseOrderLineRequest;
import com.positivity.order.internal.dto.purchaseorder.PurchaseOrderLineResponse;
import com.positivity.order.internal.dto.purchaseorder.PurchaseOrderResponse;
import com.positivity.order.internal.dto.purchaseorder.PurchaseOrderTransmissionEventResponse;
import com.positivity.order.internal.dto.purchaseorder.RevisePurchaseOrderRequest;
import com.positivity.order.internal.entity.PurchaseOrderEntity;
import com.positivity.order.internal.entity.PurchaseOrderLineEntity;
import com.positivity.order.internal.entity.PurchaseOrderTransmissionEvent;
import com.positivity.order.internal.enums.PurchaseOrderStatus;
import com.positivity.order.internal.exception.PurchaseOrderNotFoundException;
import com.positivity.order.internal.exception.PurchaseOrderRequestValidationException;
import com.positivity.order.internal.repository.PurchaseOrderRepository;
import com.positivity.order.internal.repository.PurchaseOrderTransmissionEventRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The purchase-order aggregate (CAP-320 #1334, ADR-0049 §2).
 *
 * <h2>One writer</h2>
 *
 * Every transition here ends with a {@code purchaseorder.updated} fact carrying the order's full
 * state. That is the only way anything outside this module learns what the order says — there is
 * no second write path and no synchronous read of these tables (ADR-0044 R1/R3).
 *
 * <h2>What is not here</h2>
 *
 * Receiving. Open quantities move when pos-inventory reports a receipt, not when a caller asks
 * this service to move them. The lifecycle statuses {@code PARTIALLY_RECEIVED} and
 * {@code FULLY_RECEIVED} are therefore reached from a fact rather than from an endpoint.
 */
@Service
@RequiredArgsConstructor
public class PurchaseOrderServiceImpl implements PurchaseOrderService {

    /** Highest sequence value that still renders as 8 base-36 characters. */
    private static final long MAX_PO_NUMBER_SEQUENCE = 2_821_109_907_455L;

    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseOrderTransmissionEventRepository transmissionEventRepository;

    /**
     * Used only by {@link #createRequested}, which needs an insert that fails on a duplicate id
     * rather than a save that quietly merges into one.
     */
    private final jakarta.persistence.EntityManager entityManager;

    private final PurchaseOrderFactPublisher purchaseOrderFactPublisher;
    private final DocumentQuantityConverter documentQuantityConverter;
    private final Clock clock;

    @Value("${pos.order.default-tax-rate:0.10}")
    private double defaultTaxRate = 0.10d;

    @Override
    @Transactional
    public @NonNull PurchaseOrderResponse createPurchaseOrder(
            @NonNull CreatePurchaseOrderRequest request, @NonNull String actorId) {
        TotalsAndLines totalsAndLines = buildLineEntities(request.getLines());
        long subtotalMinor = totalsAndLines.subtotalMinor();
        long taxMinor = totalsAndLines.taxMinor();
        long grandTotalMinor = subtotalMinor + taxMinor;

        PurchaseOrderEntity purchaseOrder = PurchaseOrderEntity.builder()
                .vendorId(request.getVendorId())
                .poNumber(generatePoNumber())
                .status(PurchaseOrderStatus.DRAFT)
                .versionNumber(1)
                .currency(request.getCurrency())
                .subtotalMinor(subtotalMinor)
                .taxMinor(taxMinor)
                .grandTotalMinor(grandTotalMinor)
                .openBalanceMinor(grandTotalMinor)
                .shipToLocationId(request.getShipToLocationId())
                .paymentTermsId(request.getPaymentTermsId())
                .poDate(request.getPoDate())
                .expectedDeliveryDate(request.getExpectedDeliveryDate())
                .requestedBy(request.getRequestedBy())
                .comment(request.getComment())
                .build();

        List<PurchaseOrderLineEntity> lines = totalsAndLines.lines();
        lines.forEach(line -> line.setPurchaseOrder(purchaseOrder));
        purchaseOrder.setLines(lines);

        PurchaseOrderEntity saved = purchaseOrderRepository.save(purchaseOrder);
        purchaseOrderFactPublisher.publish(saved, saved.getLines());
        return toResponse(saved);
    }

    /**
     * Creates an order at an identity another domain chose (CAP-320 #1334).
     *
     * <p>Identical to {@link #createPurchaseOrder} but for the caller-supplied id, which is what
     * makes a replenishment conversion produce exactly one order however many times its request is
     * delivered. Package-private: this is not a REST operation and must not become one, or the
     * identity of an order would become something any caller could dictate.
     *
     * <h2>Why this persists rather than saves</h2>
     *
     * {@code save()} would not do. The id is already set when it arrives, and Spring Data reads a
     * non-null id on a {@code @GeneratedValue} entity as "not new", so it would call
     * {@code merge()} — which silently <em>updates</em> an existing row. That turns the guarantee
     * inside out: a duplicate request would overwrite the order it was meant to be prevented from
     * creating twice, and a concurrent insert that slipped past the caller's existence check would
     * be lost rather than rejected. {@code persist()} inserts or fails, so the primary key is what
     * enforces "exactly one order per request" rather than a check-then-act that races.
     */
    @Transactional
    void createRequested(
            @NonNull UUID purchaseOrderId, @NonNull CreatePurchaseOrderRequest request, @NonNull String actorId) {
        TotalsAndLines totalsAndLines = buildLineEntities(request.getLines());
        long grandTotalMinor = totalsAndLines.subtotalMinor() + totalsAndLines.taxMinor();

        PurchaseOrderEntity purchaseOrder = PurchaseOrderEntity.builder()
                .purchaseOrderId(purchaseOrderId)
                .vendorId(request.getVendorId())
                .poNumber(generatePoNumber())
                .status(PurchaseOrderStatus.DRAFT)
                .versionNumber(1)
                .currency(request.getCurrency())
                .subtotalMinor(totalsAndLines.subtotalMinor())
                .taxMinor(totalsAndLines.taxMinor())
                .grandTotalMinor(grandTotalMinor)
                .openBalanceMinor(grandTotalMinor)
                .shipToLocationId(request.getShipToLocationId())
                .paymentTermsId(request.getPaymentTermsId())
                .poDate(request.getPoDate())
                .expectedDeliveryDate(request.getExpectedDeliveryDate())
                .requestedBy(request.getRequestedBy())
                .comment(request.getComment())
                .createdBy(actorId)
                .build();

        List<PurchaseOrderLineEntity> lines = totalsAndLines.lines();
        lines.forEach(line -> line.setPurchaseOrder(purchaseOrder));
        purchaseOrder.setLines(lines);

        entityManager.persist(purchaseOrder);
        entityManager.flush();
        purchaseOrderFactPublisher.publish(purchaseOrder, purchaseOrder.getLines());
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull PurchaseOrderResponse getPurchaseOrder(@NonNull UUID poId) {
        return toResponse(getPoOrThrow(poId));
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull Page<PurchaseOrderResponse> listPurchaseOrders(
            @NonNull ListPurchaseOrdersRequest filter, @NonNull Pageable pageable) {
        Page<PurchaseOrderEntity> page;
        if (filter.getVendorId() != null && filter.getStatus() != null) {
            page = purchaseOrderRepository.findByVendorIdAndStatus(filter.getVendorId(), filter.getStatus(), pageable);
        } else if (filter.getVendorId() != null) {
            page = purchaseOrderRepository.findByVendorId(filter.getVendorId(), pageable);
        } else if (filter.getStatus() != null) {
            page = purchaseOrderRepository.findByStatus(filter.getStatus(), pageable);
        } else {
            page = purchaseOrderRepository.findAll(pageable);
        }

        List<PurchaseOrderResponse> content = page.getContent().stream()
                .filter(po ->
                        filter.getCurrency() == null || filter.getCurrency().equalsIgnoreCase(po.getCurrency()))
                .filter(po ->
                        filter.getLocationId() == null || filter.getLocationId().equals(po.getShipToLocationId()))
                .map(this::toResponse)
                .toList();

        return new PageImpl<>(content, pageable, page.getTotalElements());
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull Page<PurchaseOrderTransmissionEventResponse> listTransmissionEvents(
            @NonNull UUID poId, @NonNull Pageable pageable) {
        if (!purchaseOrderRepository.existsById(poId)) {
            throw new PurchaseOrderNotFoundException(poId);
        }
        // The ordering is the semantics of the timeline, so the client's sort parameter is
        // deliberately not honoured: only the page window is taken from the request, and the
        // derived query supplies observedAt, recordedAt, eventId ascending.
        Pageable window = pageable.isPaged()
                ? PageRequest.of(pageable.getPageNumber(), pageable.getPageSize())
                : Pageable.unpaged();
        return transmissionEventRepository
                .findByPurchaseOrderIdOrderByObservedAtAscRecordedAtAscEventIdAsc(poId, window)
                .map(PurchaseOrderServiceImpl::toTransmissionEventResponse);
    }

    private static PurchaseOrderTransmissionEventResponse toTransmissionEventResponse(
            PurchaseOrderTransmissionEvent event) {
        return new PurchaseOrderTransmissionEventResponse(
                event.getEventId(),
                event.getTransmissionIntentId(),
                event.getEventType(),
                event.getStatus(),
                event.getVendorDocumentId(),
                event.getSupplierOrderNumber(),
                event.getVendorReason(),
                event.getDespatchDate(),
                event.getEstimatedDeliveryDate(),
                event.getObservedAt(),
                event.getRecordedAt());
    }

    @Override
    @Transactional
    public @NonNull PurchaseOrderResponse approvePurchaseOrder(
            @NonNull UUID poId, @NonNull ApprovePurchaseOrderRequest request, @NonNull String actorId) {
        PurchaseOrderEntity po = getPoOrThrow(poId);
        if (po.getStatus() != PurchaseOrderStatus.DRAFT) {
            throw new IllegalStateException("Only DRAFT purchase orders can be approved");
        }

        // Approval is the moment the order becomes a commitment, and the moment its open
        // quantities start counting as incoming supply everywhere downstream.
        po.setStatus(PurchaseOrderStatus.APPROVED);
        po.setApproverId(actorId);
        po.setApprovalTimestamp(Instant.now(clock));
        po.setApprovalNotes(request.getApprovalNotes());

        PurchaseOrderEntity saved = purchaseOrderRepository.save(po);
        purchaseOrderFactPublisher.publish(saved, saved.getLines());
        return toResponse(saved);
    }

    @Override
    @Transactional
    public @NonNull PurchaseOrderResponse revisePurchaseOrder(
            @NonNull UUID poId, @NonNull RevisePurchaseOrderRequest request, @NonNull String actorId) {
        PurchaseOrderEntity po = getPoOrThrow(poId);
        int priorVersion = po.getVersionNumber() == null ? 1 : po.getVersionNumber();

        po.setVersionNumber(priorVersion + 1);
        po.setPoDate(request.getPoDate());
        po.setPaymentTermsId(request.getPaymentTermsId());
        po.setExpectedDeliveryDate(request.getExpectedDeliveryDate());
        po.setShipToLocationId(request.getShipToLocationId());
        po.setRequestedBy(request.getRequestedBy());
        po.setComment(request.getComment());

        TotalsAndLines totalsAndLines = buildLineEntities(request.getLines());
        po.setSubtotalMinor(totalsAndLines.subtotalMinor());
        po.setTaxMinor(totalsAndLines.taxMinor());
        po.setGrandTotalMinor(totalsAndLines.subtotalMinor() + totalsAndLines.taxMinor());
        po.setOpenBalanceMinor(totalsAndLines.lines().stream()
                .mapToLong(line -> safeLong(line.getLineTotalMinor()) + safeLong(line.getTaxMinor()))
                .sum());

        po.getLines().clear();
        totalsAndLines.lines().forEach(line -> {
            line.setPurchaseOrder(po);
            po.getLines().add(line);
        });

        PurchaseOrderEntity saved = purchaseOrderRepository.save(po);
        purchaseOrderFactPublisher.publish(saved, saved.getLines());
        return toResponse(saved);
    }

    @Override
    @Transactional
    public @NonNull PurchaseOrderResponse cancelPurchaseOrder(@NonNull UUID poId, @NonNull String actorId) {
        PurchaseOrderEntity po = getPoOrThrow(poId);
        if (po.getStatus() == PurchaseOrderStatus.FULLY_RECEIVED || po.getStatus() == PurchaseOrderStatus.CLOSED) {
            throw new IllegalStateException("Cannot cancel a fully received or closed purchase order");
        }

        po.setStatus(PurchaseOrderStatus.CANCELLED);
        PurchaseOrderEntity saved = purchaseOrderRepository.save(po);
        // The fact alone tells every reader the supply is gone: a cancelled order is not in the
        // open-supply status set, so a consumer applying this drops it from its own figures.
        // pos-inventory derives its ExpectedSupplyDropped fact from that transition, in its own
        // domain, rather than pos-order emitting an inventory fact it does not own.
        purchaseOrderFactPublisher.publish(saved, saved.getLines());
        return toResponse(saved);
    }

    private PurchaseOrderEntity getPoOrThrow(UUID poId) {
        return purchaseOrderRepository.findById(poId).orElseThrow(() -> new PurchaseOrderNotFoundException(poId));
    }

    private TotalsAndLines buildLineEntities(List<PurchaseOrderLineRequest> lineRequests) {
        List<PurchaseOrderLineEntity> lines = new ArrayList<>();
        long subtotalMinor = 0L;
        long totalTaxMinor = 0L;

        for (PurchaseOrderLineRequest lineRequest : lineRequests) {
            // An optional document UoM (e.g. 1 CASE) derives the base quantity that flows into
            // open-quantity maths; unitCostMinor refers to one document-UoM unit, so the money
            // maths stays documentQuantity × unitCostMinor.
            DocumentQuantityConverter.DocumentConversion conversion = documentQuantityConverter
                    .convertIfPresent(
                            lineRequest.getSkuId(),
                            String.valueOf(lineRequest.getSkuId()),
                            lineRequest.getDocumentUom(),
                            lineRequest.getDocumentQuantity())
                    .orElse(null);
            BigDecimal baseQuantity = conversion != null ? conversion.baseQuantity() : lineRequest.getQuantity();
            if (baseQuantity == null) {
                throw new PurchaseOrderRequestValidationException(
                        "quantity is required when documentUom/documentQuantity are absent");
            }
            BigDecimal costedQuantity = conversion != null ? conversion.documentQuantity() : baseQuantity;
            long lineTotalMinor = costedQuantity
                    .multiply(BigDecimal.valueOf(lineRequest.getUnitCostMinor()))
                    .setScale(0, RoundingMode.HALF_EVEN)
                    .longValue();
            long lineTaxMinor = lineRequest.getTaxCodeId() == null ? 0L : Math.round(lineTotalMinor * defaultTaxRate);

            lines.add(PurchaseOrderLineEntity.builder()
                    .lineNumber(lineRequest.getLineNumber())
                    .skuId(lineRequest.getSkuId())
                    .description(lineRequest.getDescription())
                    .quantityDecimal(baseQuantity)
                    .unitCostMinor(lineRequest.getUnitCostMinor())
                    .lineTotalMinor(lineTotalMinor)
                    .taxMinor(lineTaxMinor)
                    .taxCodeId(lineRequest.getTaxCodeId())
                    .glAccountId(lineRequest.getGlAccountId())
                    // A new line is entirely outstanding until something is received against it.
                    .openQuantityDecimal(baseQuantity)
                    .documentUom(conversion == null ? null : conversion.documentUom())
                    .documentQuantity(conversion == null ? null : conversion.documentQuantity())
                    .conversionFactor(conversion == null ? null : conversion.conversionFactor())
                    .build());

            subtotalMinor += lineTotalMinor;
            totalTaxMinor += lineTaxMinor;
        }

        return new TotalsAndLines(lines, subtotalMinor, totalTaxMinor);
    }

    private PurchaseOrderResponse toResponse(PurchaseOrderEntity entity) {
        List<PurchaseOrderLineResponse> lineResponses = entity.getLines().stream()
                .sorted(Comparator.comparing(PurchaseOrderLineEntity::getLineNumber))
                .map(PurchaseOrderServiceImpl::toLineResponse)
                .toList();

        return PurchaseOrderResponse.builder()
                .purchaseOrderId(entity.getPurchaseOrderId())
                .vendorId(entity.getVendorId())
                .poNumber(entity.getPoNumber())
                .status(entity.getStatus())
                .versionNumber(entity.getVersionNumber())
                .currency(entity.getCurrency())
                .subtotalMinor(entity.getSubtotalMinor())
                .taxMinor(entity.getTaxMinor())
                .grandTotalMinor(entity.getGrandTotalMinor())
                .openBalanceMinor(safeLong(entity.getOpenBalanceMinor()))
                .shipToLocationId(entity.getShipToLocationId())
                .paymentTermsId(entity.getPaymentTermsId())
                .poDate(entity.getPoDate())
                .expectedDeliveryDate(entity.getExpectedDeliveryDate())
                .requestedBy(entity.getRequestedBy())
                .comment(entity.getComment())
                .approverId(entity.getApproverId())
                .approvalTimestamp(entity.getApprovalTimestamp())
                .approvalNotes(entity.getApprovalNotes())
                .createdBy(entity.getCreatedBy())
                .updatedBy(entity.getUpdatedBy())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .lines(lineResponses)
                .build();
    }

    private static PurchaseOrderLineResponse toLineResponse(PurchaseOrderLineEntity line) {
        return PurchaseOrderLineResponse.builder()
                .lineId(line.getLineId())
                .lineNumber(line.getLineNumber())
                .skuId(line.getSkuId())
                .description(line.getDescription())
                .quantityDecimal(line.getQuantityDecimal())
                .unitCostMinor(line.getUnitCostMinor())
                .lineTotalMinor(line.getLineTotalMinor())
                .taxMinor(safeLong(line.getTaxMinor()))
                .openQuantityDecimal(line.getOpenQuantityDecimal())
                .documentUom(line.getDocumentUom())
                .documentQuantity(line.getDocumentQuantity())
                .conversionFactor(line.getConversionFactor())
                .build();
    }

    private static long safeLong(Long value) {
        return value == null ? 0L : value;
    }

    /**
     * Renders the next sequence value as an 8-character base-36 code.
     *
     * <p>The sequence moved with the aggregate, started above the pos-inventory high-water mark, so
     * a number issued here cannot collide with one already printed on a purchase order.
     */
    private String generatePoNumber() {
        long sequence = purchaseOrderRepository.getNextPurchaseOrderSequence();
        if (sequence < 1L || sequence > MAX_PO_NUMBER_SEQUENCE) {
            throw new IllegalStateException("Purchase order sequence is out of range for 8-character codes");
        }
        return String.format(Locale.ROOT, "%8s", Long.toString(sequence, 36))
                .replace(' ', '0')
                .toUpperCase(Locale.ROOT);
    }

    private record TotalsAndLines(List<PurchaseOrderLineEntity> lines, long subtotalMinor, long taxMinor) {}
}
