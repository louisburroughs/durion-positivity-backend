package com.positivity.order.internal.service;

import com.positivity.order.internal.client.InventoryPort;
import com.positivity.order.internal.client.InventoryResult;
import com.positivity.order.internal.client.PricingPort;
import com.positivity.order.internal.client.PricingResult;
import com.positivity.order.internal.client.SourceDocumentLine;
import com.positivity.order.internal.client.SourceDocumentPort;
import com.positivity.order.internal.entity.*;
import com.positivity.order.internal.exception.InvalidSkuException;
import com.positivity.order.internal.exception.SalesOrderNotFoundException;
import com.positivity.order.internal.repository.SalesOrderLineRepository;
import com.positivity.order.internal.repository.SalesOrderRepository;
import com.positivity.order.service.SalesOrderService;
import com.positivity.order.service.model.SalesOrderLineSummary;
import com.positivity.order.service.model.SalesOrderSummary;
import com.positivity.security.common.SecurityContextHelper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SalesOrderServiceImpl implements SalesOrderService {

    private final SalesOrderRepository salesOrderRepository;
    private final SalesOrderLineRepository salesOrderLineRepository;
    private final PricingPort pricingPort;
    private final InventoryPort inventoryPort;
    private final SourceDocumentPort sourceDocumentPort;

    @Override
    public SalesOrderSummary createCart(String clerkId, String terminalId, String customerId, String vehicleId) {
        String actor = SecurityContextHelper.getCurrentUsernameOrDefault("system");
        SalesOrder order = SalesOrder.builder()
                .clerkId(clerkId)
                .terminalId(terminalId)
                .customerId(customerId)
                .vehicleId(vehicleId)
                .status(SalesOrderStatus.DRAFT)
                .subtotal(BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP))
                .createdBy(actor)
                .updatedBy(actor)
                .build();
        return toSummary(salesOrderRepository.save(order));
    }

    @Override
    public SalesOrderLineSummary addItem(UUID orderId, String itemSku, int quantity, String reasonCode,
            BigDecimal manualPrice) {
        SalesOrder order = salesOrderRepository.findById(orderId)
                .orElseThrow(() -> new SalesOrderNotFoundException(orderId));

        BigDecimal unitPrice;
        PriceSource priceSource;

        if (manualPrice != null) {
            if (!SecurityContextHelper.hasAuthority("order:line:enter_manual_price")) {
                throw new AccessDeniedException(
                        "Permission 'order:line:enter_manual_price' is required to enter a manual price");
            }
            unitPrice = manualPrice.setScale(4, RoundingMode.HALF_UP);
            priceSource = PriceSource.MANUAL;
        } else {
            PricingResult pricingResult = pricingPort.resolvePrice(itemSku);
            if (!pricingResult.found()) {
                throw new InvalidSkuException("Product not found for SKU: " + itemSku);
            }
            unitPrice = pricingResult.price().setScale(4, RoundingMode.HALF_UP);
            priceSource = pricingResult.stale() ? PriceSource.CACHE : PriceSource.PRICING_SERVICE;
        }

        InventoryResult inventoryResult = inventoryPort.checkAvailability(itemSku, quantity);
        FulfillmentStatus fulfillmentStatus = inventoryResult.sufficient()
                ? FulfillmentStatus.AVAILABLE
                : FulfillmentStatus.BACKORDER;

        SalesOrderLine line = SalesOrderLine.builder()
                .order(order)
                .itemSku(itemSku)
                .itemDescription(itemSku)
                .quantity(quantity)
                .unitPrice(unitPrice)
                .priceSource(priceSource)
                .fulfillmentStatus(fulfillmentStatus)
                .reasonCode(reasonCode)
                .build();

        SalesOrderLine saved = salesOrderLineRepository.save(line);
        order.getLines().add(saved);
        order.setSubtotal(recalculateSubtotal(order.getLines()));
        String actor = SecurityContextHelper.getCurrentUsernameOrDefault("system");
        order.setUpdatedBy(actor);
        salesOrderRepository.save(order);
        return toLineSummary(saved);
    }

    @Override
    public SalesOrderLineSummary updateItemQuantity(UUID orderId, UUID lineId, int newQuantity) {
        SalesOrder order = salesOrderRepository.findById(orderId)
                .orElseThrow(() -> new SalesOrderNotFoundException(orderId));
        SalesOrderLine line = salesOrderLineRepository.findById(lineId)
                .orElseThrow(() -> new SalesOrderNotFoundException("Order line not found: " + lineId));

        line.setQuantity(newQuantity);
        SalesOrderLine saved = salesOrderLineRepository.save(line);

        order.getLines().removeIf(l -> lineId.equals(l.getOrderLineId()));
        order.getLines().add(saved);
        order.setSubtotal(recalculateSubtotal(order.getLines()));
        String actor = SecurityContextHelper.getCurrentUsernameOrDefault("system");
        order.setUpdatedBy(actor);
        salesOrderRepository.save(order);
        return toLineSummary(saved);
    }

    @Override
    public void removeItem(UUID orderId, UUID lineId) {
        SalesOrder order = salesOrderRepository.findById(orderId)
                .orElseThrow(() -> new SalesOrderNotFoundException(orderId));

        order.getLines().removeIf(l -> lineId.equals(l.getOrderLineId()));
        order.setSubtotal(recalculateSubtotal(order.getLines()));
        String actor = SecurityContextHelper.getCurrentUsernameOrDefault("system");
        order.setUpdatedBy(actor);
        salesOrderRepository.save(order);
    }

    @Override
    public SalesOrderSummary getOrder(UUID orderId) {
        return toSummary(salesOrderRepository.findById(orderId)
                .orElseThrow(() -> new SalesOrderNotFoundException(orderId)));
    }

    @Override
    public SalesOrderSummary linkSource(UUID orderId, String sourceType, String sourceId) {
        SourceType type = SourceType.valueOf(sourceType);

        SalesOrder order = salesOrderRepository.findById(orderId)
                .orElseThrow(() -> new SalesOrderNotFoundException(orderId));

        if (order.getCustomerId() == null && SourceType.WORKORDER.equals(type)) {
            throw new IllegalStateException(
                    "Cannot link source: a customer must be assigned to this cart before linking a WORKORDER source");
        }

        Set<String> alreadyLinkedKeys = order.getLines().stream()
                .filter(l -> sourceId.equals(l.getSourceId()))
                .map(l -> l.getSourceId() + "|" + l.getSourceLineId())
                .collect(Collectors.toSet());

        List<SourceDocumentLine> sourceLines = sourceDocumentPort.fetchLines(type, sourceId);

        for (SourceDocumentLine sourceLine : sourceLines) {
            String linkKey = sourceId + "|" + sourceLine.sourceLineId();
            if (alreadyLinkedKeys.contains(linkKey)) {
                continue;
            }

            boolean merged = false;
            for (SalesOrderLine existingLine : order.getLines()) {
                if (existingLine.getItemSku().equals(sourceLine.itemSku())
                        && existingLine.getUnitPrice().compareTo(sourceLine.unitPrice()) == 0
                        && (existingLine.getSourceId() == null || sourceId.equals(existingLine.getSourceId()))) {
                    existingLine.setQuantity(existingLine.getQuantity() + sourceLine.quantity());
                    if (existingLine.getSourceId() == null) {
                        existingLine.setSourceType(type);
                        existingLine.setSourceId(sourceId);
                        existingLine.setSourceLineId(sourceLine.sourceLineId());
                    }
                    salesOrderLineRepository.save(existingLine);
                    merged = true;
                    break;
                }
            }

            if (!merged) {
                SalesOrderLine newLine = SalesOrderLine.builder()
                        .order(order)
                        .itemSku(sourceLine.itemSku())
                        .itemDescription(sourceLine.itemDescription())
                        .quantity(sourceLine.quantity())
                        .unitPrice(sourceLine.unitPrice().setScale(4, RoundingMode.HALF_UP))
                        .priceSource(PriceSource.PRICING_SERVICE)
                        .fulfillmentStatus(FulfillmentStatus.AVAILABLE)
                        .sourceType(type)
                        .sourceId(sourceId)
                        .sourceLineId(sourceLine.sourceLineId())
                        .build();
                SalesOrderLine savedNewLine = salesOrderLineRepository.save(newLine);
                order.getLines().add(savedNewLine);
            }
        }

        order.setSubtotal(recalculateSubtotal(order.getLines()));
        String actor = SecurityContextHelper.getCurrentUsernameOrDefault("system");
        order.setUpdatedBy(actor);
        return toSummary(salesOrderRepository.save(order));
    }

    private BigDecimal recalculateSubtotal(List<SalesOrderLine> lines) {
        return lines.stream()
                .filter(Objects::nonNull)
                .map(l -> l.getUnitPrice().multiply(BigDecimal.valueOf(l.getQuantity())))
                .reduce(BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP), BigDecimal::add);
    }

    private SalesOrderLineSummary toLineSummary(SalesOrderLine line) {
        return new SalesOrderLineSummary(
                line.getOrderLineId().toString(),
                line.getItemSku(),
                line.getItemDescription(),
                line.getQuantity(),
                line.getUnitPrice(),
                line.getFulfillmentStatus().name(),
                line.getPriceSource().name(),
                line.getReasonCode(),
                line.getSourceType() != null ? line.getSourceType().name() : null,
                line.getSourceId(),
                line.getSourceLineId());
    }

    private SalesOrderSummary toSummary(SalesOrder order) {
        List<SalesOrderLineSummary> lines = order.getLines().stream()
                .filter(Objects::nonNull)
                .map(this::toLineSummary)
                .toList();
        return new SalesOrderSummary(
                order.getOrderId().toString(),
                order.getCustomerId(),
                order.getVehicleId(),
                order.getClerkId(),
                order.getTerminalId(),
                order.getStatus().name(),
                order.getSubtotal(),
                order.getCreatedAt(),
                order.getUpdatedAt(),
                order.getCreatedBy(),
                order.getUpdatedBy(),
                lines);
    }
}
