package com.positivity.order.internal.service;

import com.positivity.order.internal.client.CustomerLookupResult;
import com.positivity.order.internal.client.CustomerPort;
import com.positivity.order.internal.client.InventoryPort;
import com.positivity.order.internal.client.InventoryResult;
import com.positivity.order.internal.client.PricingPort;
import com.positivity.order.internal.client.PricingResult;
import com.positivity.order.internal.client.SourceDocumentLine;
import com.positivity.order.internal.client.SourceDocumentPort;
import com.positivity.order.internal.entity.*;
import com.positivity.order.internal.exception.CartIdempotencyConflictException;
import com.positivity.order.internal.exception.InvalidCustomerException;
import com.positivity.order.internal.exception.InvalidSkuException;
import com.positivity.order.internal.exception.SalesOrderNotFoundException;
import com.positivity.order.internal.repository.SalesOrderLineRepository;
import com.positivity.order.internal.repository.SalesOrderRepository;
import com.positivity.order.service.SalesOrderService;
import com.positivity.order.service.model.CreateCartCommand;
import com.positivity.order.service.model.CreateCartResult;
import com.positivity.order.service.model.SalesOrderLineSummary;
import com.positivity.order.service.model.SalesOrderSummary;
import com.positivity.security.common.SecurityContextHelper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SalesOrderServiceImpl implements SalesOrderService {

    private final SalesOrderRepository salesOrderRepository;
    private final SalesOrderLineRepository salesOrderLineRepository;
    private final PricingPort pricingPort;
    private final InventoryPort inventoryPort;
    private final SourceDocumentPort sourceDocumentPort;
    private final CustomerPort customerPort;
    private final OrderStateMachine orderStateMachine;
    private final OrderNumberService orderNumberService;

    @Override
    @Transactional
    public CreateCartResult createCart(CreateCartCommand command) {
        // Blank keys behave as "no idempotency key" — never looked up, never persisted (PR #1089
        // review): with the unique index, persisting "" would make unrelated requests collide.
        String idempotencyKey = normalizeBlank(command.idempotencyKey());
        if (idempotencyKey != null) {
            Optional<SalesOrder> existing = salesOrderRepository.findByCreationIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                validateCreateReplay(existing.get(), command);
                return new CreateCartResult(toSummary(existing.get()), true);
            }
        }

        UUID customerId = parseReference(command.customerId(), "customerId");
        UUID vehicleId = parseReference(command.vehicleId(), "vehicleId");
        CustomerValidationStatus validationStatus = validateCustomerAndVehicle(customerId, vehicleId);

        String actor = SecurityContextHelper.getCurrentUsernameOrDefault("system");
        SalesOrder order = SalesOrder.builder()
                .orderNumber(orderNumberService.nextNumber(command.locationId()))
                .locationId(command.locationId())
                .label(normalizeBlank(command.label()))
                .clerkId(command.clerkId())
                .terminalId(command.terminalId())
                .customerId(customerId)
                .vehicleId(vehicleId)
                .customerValidationStatus(validationStatus)
                .creationIdempotencyKey(idempotencyKey)
                .status(SalesOrderStatus.DRAFT)
                .subtotal(BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP))
                .createdBy(actor)
                .updatedBy(actor)
                .build();
        SalesOrder saved = salesOrderRepository.save(order);
        orderStateMachine.recordCreation(saved);
        return new CreateCartResult(toSummary(saved), false);
    }

    @Override
    @Transactional
    public SalesOrderLineSummary addItem(
            UUID orderId,
            String itemSku,
            int quantity,
            String reasonCode,
            BigDecimal manualPrice,
            UUID clientLineUuid) {
        SalesOrder order =
                salesOrderRepository.findById(orderId).orElseThrow(() -> new SalesOrderNotFoundException(orderId));
        orderStateMachine.requireEditable(order);

        if (clientLineUuid != null) {
            Optional<SalesOrderLine> replayed =
                    salesOrderLineRepository.findByOrder_OrderIdAndClientLineUuid(orderId, clientLineUuid);
            if (replayed.isPresent()) {
                return replayAddItem(order, replayed.get(), itemSku, quantity);
            }
        }

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
        FulfillmentStatus fulfillmentStatus =
                inventoryResult.sufficient() ? FulfillmentStatus.AVAILABLE : FulfillmentStatus.BACKORDER;

        SalesOrderLine line = SalesOrderLine.builder()
                .order(order)
                .itemSku(itemSku)
                .itemDescription(itemSku)
                .quantity(quantity)
                .unitPrice(unitPrice)
                .priceSource(priceSource)
                .fulfillmentStatus(fulfillmentStatus)
                .reasonCode(reasonCode)
                .clientLineUuid(clientLineUuid)
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
    @Transactional
    public SalesOrderLineSummary updateItemQuantity(UUID orderId, UUID lineId, int newQuantity) {
        SalesOrder order =
                salesOrderRepository.findById(orderId).orElseThrow(() -> new SalesOrderNotFoundException(orderId));
        orderStateMachine.requireEditable(order);
        SalesOrderLine line = salesOrderLineRepository
                .findById(lineId)
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
    @Transactional
    public void removeItem(UUID orderId, UUID lineId) {
        SalesOrder order =
                salesOrderRepository.findById(orderId).orElseThrow(() -> new SalesOrderNotFoundException(orderId));
        orderStateMachine.requireEditable(order);

        order.getLines().removeIf(l -> lineId.equals(l.getOrderLineId()));
        order.setSubtotal(recalculateSubtotal(order.getLines()));
        String actor = SecurityContextHelper.getCurrentUsernameOrDefault("system");
        order.setUpdatedBy(actor);
        salesOrderRepository.save(order);
    }

    @Override
    public SalesOrderSummary getOrder(UUID orderId) {
        return toSummary(
                salesOrderRepository.findById(orderId).orElseThrow(() -> new SalesOrderNotFoundException(orderId)));
    }

    @Override
    public List<SalesOrderSummary> listCarts(String clerkId, String terminalId, String status, int page, int size) {
        SalesOrderStatus statusFilter = normalizeBlank(status) == null ? null : SalesOrderStatus.valueOf(status.trim());
        int pageSize = Math.min(Math.max(size, 1), 100);
        return salesOrderRepository
                .search(
                        normalizeBlank(clerkId),
                        normalizeBlank(terminalId),
                        statusFilter,
                        PageRequest.of(Math.max(page, 0), pageSize))
                .map(this::toListSummary)
                .getContent();
    }

    @Override
    @Transactional
    public SalesOrderSummary linkSource(UUID orderId, String sourceType, String sourceId) {
        SourceType type = SourceType.valueOf(sourceType);

        SalesOrder order =
                salesOrderRepository.findById(orderId).orElseThrow(() -> new SalesOrderNotFoundException(orderId));
        orderStateMachine.requireEditable(order);

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

    private SalesOrderLineSummary replayAddItem(SalesOrder order, SalesOrderLine line, String itemSku, int quantity) {
        if (!line.getItemSku().equals(itemSku)) {
            throw new CartIdempotencyConflictException("Client line uuid " + line.getClientLineUuid()
                    + " was previously used for SKU " + line.getItemSku() + ", not " + itemSku);
        }
        if (line.getQuantity() != quantity) {
            // Odoo _process_order analog: a replayed CREATE with a known uuid becomes an UPDATE.
            line.setQuantity(quantity);
            salesOrderLineRepository.save(line);
            order.setSubtotal(recalculateSubtotal(order.getLines()));
            order.setUpdatedBy(SecurityContextHelper.getCurrentUsernameOrDefault("system"));
            salesOrderRepository.save(order);
        }
        return toLineSummary(line);
    }

    private void validateCreateReplay(SalesOrder existing, CreateCartCommand command) {
        // Compare normalized references, not raw strings: a replay sending "" where the original
        // sent null is semantically identical (PR #1089 review).
        boolean matches = existing.getClerkId().equals(command.clerkId())
                && existing.getTerminalId().equals(command.terminalId())
                && Objects.equals(existing.getLocationId(), command.locationId())
                && Objects.equals(existing.getLabel(), normalizeBlank(command.label()))
                && Objects.equals(existing.getCustomerId(), parseReference(command.customerId(), "customerId"))
                && Objects.equals(existing.getVehicleId(), parseReference(command.vehicleId(), "vehicleId"));
        if (!matches) {
            throw new CartIdempotencyConflictException(
                    "Idempotency key was previously used with a different cart-creation payload");
        }
    }

    private CustomerValidationStatus validateCustomerAndVehicle(UUID customerId, UUID vehicleId) {
        if (customerId == null) {
            // A vehicle without a customer cannot be validated against CRM (vehicles are
            // customer-scoped); it is recorded as-is.
            return null;
        }
        CustomerLookupResult customer = customerPort.lookupCustomer(customerId);
        if (customer == CustomerLookupResult.NOT_FOUND) {
            throw new InvalidCustomerException("Customer not found in CRM: " + customerId);
        }
        CustomerLookupResult vehicle = CustomerLookupResult.FOUND;
        if (vehicleId != null) {
            vehicle = customerPort.lookupVehicle(customerId, vehicleId);
            if (vehicle == CustomerLookupResult.NOT_FOUND) {
                throw new InvalidCustomerException("Vehicle " + vehicleId + " not found for customer " + customerId);
            }
        }
        boolean pending = customer == CustomerLookupResult.UNAVAILABLE || vehicle == CustomerLookupResult.UNAVAILABLE;
        return pending ? CustomerValidationStatus.PENDING : CustomerValidationStatus.VALIDATED;
    }

    private static String normalizeBlank(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }

    private static UUID parseReference(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new InvalidCustomerException(field + " must be a UUID: " + value);
        }
    }

    private static String asString(UUID value) {
        return value == null ? null : value.toString();
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

    private SalesOrderSummary toListSummary(SalesOrder order) {
        return toSummary(order, List.of());
    }

    private SalesOrderSummary toSummary(SalesOrder order) {
        List<SalesOrderLineSummary> lines = order.getLines().stream()
                .filter(Objects::nonNull)
                .map(this::toLineSummary)
                .toList();
        return toSummary(order, lines);
    }

    private SalesOrderSummary toSummary(SalesOrder order, List<SalesOrderLineSummary> lines) {
        return new SalesOrderSummary(
                order.getOrderId().toString(),
                order.getOrderNumber(),
                asString(order.getLocationId()),
                order.getLabel(),
                asString(order.getCustomerId()),
                asString(order.getVehicleId()),
                order.getCustomerValidationStatus() != null
                        ? order.getCustomerValidationStatus().name()
                        : null,
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
