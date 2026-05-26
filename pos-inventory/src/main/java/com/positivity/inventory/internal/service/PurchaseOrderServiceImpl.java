package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.dto.purchaseorder.ApprovePurchaseOrderRequest;
import com.positivity.inventory.internal.dto.purchaseorder.CreatePurchaseOrderRequest;
import com.positivity.inventory.internal.dto.purchaseorder.ListPurchaseOrdersRequest;
import com.positivity.inventory.internal.dto.purchaseorder.PurchaseOrderLineRequest;
import com.positivity.inventory.internal.dto.purchaseorder.PurchaseOrderLineResponse;
import com.positivity.inventory.internal.dto.purchaseorder.PurchaseOrderResponse;
import com.positivity.inventory.internal.dto.purchaseorder.ReceivePurchaseOrderRequest;
import com.positivity.inventory.internal.dto.purchaseorder.ReceivePurchaseOrderResponse;
import com.positivity.inventory.internal.dto.purchaseorder.RevisePurchaseOrderRequest;
import com.positivity.inventory.internal.entity.PurchaseOrderEntity;
import com.positivity.inventory.internal.entity.PurchaseOrderLineEntity;
import com.positivity.inventory.internal.enums.PurchaseOrderStatus;
import com.positivity.inventory.internal.exception.PurchaseOrderNotApprovedException;
import com.positivity.inventory.internal.exception.ResourceNotFoundException;
import com.positivity.inventory.internal.repository.PurchaseOrderLineRepository;
import com.positivity.inventory.internal.repository.PurchaseOrderRepository;
import com.positivity.inventory.service.PurchaseOrderService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PurchaseOrderServiceImpl implements PurchaseOrderService {

    private static final String OCCURRED_AT = "occurredAt";
    private static final String ACTOR_ID = "actorId";
    private static final String EVENT_TYPE = "eventType";
    private static final long MAX_PO_NUMBER_SEQUENCE = 2_821_109_907_455L;

    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseOrderLineRepository purchaseOrderLineRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ApplicationContext applicationContext;
    private final EncumbranceEventPublisher encumbranceEventPublisher;
    private final Clock clock;

    @Value("${pos.inventory.encumbranceEnabled:false}")
    private boolean encumbranceEnabled;

    @Value("${pos.inventory.default-tax-rate:0.10}")
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
        eventPublisher.publishEvent(Map.of(
                EVENT_TYPE,
                "PurchaseOrderCreated",
                "poId",
                saved.getPurchaseOrderId().toString(),
                "vendorId",
                saved.getVendorId().toString(),
                ACTOR_ID,
                actorId,
                OCCURRED_AT,
                Instant.now(clock).toString()));
        return toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull PurchaseOrderResponse getPurchaseOrder(@NonNull UUID poId) {
        PurchaseOrderEntity entity = getPoOrThrow(poId);
        return toResponse(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull Page<PurchaseOrderResponse> listPurchaseOrders(
            @NonNull ListPurchaseOrdersRequest filter, @NonNull Pageable pageable) {
        Page<PurchaseOrderEntity> page;
        if (filter.getVendorId() != null && filter.getStatus() != null) {
            page = purchaseOrderRepository.findByVendorIdAndStatus(filter.getVendorId(), filter.getStatus(), pageable);
            if (page == null) {
                List<PurchaseOrderEntity> legacy = purchaseOrderRepository.findByVendorIdAndStatus(filter.getVendorId(),
                        filter.getStatus());
                page = new PageImpl<>(legacy, pageable, legacy.size());
            }
        } else if (filter.getVendorId() != null) {
            page = purchaseOrderRepository.findByVendorId(filter.getVendorId(), pageable);
            if (page == null) {
                page = purchaseOrderRepository.findAll(pageable);
            }
        } else if (filter.getStatus() != null) {
            page = purchaseOrderRepository.findByStatus(filter.getStatus(), pageable);
            if (page == null) {
                page = purchaseOrderRepository.findAll(pageable);
            }
        } else {
            page = purchaseOrderRepository.findAll(pageable);
        }

        List<PurchaseOrderResponse> content = page.getContent().stream()
                .filter(po -> filter.getVendorId() == null || filter.getVendorId().equals(po.getVendorId()))
                .filter(po -> filter.getCurrency() == null || filter.getCurrency().equalsIgnoreCase(po.getCurrency()))
                .filter(po -> filter.getLocationId() == null || filter.getLocationId().equals(po.getShipToLocationId()))
                .map(this::toResponse)
                .toList();

        return new PageImpl<>(content, pageable, page.getTotalElements());
    }

    @Override
    @Transactional
    public @NonNull PurchaseOrderResponse approvePurchaseOrder(
            @NonNull UUID poId, @NonNull ApprovePurchaseOrderRequest request, @NonNull String actorId) {
        PurchaseOrderEntity po = getPoOrThrow(poId);
        if (po.getStatus() != PurchaseOrderStatus.DRAFT) {
            throw new IllegalStateException("Only DRAFT purchase orders can be approved");
        }

        po.setStatus(PurchaseOrderStatus.APPROVED);
        po.setApproverId(actorId);
        Instant now = Instant.now(clock);
        po.setApprovalTimestamp(now);
        po.setApprovalNotes(request.getApprovalNotes());
        if (encumbranceEnabled) {
            po.setEncumbranceRef(
                    "ENC-" + po.getPurchaseOrderId().toString().substring(0, 8).toUpperCase());
        }

        PurchaseOrderEntity saved = purchaseOrderRepository.save(po);

        eventPublisher.publishEvent(Map.of(
                EVENT_TYPE,
                "PurchaseOrderApproved",
                "poId",
                saved.getPurchaseOrderId().toString(),
                "vendorId",
                saved.getVendorId().toString(),
                "totalAmountMinor",
                saved.getGrandTotalMinor(),
                "currency",
                saved.getCurrency(),
                "lineItems",
                toApprovedLineItems(saved),
                "approvalStatus",
                saved.getStatus().name(),
                ACTOR_ID,
                actorId,
                OCCURRED_AT,
                now.toString()));

        if (encumbranceEnabled) {
            EncumbranceEventPublisher publisher = encumbranceEventPublisher;
            if (publisher == null) {
                publisher = applicationContext.getBean(EncumbranceEventPublisher.class);
            }
            publisher.publishEncumbranceEvent(
                    saved.getPurchaseOrderId(),
                    saved.getPoNumber(),
                    safeLong(saved.getGrandTotalMinor()),
                    saved.getCurrency());
        }

        return toResponse(saved);
    }

    @Override
    @Transactional
    public @NonNull PurchaseOrderResponse revisePurchaseOrder(
            @NonNull UUID poId, @NonNull RevisePurchaseOrderRequest request, @NonNull String actorId) {
        PurchaseOrderEntity po = getPoOrThrow(poId);
        Integer priorVersion = po.getVersionNumber() == null ? 1 : po.getVersionNumber();
        long priorSubtotalMinor = safeLong(po.getSubtotalMinor());
        long priorTaxMinor = safeLong(po.getTaxMinor());
        long priorGrandTotalMinor = safeLong(po.getGrandTotalMinor());
        Map<String, Object> delta = new HashMap<>();
        addDelta(delta, "poDate", po.getPoDate(), request.getPoDate());
        addDelta(delta, "paymentTermsId", po.getPaymentTermsId(), request.getPaymentTermsId());
        addDelta(delta, "expectedDeliveryDate", po.getExpectedDeliveryDate(), request.getExpectedDeliveryDate());
        addDelta(delta, "shipToLocationId", po.getShipToLocationId(), request.getShipToLocationId());
        addDelta(delta, "requestedBy", po.getRequestedBy(), request.getRequestedBy());
        addDelta(delta, "comment", po.getComment(), request.getComment());

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
        addDelta(delta, "subtotalMinor", priorSubtotalMinor, totalsAndLines.subtotalMinor());
        addDelta(delta, "taxMinor", priorTaxMinor, totalsAndLines.taxMinor());
        addDelta(
                delta,
                "grandTotalMinor",
                priorGrandTotalMinor,
                totalsAndLines.subtotalMinor() + totalsAndLines.taxMinor());

        long revisedOpenBalance = totalsAndLines.lines().stream()
                .mapToLong(line -> safeLong(line.getLineTotalMinor()) + safeLong(line.getTaxMinor()))
                .sum();
        po.setOpenBalanceMinor(revisedOpenBalance);

        po.getLines().clear();
        totalsAndLines.lines().forEach(line -> {
            line.setPurchaseOrder(po);
            po.getLines().add(line);
        });

        PurchaseOrderEntity saved = purchaseOrderRepository.save(po);
        eventPublisher.publishEvent(Map.of(
                EVENT_TYPE,
                "PurchaseOrderRevised",
                "poId",
                saved.getPurchaseOrderId().toString(),
                "priorVersion",
                priorVersion,
                "newVersion",
                saved.getVersionNumber(),
                "revisionReason",
                request.getRevisionReason(),
                "delta",
                delta,
                ACTOR_ID,
                actorId,
                OCCURRED_AT,
                Instant.now(clock).toString()));
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
        eventPublisher.publishEvent(Map.of(
                EVENT_TYPE,
                "PurchaseOrderCancelled",
                "poId",
                saved.getPurchaseOrderId().toString(),
                ACTOR_ID,
                actorId,
                OCCURRED_AT,
                Instant.now(clock).toString()));
        return toResponse(saved);
    }

    @Override
    @Transactional
    public @NonNull ReceivePurchaseOrderResponse receivePurchaseOrder(
            @NonNull UUID poId, @NonNull ReceivePurchaseOrderRequest request, @NonNull String actorId) {
        PurchaseOrderEntity po = getPoOrThrow(poId);
        validateReceivableStatus(po);
        Map<UUID, PurchaseOrderLineEntity> poLinesById = mapPoLinesById(po.getLines());
        long openBalanceMinor = applyReceiptLines(request.getLines(), poLinesById, safeLong(po.getOpenBalanceMinor()));
        updateReceiptStatus(po, openBalanceMinor);
        purchaseOrderRepository.save(po);
        publishReceiptEvent(po, actorId);
        List<ReceivePurchaseOrderResponse.ReceivePurchaseOrderLineDetail> lineDetails = buildReceiveLineDetails(po);

        return ReceivePurchaseOrderResponse.builder()
                .poId(po.getPurchaseOrderId())
                .status(po.getStatus())
                .openBalanceMinor(safeLong(po.getOpenBalanceMinor()))
                .message("Purchase order receipt recorded")
                .lines(lineDetails)
                .build();
    }

    private void validateReceivableStatus(PurchaseOrderEntity po) {
        if (po.getStatus() == PurchaseOrderStatus.DRAFT || po.getStatus() == PurchaseOrderStatus.CANCELLED) {
            throw new PurchaseOrderNotApprovedException("Purchase order must be approved before receiving");
        }
    }

    private Map<UUID, PurchaseOrderLineEntity> mapPoLinesById(List<PurchaseOrderLineEntity> lines) {
        Map<UUID, PurchaseOrderLineEntity> poLinesById = new HashMap<>();
        for (PurchaseOrderLineEntity line : lines) {
            if (line.getLineId() != null) {
                poLinesById.put(line.getLineId(), line);
            }
        }
        return poLinesById;
    }

    private long applyReceiptLines(
            List<ReceivePurchaseOrderRequest.ReceivePurchaseOrderLineRequest> lineRequests,
            Map<UUID, PurchaseOrderLineEntity> poLinesById,
            long openBalanceMinor) {
        long updatedOpenBalanceMinor = openBalanceMinor;
        for (ReceivePurchaseOrderRequest.ReceivePurchaseOrderLineRequest lineRequest : lineRequests) {
            PurchaseOrderLineEntity line = resolvePurchaseOrderLine(lineRequest.getLineId(), poLinesById);
            updateOpenQuantity(line, lineRequest.getQuantityReceived());
            long receivedValueMinor = calculateReceivedValueMinor(line, lineRequest);
            updatedOpenBalanceMinor = Math.max(0L, updatedOpenBalanceMinor - receivedValueMinor);
        }
        return updatedOpenBalanceMinor;
    }

    private PurchaseOrderLineEntity resolvePurchaseOrderLine(
            UUID lineId, Map<UUID, PurchaseOrderLineEntity> poLinesById) {
        PurchaseOrderLineEntity line = poLinesById.get(lineId);
        if (line != null) {
            return line;
        }
        return purchaseOrderLineRepository
                .findById(lineId)
                .orElseThrow(() -> new ResourceNotFoundException("PurchaseOrderLine", lineId.toString()));
    }

    private void updateOpenQuantity(PurchaseOrderLineEntity line, BigDecimal quantityReceived) {
        BigDecimal currentOpenQty = line.getOpenQuantityDecimal() == null ? BigDecimal.ZERO
                : line.getOpenQuantityDecimal();
        BigDecimal nextOpenQty = currentOpenQty.subtract(quantityReceived);
        line.setOpenQuantityDecimal(nextOpenQty.signum() < 0 ? BigDecimal.ZERO : nextOpenQty);
    }

    private long calculateReceivedValueMinor(
            PurchaseOrderLineEntity line, ReceivePurchaseOrderRequest.ReceivePurchaseOrderLineRequest lineRequest) {
        long effectiveUnitCost = lineRequest.getUnitCostMinor() == null
                ? safeLong(line.getUnitCostMinor())
                : lineRequest.getUnitCostMinor();
        return lineRequest
                .getQuantityReceived()
                .multiply(BigDecimal.valueOf(effectiveUnitCost))
                .setScale(0, RoundingMode.HALF_EVEN)
                .longValue();
    }

    private void updateReceiptStatus(PurchaseOrderEntity po, long openBalanceMinor) {
        if (isFullyReceived(po.getLines())) {
            po.setStatus(PurchaseOrderStatus.FULLY_RECEIVED);
            po.setOpenBalanceMinor(0L);
            return;
        }
        po.setStatus(PurchaseOrderStatus.PARTIALLY_RECEIVED);
        po.setOpenBalanceMinor(openBalanceMinor);
    }

    private boolean isFullyReceived(List<PurchaseOrderLineEntity> lines) {
        return lines.stream()
                .map(line -> line.getOpenQuantityDecimal() == null ? BigDecimal.ZERO : line.getOpenQuantityDecimal())
                .allMatch(openQty -> openQty.compareTo(BigDecimal.ZERO) <= 0);
    }

    private void publishReceiptEvent(PurchaseOrderEntity po, String actorId) {
        eventPublisher.publishEvent(Map.of(
                EVENT_TYPE,
                "PurchaseOrderReceiptEvent",
                "poId",
                po.getPurchaseOrderId().toString(),
                "status",
                po.getStatus().name(),
                "openBalanceMinor",
                safeLong(po.getOpenBalanceMinor()),
                ACTOR_ID,
                actorId,
                OCCURRED_AT,
                Instant.now(clock).toString()));
    }

    private List<ReceivePurchaseOrderResponse.ReceivePurchaseOrderLineDetail> buildReceiveLineDetails(
            PurchaseOrderEntity po) {
        return po.getLines().stream()
                .sorted(Comparator.comparing(PurchaseOrderLineEntity::getLineNumber))
                .map(line -> new ReceivePurchaseOrderResponse.ReceivePurchaseOrderLineDetail(
                        line.getLineId(),
                        line.getOpenQuantityDecimal() == null ? BigDecimal.ZERO : line.getOpenQuantityDecimal()))
                .toList();
    }

    private PurchaseOrderEntity getPoOrThrow(UUID poId) {
        return purchaseOrderRepository
                .findById(poId)
                .orElseThrow(() -> new ResourceNotFoundException("PurchaseOrder", poId.toString()));
    }

    private TotalsAndLines buildLineEntities(List<PurchaseOrderLineRequest> lineRequests) {
        List<PurchaseOrderLineEntity> lines = new ArrayList<>();
        long subtotalMinor = 0L;
        long totalTaxMinor = 0L;

        for (PurchaseOrderLineRequest lineRequest : lineRequests) {
            long lineTotalMinor = lineRequest
                    .getQuantity()
                    .multiply(BigDecimal.valueOf(lineRequest.getUnitCostMinor()))
                    .setScale(0, RoundingMode.HALF_EVEN)
                    .longValue();

            long lineTaxMinor = lineRequest.getTaxCodeId() == null ? 0L : Math.round(lineTotalMinor * defaultTaxRate);

            PurchaseOrderLineEntity line = PurchaseOrderLineEntity.builder()
                    .lineNumber(lineRequest.getLineNumber())
                    .skuId(lineRequest.getSkuId())
                    .description(lineRequest.getDescription())
                    .quantityDecimal(lineRequest.getQuantity())
                    .unitCostMinor(lineRequest.getUnitCostMinor())
                    .lineTotalMinor(lineTotalMinor)
                    .taxMinor(lineTaxMinor)
                    .taxCodeId(lineRequest.getTaxCodeId())
                    .glAccountId(lineRequest.getGlAccountId())
                    .openQuantityDecimal(lineRequest.getQuantity())
                    .build();
            lines.add(line);

            subtotalMinor += lineTotalMinor;
            totalTaxMinor += lineTaxMinor;
        }

        return new TotalsAndLines(lines, subtotalMinor, totalTaxMinor);
    }

    private PurchaseOrderResponse toResponse(PurchaseOrderEntity entity) {
        List<PurchaseOrderLineResponse> lineResponses = entity.getLines().stream()
                .sorted(Comparator.comparing(PurchaseOrderLineEntity::getLineNumber))
                .map(this::toLineResponse)
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
                .encumbranceRef(entity.getEncumbranceRef())
                .createdBy(entity.getCreatedBy())
                .updatedBy(entity.getUpdatedBy())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .lines(lineResponses)
                .build();
    }

    private List<Map<String, Object>> toApprovedLineItems(PurchaseOrderEntity entity) {
        return entity.getLines().stream()
                .sorted(Comparator.comparing(PurchaseOrderLineEntity::getLineNumber))
                .map(line -> {
                    Map<String, Object> item = new HashMap<>();
                    item.put(
                            "lineId",
                            line.getLineId() == null ? "" : line.getLineId().toString());
                    item.put(
                            "skuId",
                            line.getSkuId() == null ? "" : line.getSkuId().toString());
                    item.put("quantityDecimal", line.getQuantityDecimal());
                    item.put("unitCostMinor", safeLong(line.getUnitCostMinor()));
                    item.put("lineTotalMinor", safeLong(line.getLineTotalMinor()));
                    return item;
                })
                .toList();
    }

    private void addDelta(Map<String, Object> delta, String field, Object before, Object after) {
        if (!java.util.Objects.equals(before, after)) {
            Map<String, Object> changedValues = new HashMap<>();
            changedValues.put("before", before);
            changedValues.put("after", after);
            delta.put(field, changedValues);
        }
    }

    private PurchaseOrderLineResponse toLineResponse(PurchaseOrderLineEntity line) {
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
                .build();
    }

    private long safeLong(Long value) {
        return value == null ? 0L : value;
    }

    private String generatePoNumber() {
        long sequence = purchaseOrderRepository.getNextPurchaseOrderSequence();
        if (sequence < 1L || sequence > MAX_PO_NUMBER_SEQUENCE) {
            throw new IllegalStateException("Purchase order sequence is out of range for 8-character codes");
        }
        return String.format(Locale.ROOT, "%8s", Long.toString(sequence, 36)).replace(' ', '0')
                .toUpperCase(Locale.ROOT);
    }

    private record TotalsAndLines(List<PurchaseOrderLineEntity> lines, long subtotalMinor, long taxMinor) {
    }
}
