package com.positivity.order.internal.service;

import com.positivity.order.internal.client.CustomerLookupResult;
import com.positivity.order.internal.client.CustomerPort;
import com.positivity.order.internal.client.InventoryPort;
import com.positivity.order.internal.client.InventoryResult;
import com.positivity.order.internal.client.InvoiceRef;
import com.positivity.order.internal.client.InvoicingPort;
import com.positivity.order.internal.client.PricingPort;
import com.positivity.order.internal.client.PricingQuote;
import com.positivity.order.internal.client.SourceDocumentLine;
import com.positivity.order.internal.client.SourceDocumentPort;
import com.positivity.order.internal.config.InventoryCommandPublisher;
import com.positivity.order.internal.config.OrderDomainEventPublisher;
import com.positivity.order.internal.entity.*;
import com.positivity.order.internal.exception.CartIdempotencyConflictException;
import com.positivity.order.internal.exception.InvalidCustomerException;
import com.positivity.order.internal.exception.InvalidSkuException;
import com.positivity.order.internal.exception.OrderVoidBlockedException;
import com.positivity.order.internal.exception.SalesOrderNotFoundException;
import com.positivity.order.internal.repository.ExtBillingRulesRepository;
import com.positivity.order.internal.repository.ExtCustomerRepository;
import com.positivity.order.internal.repository.ExtProductRepository;
import com.positivity.order.internal.repository.OrderPaymentRecordRepository;
import com.positivity.order.internal.repository.RegisterSessionRepository;
import com.positivity.order.internal.repository.SalesOrderLineRepository;
import com.positivity.order.internal.repository.SalesOrderRepository;
import com.positivity.order.service.SalesOrderService;
import com.positivity.order.service.model.AddItemCommand;
import com.positivity.order.service.model.CheckoutResult;
import com.positivity.order.service.model.CreateCartCommand;
import com.positivity.order.service.model.CreateCartResult;
import com.positivity.order.service.model.OrderDiscountCommand;
import com.positivity.order.service.model.SalesOrderLineSummary;
import com.positivity.order.service.model.SalesOrderSummary;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.shared.dto.OrderInvoiceCreationRequest;
import com.positivity.shared.dto.OrderInvoiceLineItem;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class SalesOrderServiceImpl implements SalesOrderService {

    private final SalesOrderRepository salesOrderRepository;
    private final SalesOrderLineRepository salesOrderLineRepository;
    private final PricingPort pricingPort;
    private final InventoryPort inventoryPort;
    private final SourceDocumentPort sourceDocumentPort;
    private final CustomerPort customerPort;
    private final InvoicingPort invoicingPort;
    private final ExtProductRepository extProductRepository;
    private final ExtCustomerRepository extCustomerRepository;
    private final ExtBillingRulesRepository extBillingRulesRepository;
    private final OrderPaymentRecordRepository paymentRecordRepository;
    private final RegisterSessionRepository registerSessionRepository;
    private final OrderDomainEventPublisher domainEventPublisher;
    private final OrderStateMachine orderStateMachine;
    private final OrderNumberService orderNumberService;
    private final OrderTotalsCalculator totalsCalculator;
    private final OrderTaxService orderTaxService;

    /**
     * Absent when this module runs with Kafka disabled (local/dev profiles). Checkout still
     * labels lines from availability in that case; it simply registers no demand, which is the
     * behaviour every profile had before CAP #1315.
     */
    private final ObjectProvider<InventoryCommandPublisher> inventoryCommandPublisher;

    private final Clock clock;

    /** Quote validity horizon (parity story A3); ISO-8601 duration. */
    @Value("${pos.order.quote.validity:P7D}")
    private Duration quoteValidity;

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

        // Story E4: deposit provenance is all-or-nothing — reject a half-specified source pair
        // (Copilot #1093 review) so an order never carries incomplete deposit provenance.
        String depositSourceType = normalizeBlank(command.depositSourceType());
        if ((depositSourceType == null) != (command.depositSourceId() == null)) {
            throw new IllegalArgumentException(
                    "depositSourceType and depositSourceId must be provided together for a deposit take");
        }

        // Story G1: orders created while a session is open on the terminal bind to it, and the
        // session supplies the location when the caller did not (A2's param is the fallback). A
        // terminal being counted at close (CLOSING) is frozen — no new orders (Copilot #1093 review).
        if (registerSessionRepository
                .findFirstByTerminalIdAndStatus(command.terminalId(), RegisterSessionStatus.CLOSING)
                .isPresent()) {
            throw new IllegalStateException("Terminal " + command.terminalId()
                    + " has a register session being closed; no new orders until the close completes");
        }
        RegisterSession openSession = registerSessionRepository
                .findFirstByTerminalIdAndStatus(command.terminalId(), RegisterSessionStatus.OPEN)
                .orElse(null);
        UUID sessionId = openSession != null ? openSession.getSessionId() : null;
        UUID locationId = command.locationId() != null
                ? command.locationId()
                : (openSession != null ? openSession.getLocationId() : null);
        if (locationId == null) {
            throw new IllegalArgumentException(
                    "locationId is required when no register session is open on the terminal");
        }

        String actor = SecurityContextHelper.getCurrentUsernameOrDefault("system");
        SalesOrder order = SalesOrder.builder()
                .orderNumber(orderNumberService.nextNumber(locationId))
                .locationId(locationId)
                .label(normalizeBlank(command.label()))
                .generalNote(normalizeBlank(command.generalNote()))
                .clerkId(command.clerkId())
                .terminalId(command.terminalId())
                .sessionId(sessionId)
                .depositSourceType(depositSourceType)
                .depositSourceId(command.depositSourceId())
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
    public SalesOrderLineSummary addItem(UUID orderId, AddItemCommand command) {
        SalesOrder order =
                salesOrderRepository.findById(orderId).orElseThrow(() -> new SalesOrderNotFoundException(orderId));
        orderStateMachine.requireEditable(order);

        if (command.clientLineUuid() != null) {
            Optional<SalesOrderLine> replayed =
                    salesOrderLineRepository.findByOrder_OrderIdAndClientLineUuid(orderId, command.clientLineUuid());
            if (replayed.isPresent()) {
                return replayAddItem(order, replayed.get(), command.itemSku(), command.quantity());
            }
        }

        BigDecimal unitPrice;
        PriceSource priceSource;
        String description = command.itemSku();
        String breakdownJson = null;

        if (command.manualPrice() != null) {
            if (!SecurityContextHelper.hasAuthority("order:line:enter_manual_price")) {
                throw new AccessDeniedException(
                        "Permission 'order:line:enter_manual_price' is required to enter a manual price");
            }
            unitPrice = command.manualPrice().setScale(4, RoundingMode.HALF_UP);
            priceSource = PriceSource.MANUAL;
        } else {
            PricingQuote pricingQuote = pricingPort.quoteForSku(
                    command.itemSku(), command.quantity(), order.getLocationId(), order.getCustomerId());
            switch (pricingQuote.status()) {
                case UNKNOWN_SKU -> throw new InvalidSkuException("Product not found for SKU: " + command.itemSku());
                case UNAVAILABLE ->
                    throw new IllegalStateException("Pricing is unavailable for SKU " + command.itemSku()
                            + "; retry, or enter a manual price (requires order:line:enter_manual_price)");
                case PRICED -> {
                    /* fall through with quote values */
                }
            }
            unitPrice = pricingQuote.unitPrice().setScale(4, RoundingMode.HALF_UP);
            priceSource = PriceSource.PRICING_SERVICE;
            description = pricingQuote.description() != null ? pricingQuote.description() : command.itemSku();
            breakdownJson = pricingQuote.breakdownJson();
        }

        // Advisory at line-add (an estimate may legitimately be built from out-of-stock items);
        // checkout re-evaluates and is what actually registers the demand.
        InventoryResult inventoryResult = inventoryPort.checkAvailability(
                command.itemSku(), BigDecimal.valueOf(command.quantity()), order.getLocationId());
        FulfillmentStatus fulfillmentStatus =
                inventoryResult.sufficient() ? FulfillmentStatus.AVAILABLE : FulfillmentStatus.BACKORDER;

        List<String> serialNumbers = normalizeSerials(command.serialNumbers(), command.quantity());

        SalesOrderLine line = SalesOrderLine.builder()
                .order(order)
                .itemSku(command.itemSku())
                .itemDescription(description)
                .quantity(command.quantity())
                .unitPrice(unitPrice)
                .priceSource(priceSource)
                .fulfillmentStatus(fulfillmentStatus)
                .reasonCode(command.reasonCode())
                .clientLineUuid(command.clientLineUuid())
                .customerNote(normalizeBlank(command.customerNote()))
                .internalNote(normalizeBlank(command.internalNote()))
                .pricingBreakdown(breakdownJson)
                .serialNumbers(serialNumbers)
                .build();

        SalesOrderLine saved = salesOrderLineRepository.save(line);
        order.getLines().add(saved);
        recomputeAfterMutation(order);
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
        recomputeAfterMutation(order);
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
        recomputeAfterMutation(order);
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
                        // Spec R7.1: approved source prices are contractual — never repriced.
                        .priceSource(PriceSource.SOURCE_DOCUMENT)
                        .fulfillmentStatus(FulfillmentStatus.AVAILABLE)
                        .sourceType(type)
                        .sourceId(sourceId)
                        .sourceLineId(sourceLine.sourceLineId())
                        .returnable(sourceLine.returnable())
                        .build();
                SalesOrderLine savedNewLine = salesOrderLineRepository.save(newLine);
                order.getLines().add(savedNewLine);
            }
        }

        recomputeAfterMutation(order);
        return toSummary(salesOrderRepository.save(order));
    }

    @Override
    @Transactional
    public SalesOrderSummary applyOrderDiscount(UUID orderId, OrderDiscountCommand command) {
        SalesOrder order =
                salesOrderRepository.findById(orderId).orElseThrow(() -> new SalesOrderNotFoundException(orderId));
        orderStateMachine.requireEditable(order);

        OrderDiscountType type;
        try {
            type = OrderDiscountType.valueOf(command.type());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Order discount type must be PERCENT or AMOUNT: " + command.type());
        }
        if (command.value().signum() <= 0
                || (type == OrderDiscountType.PERCENT && command.value().compareTo(BigDecimal.valueOf(100)) > 0)) {
            throw new IllegalStateException(
                    "Order discount value must be positive" + (type == OrderDiscountType.PERCENT ? " and <= 100" : ""));
        }

        order.setOrderDiscountType(type);
        order.setOrderDiscountValue(command.value().setScale(4, RoundingMode.HALF_UP));
        order.setOrderDiscountReasonCode(normalizeBlank(command.reasonCode()));
        recomputeAfterMutation(order);
        return toSummary(salesOrderRepository.save(order));
    }

    @Override
    @Transactional
    public SalesOrderSummary clearOrderDiscount(UUID orderId) {
        SalesOrder order =
                salesOrderRepository.findById(orderId).orElseThrow(() -> new SalesOrderNotFoundException(orderId));
        orderStateMachine.requireEditable(order);
        order.setOrderDiscountType(null);
        order.setOrderDiscountValue(null);
        order.setOrderDiscountReasonCode(null);
        recomputeAfterMutation(order);
        return toSummary(salesOrderRepository.save(order));
    }

    @Override
    @Transactional
    public SalesOrderSummary quote(UUID orderId) {
        SalesOrder order =
                salesOrderRepository.findById(orderId).orElseThrow(() -> new SalesOrderNotFoundException(orderId));
        requireNoWorkorderLink(order);
        if (order.getLines().isEmpty()) {
            throw new IllegalStateException("Cannot quote an empty cart");
        }

        repriceLines(order, true);
        orderTaxService.recomputeTax(order);
        orderStateMachine.transition(order, SalesOrderStatus.QUOTED, "counter quote");
        order.setQuoteExpiresAt(Instant.now(clock).plus(quoteValidity));
        order.setUpdatedBy(SecurityContextHelper.getCurrentUsernameOrDefault("system"));
        return toSummary(salesOrderRepository.save(order));
    }

    @Override
    @Transactional
    public SalesOrderSummary reopenQuote(UUID orderId) {
        SalesOrder order =
                salesOrderRepository.findById(orderId).orElseThrow(() -> new SalesOrderNotFoundException(orderId));
        orderStateMachine.transition(order, SalesOrderStatus.DRAFT, "quote reopened");
        order.setQuoteExpiresAt(null);
        // Best-effort reprice on reopen (spec R2.3): price movement between quote cycles must be
        // reflected; an unavailable pricing service keeps the old price and taxStale flags it.
        repriceLines(order, false);
        recomputeAfterMutation(order);
        return toSummary(salesOrderRepository.save(order));
    }

    @Override
    @Transactional
    public CheckoutResult checkout(UUID orderId, String idempotencyKey, String tenderType) {
        String key = normalizeBlank(idempotencyKey);
        if (key == null) {
            throw new IllegalArgumentException("Idempotency-Key is required for checkout");
        }
        String tender =
                normalizeBlank(tenderType) == null ? null : tenderType.trim().toUpperCase(java.util.Locale.ROOT);
        boolean onAccount = "ON_ACCOUNT".equals(tender);
        if (!onAccount && tender != null && !"DEFAULT".equals(tender)) {
            throw new IllegalArgumentException("Unsupported tenderType: " + tenderType);
        }
        SalesOrder order =
                salesOrderRepository.findById(orderId).orElseThrow(() -> new SalesOrderNotFoundException(orderId));

        if (key.equals(order.getCheckoutIdempotencyKey())) {
            return new CheckoutResult(toSummary(order), true);
        }
        Optional<SalesOrder> keyOwner = salesOrderRepository.findByCheckoutIdempotencyKey(key);
        if (keyOwner.isPresent() && !orderId.equals(keyOwner.get().getOrderId())) {
            throw new CartIdempotencyConflictException(
                    "Idempotency key was previously used to check out a different order");
        }

        if (order.getLines().stream().filter(Objects::nonNull).findAny().isEmpty()) {
            throw new IllegalStateException("Cannot check out an empty cart");
        }
        // Resolved Q8: PENDING customer validation hard-blocks financially consequential
        // transitions; the cart stays workable until CRM resolves.
        if (order.getCustomerValidationStatus() == CustomerValidationStatus.PENDING) {
            throw new InvalidCustomerException(
                    "Customer validation is pending; checkout is blocked until CRM confirms the customer");
        }
        if (onAccount) {
            requireOnAccountEligibility(order);
        }
        applyAvailabilityAndRegisterDemand(order);

        requireSerialsForTrackedProducts(order);

        // Final reprice (no stale prices, spec R2.1) + authoritative tax before the freeze.
        repriceLines(order, true);
        orderTaxService.recomputeTax(order);

        orderStateMachine.transition(order, SalesOrderStatus.PENDING_PAYMENT, "checkout");
        order.setCheckoutIdempotencyKey(key);
        order.setAmountPaid(BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP));
        order.setBalanceDue(order.getGrandTotal());
        order.setUpdatedBy(SecurityContextHelper.getCurrentUsernameOrDefault("system"));
        SalesOrder saved = salesOrderRepository.save(order);

        // Synchronous invoice handshake (stories C1/C2). A failure here throws and rolls the
        // whole checkout back — atomic from the client's view.
        InvoiceRef invoiceRef = invoicingPort.createInvoiceForOrder(toInvoiceRequest(saved));
        saved.setInvoiceId(invoiceRef.invoiceId());
        saved.setInvoiceNumber(invoiceRef.invoiceNumber());

        if (onAccount) {
            completeOnAccount(saved);
        }
        return new CheckoutResult(toSummary(salesOrderRepository.save(saved)), false);
    }

    /**
     * Re-labels each line against owned availability at the selling location and registers the
     * demand with pos-inventory (CAP #1315).
     *
     * <h2>Short lines are admitted, not rejected</h2>
     *
     * Checkout used to throw on the first short line. That was wrong for a counter sale: the
     * customer is standing there, the order is real, and refusing it loses the sale rather than
     * recording it. A short line is instead marked {@code BACKORDER} and the order proceeds — the
     * shortfall becomes a promise to fulfil, which is what pos-inventory's backorder machinery
     * exists to track. Availability is only re-evaluated here (not trusted from line-add time)
     * because stock moves between building a cart and paying for it.
     *
     * <h2>The command is the commitment, this labelling is not</h2>
     *
     * The replica answer decides what the customer is told. What is actually reserved is decided
     * by pos-inventory against its own ledger when it handles the command, which is why a line can
     * be labelled available here and still come back backordered — a race this design accepts,
     * since the outcome fact carries the truth and arrives within seconds.
     *
     * <p>A line whose SKU resolves to no replicated product cannot have a reservation requested
     * (the command is keyed by stock-item id, which only the product replica can supply). That
     * line is still sold and still labelled from availability; it simply carries no reservation,
     * the same position it was in before this gate existed.
     */
    private void applyAvailabilityAndRegisterDemand(SalesOrder order) {
        UUID locationId = order.getLocationId();
        InventoryCommandPublisher publisher = inventoryCommandPublisher.getIfAvailable();
        for (SalesOrderLine line : order.getLines()) {
            if (line == null) {
                continue;
            }
            InventoryResult availability = inventoryPort.checkAvailability(
                    line.getItemSku(), BigDecimal.valueOf(line.getQuantity()), locationId);
            line.setFulfillmentStatus(
                    availability.sufficient() ? FulfillmentStatus.AVAILABLE : FulfillmentStatus.BACKORDER);

            if (publisher == null || locationId == null) {
                continue;
            }
            UUID stockItemId = extProductRepository
                    .findFirstBySkuIgnoreCaseAndActiveTrue(line.getItemSku())
                    .map(ExtProduct::getProductId)
                    .orElse(null);
            if (stockItemId == null) {
                log.warn(
                        "No replicated product for SKU {} on order {}; line checked out without a reservation",
                        line.getItemSku(),
                        order.getOrderId());
                continue;
            }
            // Sales-order demand stays integral by decision (ADR-0055 leaves SalesOrderLine.quantity
            // an int); the command is decimal, so this widens rather than narrowing.
            publisher.requestReservation(
                    line.getOrderLineId(), stockItemId, BigDecimal.valueOf(line.getQuantity()), locationId);
        }
    }

    /**
     * Story C4 gate (spec R4.5, R11.2): ON_ACCOUNT needs a VALIDATED commercial customer whose
     * replicated billing rules carry payment terms and no credit hold, plus the explicit
     * charge-on-account permission.
     */
    private void requireOnAccountEligibility(SalesOrder order) {
        if (!SecurityContextHelper.hasAuthority(
                com.positivity.order.internal.security.OrderPermissions.ORDER_CHARGE_ON_ACCOUNT)) {
            throw new AccessDeniedException(
                    "Permission 'order:order:charge_on_account' is required for ON_ACCOUNT tender");
        }
        if (order.getCustomerId() == null
                || order.getCustomerValidationStatus() != CustomerValidationStatus.VALIDATED) {
            throw new InvalidCustomerException("ON_ACCOUNT tender requires a validated customer on the order");
        }
        ExtCustomer customer =
                extCustomerRepository.findById(order.getCustomerId()).orElse(null);
        if (customer == null || !"COMMERCIAL".equalsIgnoreCase(customer.getPartyType())) {
            throw new InvalidCustomerException("ON_ACCOUNT tender requires a commercial customer account");
        }
        if (!customer.isRequirementsMet()) {
            throw new InvalidCustomerException(
                    "Customer account does not currently meet requirements (inactive or credit hold)");
        }
        ExtBillingRules billingRules =
                extBillingRulesRepository.findById(order.getCustomerId()).orElse(null);
        if (billingRules == null || normalizeBlank(billingRules.getPaymentTerms()) == null) {
            throw new InvalidCustomerException("Customer has no billing terms configured for on-account charges");
        }
        if (Boolean.TRUE.equals(billingRules.getCreditHold())) {
            throw new InvalidCustomerException("Customer account is on credit hold");
        }
    }

    /**
     * Story C4 completion (spec R4.5): the accepted AR invoice IS the settlement — record an
     * ON_ACCOUNT ledger entry for the full total and complete the order synchronously. The AR
     * balance lives on the invoice (pos-invoice/pos-accounting), not on the order.
     */
    private void completeOnAccount(SalesOrder order) {
        paymentRecordRepository.save(OrderPaymentRecord.builder()
                .orderId(order.getOrderId())
                .recordType(OrderPaymentRecord.RecordType.SETTLED)
                .methodType("ON_ACCOUNT")
                .amount(order.getGrandTotal())
                .reference(order.getInvoiceNumber())
                .occurredAt(Instant.now(clock))
                .build());
        order.setAmountPaid(order.getGrandTotal());
        order.setBalanceDue(BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP));
        orderStateMachine.transition(order, SalesOrderStatus.COMPLETED, "on-account invoice accepted");
        domainEventPublisher.publishOrderCompleted(
                order,
                List.of(new com.positivity.domainevents.order.OrderCompletedV1.Tender(
                        "ON_ACCOUNT", order.getGrandTotal())));
    }

    private static OrderInvoiceCreationRequest toInvoiceRequest(SalesOrder order) {
        // Line amounts are net of ALL discounts (lineTotal − tax includes the pro-rata order
        // discount allocation) so the invoice's subtotal + tax = total exactly.
        List<OrderInvoiceLineItem> lines = order.getLines().stream()
                .filter(Objects::nonNull)
                .map(line -> OrderInvoiceLineItem.builder()
                        .orderLineId(line.getOrderLineId())
                        .description(line.getItemDescription())
                        .quantity(BigDecimal.valueOf(line.getQuantity()))
                        .unitPrice(line.getUnitPrice())
                        .amount(line.getLineTotal().subtract(line.getTaxAmount()))
                        .taxAmount(line.getTaxAmount())
                        .type("PART")
                        .build())
                .toList();
        OrderInvoiceCreationRequest.OrderInvoiceCreationRequestBuilder builder = OrderInvoiceCreationRequest.builder()
                .orderId(order.getOrderId())
                .workorderId(order.getWorkOrderId())
                .customerId(order.getCustomerId())
                .locationId(order.getLocationId())
                .subtotal(order.getGrandTotal().subtract(order.getTaxTotal()))
                .taxAmount(order.getTaxTotal())
                .totalAmount(order.getGrandTotal())
                .lines(lines);
        // Story E4: a deposit-take order registers a deposit credit against its source for the
        // order total — pos-invoice owns the credit artifact (gate V2).
        if (order.getDepositSourceType() != null && order.getDepositSourceId() != null) {
            builder.depositSourceType(order.getDepositSourceType())
                    .depositSourceId(order.getDepositSourceId())
                    .depositAmount(order.getGrandTotal());
        }
        return builder.build();
    }

    @Override
    @Transactional
    public SalesOrderSummary voidOrder(UUID orderId, String reason) {
        SalesOrder order =
                salesOrderRepository.findById(orderId).orElseThrow(() -> new SalesOrderNotFoundException(orderId));
        if (order.getStatus() == SalesOrderStatus.VOIDED) {
            return toSummary(order);
        }
        // Spec R2.4: settled money must go through the cancellation saga's reversal, never a void.
        boolean hasSettled = paymentRecordRepository.findByOrderId(orderId).stream()
                .anyMatch(record -> record.getRecordType() == OrderPaymentRecord.RecordType.SETTLED);
        if (hasSettled) {
            throw new OrderVoidBlockedException(orderId);
        }
        // PENDING_PAYMENT is the only voidable status; anything else (incl. DRAFT) is rejected
        // by the state machine with a 409.
        orderStateMachine.transition(order, SalesOrderStatus.VOIDED, normalizeBlank(reason));
        if (order.getInvoiceId() != null) {
            // Must fail loudly: a failed invoice cancel rolls the void back (atomic like checkout).
            invoicingPort.cancelInvoice(order.getInvoiceId());
        }
        order.setUpdatedBy(SecurityContextHelper.getCurrentUsernameOrDefault("system"));
        return toSummary(salesOrderRepository.save(order));
    }

    /**
     * Resolved Q3 (spec R2.3): QUOTED is counter-only — pos-workorder Estimates own repair
     * quoting. Reject when the order references a workorder directly or via imported lines.
     */
    private void requireNoWorkorderLink(SalesOrder order) {
        boolean workorderLinked = order.getWorkOrderId() != null
                || order.getLines().stream()
                        .filter(Objects::nonNull)
                        .anyMatch(l -> SourceType.WORKORDER.equals(l.getSourceType()));
        if (workorderLinked) {
            throw new IllegalStateException(
                    "QUOTE_NOT_ALLOWED_FOR_WORKORDER: workorder-linked orders are quoted via pos-workorder estimates");
        }
    }

    private void repriceLines(SalesOrder order, boolean failOnUnavailable) {
        for (SalesOrderLine line : order.getLines()) {
            if (line == null || line.getPriceSource() == PriceSource.MANUAL || line.getSourceType() != null) {
                continue; // manual prices are permissioned; source-doc prices are contractual
            }
            PricingQuote pricingQuote = pricingPort.quoteForSku(
                    line.getItemSku(), line.getQuantity(), order.getLocationId(), order.getCustomerId());
            if (pricingQuote.status() == PricingQuote.Status.PRICED) {
                line.setUnitPrice(pricingQuote.unitPrice().setScale(4, RoundingMode.HALF_UP));
                if (pricingQuote.description() != null) {
                    line.setItemDescription(pricingQuote.description());
                }
                line.setPricingBreakdown(pricingQuote.breakdownJson());
                salesOrderLineRepository.save(line);
            } else if (failOnUnavailable) {
                throw new IllegalStateException("Pricing is unavailable for SKU " + line.getItemSku()
                        + "; cannot finalize a quote on stale prices");
            }
        }
    }

    private void recomputeAfterMutation(SalesOrder order) {
        totalsCalculator.recalculate(order);
        order.setTaxStale(true);
        order.setUpdatedBy(SecurityContextHelper.getCurrentUsernameOrDefault("system"));
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
            recomputeAfterMutation(order);
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

    private static String normalizeBlank(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }

    /** Spec R10.2: serial capture is optional at add time but never exceeds the line quantity. */
    private static List<String> normalizeSerials(List<String> serialNumbers, int quantity) {
        if (serialNumbers == null) {
            return new java.util.ArrayList<>();
        }
        List<String> normalized = serialNumbers.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(java.util.ArrayList::new));
        if (normalized.size() > quantity) {
            throw new IllegalStateException(
                    "serialNumbers count (" + normalized.size() + ") exceeds line quantity (" + quantity + ")");
        }
        return normalized;
    }

    /**
     * Checkout serial enforcement (parity story H3, spec R8.4/R10.2): counter lines for tracked
     * products must carry serials — SERIAL tracking demands one per unit, LOT at least one lot
     * reference. Source-document lines are excluded (their stock story belongs to the source).
     */
    private void requireSerialsForTrackedProducts(SalesOrder order) {
        for (SalesOrderLine line : order.getLines()) {
            if (line == null || line.getSourceType() != null) {
                continue;
            }
            String trackingLevel = extProductRepository
                    .findFirstBySkuIgnoreCaseAndActiveTrue(line.getItemSku())
                    .map(ExtProduct::getTrackingLevel)
                    .orElse(null);
            int captured = line.getSerialNumbers() == null
                    ? 0
                    : line.getSerialNumbers().size();
            if ("SERIAL".equals(trackingLevel) && captured != line.getQuantity()) {
                throw new IllegalStateException("SKU " + line.getItemSku() + " is serial-tracked: " + line.getQuantity()
                        + " serial number(s) required, " + captured + " captured");
            }
            if ("LOT".equals(trackingLevel) && captured < 1) {
                throw new IllegalStateException(
                        "SKU " + line.getItemSku() + " is lot-tracked: at least one lot number is required");
            }
        }
    }

    private static String asString(UUID value) {
        return value == null ? null : value.toString();
    }

    private SalesOrderLineSummary toLineSummary(SalesOrderLine line) {
        return new SalesOrderLineSummary(
                line.getOrderLineId().toString(),
                line.getItemSku(),
                line.getItemDescription(),
                line.getQuantity(),
                line.getUnitPrice(),
                line.getDiscountPercent(),
                line.getDiscountAmount(),
                line.getLineSubtotal(),
                line.getTaxAmount(),
                line.getLineTotal(),
                line.getFulfillmentStatus().name(),
                line.getPriceSource().name(),
                line.getReasonCode(),
                line.getCustomerNote(),
                line.getInternalNote(),
                line.getSourceType() != null ? line.getSourceType().name() : null,
                line.getSourceId(),
                line.getSourceLineId(),
                line.getSerialNumbers() == null ? List.of() : List.copyOf(line.getSerialNumbers()),
                line.getReturnable());
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
                order.getDiscountTotal(),
                order.getTaxTotal(),
                order.getGrandTotal(),
                order.isTaxStale(),
                order.getOrderDiscountType() != null
                        ? order.getOrderDiscountType().name()
                        : null,
                order.getOrderDiscountValue(),
                order.getOrderDiscountReasonCode(),
                order.getGeneralNote(),
                order.getQuoteExpiresAt(),
                asString(order.getInvoiceId()),
                order.getInvoiceNumber(),
                order.getAmountPaid(),
                order.getBalanceDue(),
                order.getCreatedAt(),
                order.getUpdatedAt(),
                order.getCreatedBy(),
                order.getUpdatedBy(),
                lines);
    }
}
