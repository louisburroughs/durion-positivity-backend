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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PurchaseOrderServiceImpl implements PurchaseOrderService {

    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseOrderLineRepository purchaseOrderLineRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ApplicationContext applicationContext;

    @Value("${pos.inventory.encumbranceEnabled:false}")
    private boolean encumbranceEnabled;

    @Override
    @Transactional
    public @NonNull PurchaseOrderResponse createPurchaseOrder(@NonNull CreatePurchaseOrderRequest request,
            @NonNull String actorId) {
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
                .createdBy(actorId)
                .build();

        List<PurchaseOrderLineEntity> lines = totalsAndLines.lines();
        lines.forEach(line -> line.setPurchaseOrder(purchaseOrder));
        purchaseOrder.setLines(lines);

        PurchaseOrderEntity saved = purchaseOrderRepository.save(purchaseOrder);
        eventPublisher.publishEvent(Map.of(
                "eventType", "PurchaseOrderCreated",
                "poId", saved.getPurchaseOrderId().toString(),
                "vendorId", saved.getVendorId().toString(),
                "actorId", actorId,
                "occurredAt", Instant.now().toString()));
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
    public @NonNull Page<PurchaseOrderResponse> listPurchaseOrders(@NonNull ListPurchaseOrdersRequest filter,
            @NonNull Pageable pageable) {
        if (filter.getVendorId() != null && filter.getStatus() != null) {
            List<PurchaseOrderResponse> content = purchaseOrderRepository
                    .findByVendorIdAndStatus(filter.getVendorId(), filter.getStatus())
                    .stream()
                    .map(this::toResponse)
                    .toList();
            return new PageImpl<>(page(content, pageable), pageable, content.size());
        }

        Page<PurchaseOrderEntity> page;
        if (filter.getStatus() != null) {
            page = purchaseOrderRepository.findAllByStatus(filter.getStatus(), pageable);
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
    public @NonNull PurchaseOrderResponse approvePurchaseOrder(@NonNull UUID poId,
            @NonNull ApprovePurchaseOrderRequest request,
            @NonNull String actorId) {
        PurchaseOrderEntity po = getPoOrThrow(poId);
        if (po.getStatus() != PurchaseOrderStatus.DRAFT) {
            throw new IllegalStateException("Only DRAFT purchase orders can be approved");
        }

        po.setStatus(PurchaseOrderStatus.APPROVED);
        po.setApproverId(actorId);
        po.setApprovalTimestamp(Instant.now());
        po.setApprovalNotes(request.getApprovalNotes());
        if (encumbranceEnabled) {
            po.setEncumbranceRef("ENC-" + po.getPurchaseOrderId().toString().substring(0, 8).toUpperCase());
        }

        PurchaseOrderEntity saved = purchaseOrderRepository.save(po);

        eventPublisher.publishEvent(Map.of(
                "eventType", "PurchaseOrderApproved",
                "poId", saved.getPurchaseOrderId().toString(),
                "vendorId", saved.getVendorId().toString(),
                "totalAmountMinor", saved.getGrandTotalMinor(),
                "currency", saved.getCurrency(),
                "lineItems", toApprovedLineItems(saved),
                "approvalStatus", saved.getStatus().name(),
                "actorId", actorId,
                "occurredAt", Instant.now().toString()));

        if (encumbranceEnabled) {
            applicationContext.getBean(PurchaseOrderServiceImpl.class).publishEncumbranceEvent(saved, actorId);
        }

        return toResponse(saved);
    }

    @Retryable(maxAttempts = 3, backoff = @Backoff(delay = 500, multiplier = 2.0))
    public void publishEncumbranceEvent(@NonNull PurchaseOrderEntity po, @NonNull String actorId) {
        eventPublisher.publishEvent(Map.of(
                "eventType", "PurchaseOrderEncumbranceEvent",
                "poId", po.getPurchaseOrderId().toString(),
                "encumbranceRef", po.getEncumbranceRef() == null ? "" : po.getEncumbranceRef(),
                "amountMinor", safeLong(po.getGrandTotalMinor()),
                "currency", po.getCurrency(),
                "actorId", actorId,
                "occurredAt", Instant.now().toString()));
    }

    @Recover
    public void recoverPublishEncumbranceEvent(RuntimeException ex, @NonNull PurchaseOrderEntity po,
            @NonNull String actorId) {
        eventPublisher.publishEvent(Map.of(
                "eventType", "PurchaseOrderAccountingError",
                "poId", po.getPurchaseOrderId().toString(),
                "failureReason", ex.getMessage() == null ? "Unknown accounting error" : ex.getMessage(),
                "retryCount", 3,
                "timestamp", Instant.now().toString(),
                "actorId", actorId));
        throw ex;
    }

    @Override
    @Transactional
    public @NonNull PurchaseOrderResponse revisePurchaseOrder(@NonNull UUID poId,
            @NonNull RevisePurchaseOrderRequest request,
            @NonNull String actorId) {
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
        addDelta(delta, "grandTotalMinor", priorGrandTotalMinor,
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
                "eventType", "PurchaseOrderRevised",
                "poId", saved.getPurchaseOrderId().toString(),
                "priorVersion", priorVersion,
                "newVersion", saved.getVersionNumber(),
                "revisionReason", request.getRevisionReason(),
                "delta", delta,
                "actorId", actorId,
                "occurredAt", Instant.now().toString()));
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
                "eventType", "PurchaseOrderCancelled",
                "poId", saved.getPurchaseOrderId().toString(),
                "actorId", actorId,
                "occurredAt", Instant.now().toString()));
        return toResponse(saved);
    }

    @Override
    @Transactional
    public @NonNull ReceivePurchaseOrderResponse receivePurchaseOrder(@NonNull UUID poId,
            @NonNull ReceivePurchaseOrderRequest request,
            @NonNull String actorId) {
        PurchaseOrderEntity po = getPoOrThrow(poId);
        if (po.getStatus() == PurchaseOrderStatus.DRAFT || po.getStatus() == PurchaseOrderStatus.CANCELLED) {
            throw new PurchaseOrderNotApprovedException("Purchase order must be approved before receiving");
        }

        Map<UUID, PurchaseOrderLineEntity> poLinesById = new HashMap<>();
        for (PurchaseOrderLineEntity line : po.getLines()) {
            if (line.getLineId() != null) {
                poLinesById.put(line.getLineId(), line);
            }
        }

        long openBalanceMinor = safeLong(po.getOpenBalanceMinor());
        for (ReceivePurchaseOrderRequest.ReceivePurchaseOrderLineRequest lineRequest : request.getLines()) {
            PurchaseOrderLineEntity line = poLinesById.get(lineRequest.getLineId());
            if (line == null) {
                line = purchaseOrderLineRepository.findById(lineRequest.getLineId())
                        .orElseThrow(() -> new ResourceNotFoundException("PurchaseOrderLine",
                                lineRequest.getLineId().toString()));
            }

            BigDecimal currentOpenQty = line.getOpenQuantityDecimal() == null ? BigDecimal.ZERO
                    : line.getOpenQuantityDecimal();
            BigDecimal nextOpenQty = currentOpenQty.subtract(lineRequest.getQuantityReceived());
            if (nextOpenQty.signum() < 0) {
                nextOpenQty = BigDecimal.ZERO;
            }
            line.setOpenQuantityDecimal(nextOpenQty);

            long effectiveUnitCost = lineRequest.getUnitCostMinor() == null
                    ? safeLong(line.getUnitCostMinor())
                    : lineRequest.getUnitCostMinor();
            long receivedValueMinor = lineRequest.getQuantityReceived()
                    .multiply(BigDecimal.valueOf(effectiveUnitCost))
                    .setScale(0, RoundingMode.HALF_EVEN)
                    .longValue();
            openBalanceMinor = Math.max(0L, openBalanceMinor - receivedValueMinor);
        }

        boolean allReceived = po.getLines().stream()
                .allMatch(line -> {
                    BigDecimal openQty = line.getOpenQuantityDecimal() == null ? BigDecimal.ZERO
                            : line.getOpenQuantityDecimal();
                    return openQty.compareTo(BigDecimal.ZERO) <= 0;
                });

        if (allReceived) {
            po.setStatus(PurchaseOrderStatus.FULLY_RECEIVED);
            po.setOpenBalanceMinor(0L);
        } else {
            po.setStatus(PurchaseOrderStatus.PARTIALLY_RECEIVED);
            po.setOpenBalanceMinor(openBalanceMinor);
        }

        purchaseOrderRepository.save(po);
        eventPublisher.publishEvent(Map.of(
                "eventType", "PurchaseOrderReceiptEvent",
                "poId", po.getPurchaseOrderId().toString(),
                "status", po.getStatus().name(),
                "openBalanceMinor", safeLong(po.getOpenBalanceMinor()),
                "actorId", actorId,
                "occurredAt", Instant.now().toString()));

        List<ReceivePurchaseOrderResponse.ReceivePurchaseOrderLineDetail> lineDetails = po.getLines().stream()
                .sorted(Comparator.comparing(PurchaseOrderLineEntity::getLineNumber))
                .map(line -> new ReceivePurchaseOrderResponse.ReceivePurchaseOrderLineDetail(
                        line.getLineId(),
                        line.getOpenQuantityDecimal() == null ? BigDecimal.ZERO : line.getOpenQuantityDecimal()))
                .toList();

        return ReceivePurchaseOrderResponse.builder()
                .poId(po.getPurchaseOrderId())
                .status(po.getStatus())
                .openBalanceMinor(safeLong(po.getOpenBalanceMinor()))
                .message("Purchase order receipt recorded")
                .lines(lineDetails)
                .build();
    }

    private PurchaseOrderEntity getPoOrThrow(UUID poId) {
        return purchaseOrderRepository.findById(poId)
                .orElseThrow(() -> new ResourceNotFoundException("PurchaseOrder", poId.toString()));
    }

    private TotalsAndLines buildLineEntities(List<PurchaseOrderLineRequest> lineRequests) {
        List<PurchaseOrderLineEntity> lines = new ArrayList<>();
        long subtotalMinor = 0L;
        long totalTaxMinor = 0L;

        for (PurchaseOrderLineRequest lineRequest : lineRequests) {
            long lineTotalMinor = lineRequest.getQuantity()
                    .multiply(BigDecimal.valueOf(lineRequest.getUnitCostMinor()))
                    .setScale(0, RoundingMode.HALF_EVEN)
                    .longValue();

            long lineTaxMinor = lineRequest.getTaxCodeId() == null
                    ? 0L
                    : Math.round(lineTotalMinor * 0.10d);

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
                    item.put("lineId", line.getLineId() == null ? "" : line.getLineId().toString());
                    item.put("skuId", line.getSkuId() == null ? "" : line.getSkuId().toString());
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

    private List<PurchaseOrderResponse> page(List<PurchaseOrderResponse> source, Pageable pageable) {
        int start = Math.toIntExact(pageable.getOffset());
        if (start >= source.size()) {
            return List.of();
        }
        int end = Math.min(start + pageable.getPageSize(), source.size());
        return source.subList(start, end);
    }

    private long safeLong(Long value) {
        return value == null ? 0L : value;
    }

    private String generatePoNumber() {
        return "PO-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private record TotalsAndLines(List<PurchaseOrderLineEntity> lines, long subtotalMinor, long taxMinor) {
    }
}
