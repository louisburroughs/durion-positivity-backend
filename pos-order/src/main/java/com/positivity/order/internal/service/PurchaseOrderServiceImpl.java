package com.positivity.order.internal.service;

import com.positivity.order.internal.dto.purchaseorder.ApprovePurchaseOrderRequest;
import com.positivity.order.internal.dto.purchaseorder.CancelPurchaseOrderLineRequest;
import com.positivity.order.internal.dto.purchaseorder.ClosePurchaseOrderRequest;
import com.positivity.order.internal.dto.purchaseorder.CreatePurchaseOrderRequest;
import com.positivity.order.internal.dto.purchaseorder.ListPurchaseOrdersRequest;
import com.positivity.order.internal.dto.purchaseorder.PurchaseOrderLineRequest;
import com.positivity.order.internal.dto.purchaseorder.PurchaseOrderLineResponse;
import com.positivity.order.internal.dto.purchaseorder.PurchaseOrderLineRollup;
import com.positivity.order.internal.dto.purchaseorder.PurchaseOrderResponse;
import com.positivity.order.internal.dto.purchaseorder.PurchaseOrderStatusRollup;
import com.positivity.order.internal.dto.purchaseorder.PurchaseOrderStatusSummary;
import com.positivity.order.internal.dto.purchaseorder.PurchaseOrderSummaryResponse;
import com.positivity.order.internal.dto.purchaseorder.PurchaseOrderTransmissionEventResponse;
import com.positivity.order.internal.dto.purchaseorder.ReopenPurchaseOrderLineRequest;
import com.positivity.order.internal.dto.purchaseorder.RevisePurchaseOrderRequest;
import com.positivity.order.internal.entity.ExtSupplierVendor;
import com.positivity.order.internal.entity.PurchaseOrderEntity;
import com.positivity.order.internal.entity.PurchaseOrderLineEntity;
import com.positivity.order.internal.entity.PurchaseOrderTransmissionEvent;
import com.positivity.order.internal.enums.PurchaseOrderStatus;
import com.positivity.order.internal.enums.TransmissionState;
import com.positivity.order.internal.exception.PurchaseOrderNotFoundException;
import com.positivity.order.internal.exception.PurchaseOrderRequestValidationException;
import com.positivity.order.internal.exception.PurchaseOrderStateConflictException;
import com.positivity.order.internal.exception.PurchaseOrderVersionConflictException;
import com.positivity.order.internal.repository.PurchaseOrderLineRepository;
import com.positivity.order.internal.repository.PurchaseOrderRepository;
import com.positivity.order.internal.repository.PurchaseOrderTransmissionEventRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
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
@Slf4j
@Service
@RequiredArgsConstructor
public class PurchaseOrderServiceImpl implements PurchaseOrderService {

    /** Highest sequence value that still renders as 8 base-36 characters. */
    private static final long MAX_PO_NUMBER_SEQUENCE = 2_821_109_907_455L;

    /**
     * The default population of {@link #summarizePurchaseOrders}: what counts as incoming supply per
     * the {@link PurchaseOrderStatus} contract. Cancellation does not zero a line's open quantity, so a
     * status-blind sum would report cancelled units as "on order" (#1798 review).
     */
    static final List<PurchaseOrderStatus> OPEN_SUPPLY_STATUSES =
            List.of(PurchaseOrderStatus.APPROVED, PurchaseOrderStatus.PARTIALLY_RECEIVED);

    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseOrderLineRepository purchaseOrderLineRepository;
    private final PurchaseOrderTransmissionEventRepository transmissionEventRepository;

    /**
     * Used only by {@link #createRequested}, which needs an insert that fails on a duplicate id
     * rather than a save that quietly merges into one.
     */
    private final jakarta.persistence.EntityManager entityManager;

    private final PurchaseOrderFactPublisher purchaseOrderFactPublisher;
    private final DocumentQuantityConverter documentQuantityConverter;
    private final Clock clock;

    /** The vendor guard of create, approve and a vendor-changing revision (CAP:550 S24, #2517). */
    private final SupplierVendorGuard vendorGuard;

    @Value("${pos.order.default-tax-rate:0.10}")
    private double defaultTaxRate = 0.10d;

    /**
     * The order's currency in its canonical ISO 4217 form, as {@code CreatePurchaseOrderRequest}
     * documents it (ADR-0067 DF-6, #2314). Anything that is not an ISO 4217 code — a typo, a
     * free-text name — is refused rather than stored, for hand-keyed orders and those requested by
     * pos-inventory alike, since downstream receiving reads the minor-unit digits from it.
     */
    static @NonNull String canonicalCurrency(@Nullable String currency) {
        if (currency == null || currency.isBlank()) {
            throw new PurchaseOrderRequestValidationException("currency is required and must be an ISO 4217 code");
        }
        try {
            return java.util.Currency.getInstance(currency.trim().toUpperCase(java.util.Locale.ROOT))
                    .getCurrencyCode();
        } catch (IllegalArgumentException notIso) {
            throw new PurchaseOrderRequestValidationException(
                    "currency must be an ISO 4217 code, was [" + currency + "]");
        }
    }

    @Override
    @Transactional
    public @NonNull PurchaseOrderResponse createPurchaseOrder(
            @NonNull CreatePurchaseOrderRequest request, @NonNull String actorId) {
        // Only an active vendor of the copy takes a new order (S24, #2517). A requested order (createRequested) is
        // not checked here: it lands in DRAFT and approval stops it.
        ExtSupplierVendor vendor = vendorGuard.requireActive(request.getVendorId());
        TotalsAndLines totalsAndLines = buildLineEntities(request.getLines());
        long subtotalMinor = totalsAndLines.subtotalMinor();
        long taxMinor = totalsAndLines.taxMinor();
        long grandTotalMinor = subtotalMinor + taxMinor;

        PurchaseOrderEntity purchaseOrder = PurchaseOrderEntity.builder()
                .vendorId(request.getVendorId())
                // The vendor's purchasing channel as the copy holds it now (#2713); re-stamped at approve and transmit.
                .supplierRef(vendor.getPurchaseOrderSupplierRef())
                .poNumber(generatePoNumber())
                .status(PurchaseOrderStatus.DRAFT)
                .versionNumber(1)
                .currency(canonicalCurrency(request.getCurrency()))
                .subtotalMinor(subtotalMinor)
                .taxMinor(taxMinor)
                .grandTotalMinor(grandTotalMinor)
                // Ex-tax (#2633): the balance is what is owed for the goods, the basis every receipt's
                // accrual comes off. Tax on purchases is a bill matter, not a receiving one.
                .openBalanceMinor(subtotalMinor)
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
                .currency(canonicalCurrency(request.getCurrency()))
                .subtotalMinor(totalsAndLines.subtotalMinor())
                .taxMinor(totalsAndLines.taxMinor())
                .grandTotalMinor(grandTotalMinor)
                .openBalanceMinor(totalsAndLines.subtotalMinor())
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
        return purchaseOrderRepository
                .findAll(listSpecification(filter), pageable)
                .map(this::toResponse);
    }

    /**
     * Every supplied filter becomes a predicate on the query, so the page holds the first
     * {@code size} matching rows and {@code totalElements} counts only matching rows (#1804).
     * Filtering a fetched page in memory did neither: rows matching {@code currency} or
     * {@code locationId} beyond the first unfiltered page were unreachable, and the total still
     * counted them.
     *
     * <p>Currency matches case-insensitively, as the in-memory filter it replaces did.
     */
    private static @NonNull Specification<PurchaseOrderEntity> listSpecification(
            @NonNull ListPurchaseOrdersRequest filter) {
        Specification<PurchaseOrderEntity> spec = (root, query, cb) -> cb.conjunction();
        if (filter.getVendorId() != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("vendorId"), filter.getVendorId()));
        }
        if (filter.getStatus() != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), filter.getStatus()));
        }
        if (filter.getCurrency() != null) {
            String currency = filter.getCurrency().toUpperCase(Locale.ROOT);
            spec = spec.and((root, query, cb) -> cb.equal(cb.upper(root.get("currency")), currency));
        }
        if (filter.getLocationId() != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("shipToLocationId"), filter.getLocationId()));
        }
        return spec;
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull PurchaseOrderSummaryResponse summarizePurchaseOrders(
            @Nullable UUID vendorId, @Nullable List<PurchaseOrderStatus> statuses) {
        List<PurchaseOrderStatus> applied =
                statuses == null || statuses.isEmpty() ? OPEN_SUPPLY_STATUSES : List.copyOf(statuses);
        Set<PurchaseOrderStatus> wanted = EnumSet.copyOf(applied);
        List<PurchaseOrderStatusRollup> headers = vendorId == null
                ? purchaseOrderRepository.rollupByStatus()
                : purchaseOrderRepository.rollupByStatusForVendor(vendorId);
        List<PurchaseOrderLineRollup> lines = vendorId == null
                ? purchaseOrderLineRepository.rollupByStatus()
                : purchaseOrderLineRepository.rollupByStatusForVendor(vendorId);
        Map<PurchaseOrderStatus, PurchaseOrderLineRollup> linesByStatus =
                lines.stream().collect(Collectors.toMap(PurchaseOrderLineRollup::status, Function.identity()));

        List<PurchaseOrderStatusSummary> byStatus = headers.stream()
                .filter(header -> wanted.contains(header.status()))
                .sorted(Comparator.comparing(PurchaseOrderStatusRollup::status))
                .map(header -> {
                    PurchaseOrderLineRollup line = linesByStatus.get(header.status());
                    BigDecimal ordered = line == null ? BigDecimal.ZERO : zeroIfNull(line.unitsOrdered());
                    BigDecimal open = line == null ? BigDecimal.ZERO : zeroIfNull(line.unitsOpen());
                    return new PurchaseOrderStatusSummary(
                            header.status(),
                            zeroIfNull(header.orderCount()),
                            line == null ? 0L : zeroIfNull(line.lineCount()),
                            ordered,
                            open,
                            ordered.subtract(open),
                            zeroIfNull(header.grandTotalMinor()),
                            zeroIfNull(header.openBalanceMinor()));
                })
                .toList();

        BigDecimal unitsOrdered = byStatus.stream()
                .map(PurchaseOrderStatusSummary::unitsOrdered)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal unitsOpen =
                byStatus.stream().map(PurchaseOrderStatusSummary::unitsOpen).reduce(BigDecimal.ZERO, BigDecimal::add);
        return PurchaseOrderSummaryResponse.builder()
                .vendorId(vendorId)
                .statuses(applied)
                .orderCount(byStatus.stream()
                        .mapToLong(PurchaseOrderStatusSummary::orderCount)
                        .sum())
                .lineCount(byStatus.stream()
                        .mapToLong(PurchaseOrderStatusSummary::lineCount)
                        .sum())
                .unitsOrdered(unitsOrdered)
                .unitsOpen(unitsOpen)
                .unitsReceived(unitsOrdered.subtract(unitsOpen))
                .grandTotalMinor(byStatus.stream()
                        .mapToLong(PurchaseOrderStatusSummary::grandTotalMinor)
                        .sum())
                .openBalanceMinor(byStatus.stream()
                        .mapToLong(PurchaseOrderStatusSummary::openBalanceMinor)
                        .sum())
                .byStatus(byStatus)
                .build();
    }

    private static long zeroIfNull(@Nullable Long value) {
        return value == null ? 0L : value;
    }

    private static @NonNull BigDecimal zeroIfNull(@Nullable BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
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
        // Under the row lock, like revise, so an approval and a revision take turns and the version an approver
        // read is the version that is approved (#2681).
        PurchaseOrderEntity po = purchaseOrderRepository
                .findByIdForUpdate(poId)
                .orElseThrow(() -> new PurchaseOrderNotFoundException(poId));
        if (request.getExpectedVersionNumber() != null) {
            requireVersion(po, request.getExpectedVersionNumber());
        }
        if (po.getStatus() != PurchaseOrderStatus.DRAFT) {
            throw new PurchaseOrderStateConflictException("Only DRAFT purchase orders can be approved");
        }
        // The guard that stops a requested order naming a vendor outside the copy, or an inactive one (S24, #2517).
        ExtSupplierVendor vendor = vendorGuard.requireActive(po.getVendorId());
        stampChannel(po, vendor);

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
        if (request.getExpectedVersionNumber() == null || request.getExpectedVersionNumber() < 1) {
            // Bean validation answers HTTP callers first; this keeps an in-process caller to the same contract.
            throw new PurchaseOrderRequestValidationException(
                    "expectedVersionNumber is required and at least 1: the versionNumber the revision was built on");
        }
        PurchaseOrderEntity po = purchaseOrderRepository
                .findByIdForUpdate(poId)
                .orElseThrow(() -> new PurchaseOrderNotFoundException(poId));
        // Checked under the row lock and incremented in the same transaction, so two revisions built on the same
        // version cannot both commit: the second waits for the lock, reads the new version and is refused (#2681).
        int priorVersion = requireVersion(po, request.getExpectedVersionNumber());
        requireRevisable(po);
        reviseVendor(po, request.getVendorId());

        po.setVersionNumber(priorVersion + 1);
        po.setPoDate(request.getPoDate());
        po.setPaymentTermsId(request.getPaymentTermsId());
        po.setExpectedDeliveryDate(request.getExpectedDeliveryDate());
        po.setShipToLocationId(request.getShipToLocationId());
        // The order's requester, not the reviser (who is the actor): absent keeps it, blank clears it, a value
        // corrects it (#2681).
        if (request.getRequestedBy() != null) {
            String requester = request.getRequestedBy().trim();
            po.setRequestedBy(requester.isEmpty() ? null : requester);
        }
        po.setComment(request.getComment());

        TotalsAndLines totalsAndLines = buildLineEntities(request.getLines());
        po.setSubtotalMinor(totalsAndLines.subtotalMinor());
        po.setTaxMinor(totalsAndLines.taxMinor());
        po.setGrandTotalMinor(totalsAndLines.subtotalMinor() + totalsAndLines.taxMinor());
        po.setOpenBalanceMinor(totalsAndLines.subtotalMinor());

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
        // Every writer of the header takes the row lock (#2681), so none writes a stale row back over a revision.
        PurchaseOrderEntity po = purchaseOrderRepository
                .findByIdForUpdate(poId)
                .orElseThrow(() -> new PurchaseOrderNotFoundException(poId));
        if (po.getStatus() == PurchaseOrderStatus.FULLY_RECEIVED || po.getStatus() == PurchaseOrderStatus.CLOSED) {
            throw new PurchaseOrderStateConflictException("Cannot cancel a fully received or closed purchase order");
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

    @Override
    @Transactional
    public @NonNull PurchaseOrderResponse reopenPurchaseOrderLine(
            @NonNull UUID poId,
            @NonNull UUID lineId,
            @NonNull ReopenPurchaseOrderLineRequest request,
            @NonNull String actorId) {
        PurchaseOrderEntity po = lockedForLineAction(poId);
        PurchaseOrderLineEntity line = lineOf(po, lineId);
        BigDecimal quantity = request.getQuantity();
        BigDecimal returned = PurchaseOrderLineFigures.zeroIfNull(line.getReturnedQuantity());
        if (quantity.compareTo(returned) > 0) {
            throw new PurchaseOrderStateConflictException("Line " + line.getLineNumber() + " of purchase order "
                    + po.getPoNumber() + " has " + returned.stripTrailingZeros().toPlainString()
                    + " returned; " + quantity.stripTrailingZeros().toPlainString() + " cannot be reopened");
        }
        long value = PurchaseOrderLineFigures.valueOf(line, quantity);
        line.setReturnedQuantity(returned.subtract(quantity));
        line.setReturnedValueMinor(
                Math.max(0L, PurchaseOrderLineFigures.zeroIfNull(line.getReturnedValueMinor()) - value));
        PurchaseOrderLineFigures.moveBalance(po, value);
        log.info(
                "Purchase order {} line {} reopened for replacement | quantity={} | vendorReturnId={} | by={}",
                po.getPoNumber(),
                line.getLineNumber(),
                quantity,
                request.getVendorReturnId(),
                actorId);
        return finishLineAction(po, actorId, null);
    }

    @Override
    @Transactional
    public @NonNull PurchaseOrderResponse cancelPurchaseOrderLine(
            @NonNull UUID poId,
            @NonNull UUID lineId,
            @NonNull CancelPurchaseOrderLineRequest request,
            @NonNull String actorId) {
        PurchaseOrderEntity po = lockedForLineAction(poId);
        if (po.getStatus() == PurchaseOrderStatus.DRAFT) {
            throw new PurchaseOrderStateConflictException(
                    "A DRAFT purchase order has no remainder to cancel; revise its lines instead",
                    "Revise the purchase order (revisePurchaseOrder) to change its quantities");
        }
        PurchaseOrderLineEntity line = lineOf(po, lineId);
        cancelRemainder(po, line, request.getQuantity());
        if (!PurchaseOrderLineFigures.anythingReceived(po) && PurchaseOrderLineFigures.allSettled(po)) {
            // Nothing ever arrived and this cancel would settle every line: that is a cancellation of the order, not a
            // short-close, and CLOSED must keep meaning "something was delivered" (the transaction rolls back).
            throw new PurchaseOrderStateConflictException(
                    "Cancelling this remainder would leave purchase order " + po.getPoNumber()
                            + " with nothing received and nothing open; cancel the order instead",
                    "Cancel the purchase order (cancelPurchaseOrder)");
        }
        log.info(
                "Purchase order {} line {} remainder cancelled | quantity={} | reason={} | by={}",
                po.getPoNumber(),
                line.getLineNumber(),
                request.getQuantity(),
                request.getReason(),
                actorId);
        return finishLineAction(po, actorId, null);
    }

    @Override
    @Transactional
    public @NonNull PurchaseOrderResponse closePurchaseOrder(
            @NonNull UUID poId, @NonNull ClosePurchaseOrderRequest request, @NonNull String actorId) {
        PurchaseOrderEntity po = lockedForLineAction(poId);
        if (po.getStatus() == PurchaseOrderStatus.DRAFT) {
            throw new PurchaseOrderStateConflictException(
                    "A DRAFT purchase order cannot be closed; cancel it or revise it",
                    "Cancel the purchase order (cancelPurchaseOrder) or revise it (revisePurchaseOrder)");
        }
        if (!PurchaseOrderLineFigures.anythingReceived(po)) {
            // Nothing arrived: this is a cancellation, and the order should say so rather than read as CLOSED.
            throw new PurchaseOrderStateConflictException(
                    "Nothing has been received on purchase order " + po.getPoNumber()
                            + "; cancel it rather than close it",
                    "Cancel the purchase order (cancelPurchaseOrder)");
        }
        for (PurchaseOrderLineEntity line : po.getLines()) {
            BigDecimal open = PurchaseOrderLineFigures.open(line);
            if (open.signum() > 0) {
                cancelRemainder(po, line, open);
            }
        }
        log.info("Purchase order {} closed | reason={} | by={}", po.getPoNumber(), request.getReason(), actorId);
        return finishLineAction(po, actorId, PurchaseOrderStatus.CLOSED);
    }

    /** The row-locked order (#2681) in a status the per-line actions may change: not CANCELLED or CLOSED. */
    private PurchaseOrderEntity lockedForLineAction(UUID poId) {
        PurchaseOrderEntity po = purchaseOrderRepository
                .findByIdForUpdate(poId)
                .orElseThrow(() -> new PurchaseOrderNotFoundException(poId));
        if (po.getStatus() == PurchaseOrderStatus.CANCELLED || po.getStatus() == PurchaseOrderStatus.CLOSED) {
            throw new PurchaseOrderStateConflictException(
                    "Purchase order " + po.getPoNumber() + " is " + po.getStatus() + " and its lines are final");
        }
        return po;
    }

    private static PurchaseOrderLineEntity lineOf(PurchaseOrderEntity po, UUID lineId) {
        return po.getLines().stream()
                .filter(line -> lineId.equals(line.getLineId()))
                .findFirst()
                .orElseThrow(() -> new PurchaseOrderNotFoundException(po.getPurchaseOrderId(), lineId));
    }

    /** Short-closes {@code quantity} of the line: at most its open quantity. */
    private static void cancelRemainder(PurchaseOrderEntity po, PurchaseOrderLineEntity line, BigDecimal quantity) {
        BigDecimal open = PurchaseOrderLineFigures.open(line);
        if (quantity.compareTo(open) > 0) {
            throw new PurchaseOrderStateConflictException("Line " + line.getLineNumber() + " of purchase order "
                    + po.getPoNumber() + " has " + open.stripTrailingZeros().toPlainString() + " open; "
                    + quantity.stripTrailingZeros().toPlainString() + " cannot be cancelled");
        }
        long value = PurchaseOrderLineFigures.valueOf(line, quantity);
        line.setCancelledQuantity(
                PurchaseOrderLineFigures.zeroIfNull(line.getCancelledQuantity()).add(quantity));
        line.setCancelledValueMinor(PurchaseOrderLineFigures.zeroIfNull(line.getCancelledValueMinor()) + value);
        PurchaseOrderLineFigures.moveBalance(po, -value);
    }

    /**
     * Re-derives the figures (and the status, unless {@code forcedStatus} names the terminal one the action sets),
     * bumps the version, saves and publishes: every per-line action ends here.
     */
    private PurchaseOrderResponse finishLineAction(
            PurchaseOrderEntity po, String actorId, @Nullable PurchaseOrderStatus forcedStatus) {
        PurchaseOrderLineFigures.refresh(po);
        if (forcedStatus != null) {
            po.setStatus(forcedStatus);
        }
        po.setVersionNumber(po.getVersionNumber() == null ? 1 : po.getVersionNumber() + 1);
        po.setUpdatedBy(actorId);
        PurchaseOrderEntity saved = purchaseOrderRepository.save(po);
        purchaseOrderFactPublisher.publish(saved, saved.getLines());
        return toResponse(saved);
    }

    /**
     * The revise gate (#2632). A revision replaces every line with new line ids and resets the open quantities and
     * the balance, so it is allowed only before anything was received: a {@code DRAFT} order, or an {@code APPROVED}
     * one on which no line has an open quantity below its ordered quantity. Past that, a receipt or vendor return
     * naming a replaced line would be skipped, and the units already received would be expected again. Judged on
     * the lines read under the order's row lock ({@link PurchaseOrderRepository#findByIdForUpdate}), not on an
     * earlier read. From {@code PARTIALLY_RECEIVED} on, the order's quantities are left to the per-line actions of
     * #2635; until those exist, they cannot be changed.
     */
    private static void requireRevisable(@NonNull PurchaseOrderEntity po) {
        PurchaseOrderStatus status = po.getStatus();
        if (status != PurchaseOrderStatus.DRAFT && status != PurchaseOrderStatus.APPROVED) {
            throw new PurchaseOrderStateConflictException(
                    "Only a DRAFT or APPROVED purchase order with nothing received can be revised; this order is "
                            + status);
        }
        for (PurchaseOrderLineEntity line : po.getLines()) {
            if (hasReceived(line)) {
                throw new PurchaseOrderStateConflictException("Purchase order " + po.getPoNumber()
                        + " has received goods on line " + line.getLineNumber() + " and can no longer be revised");
            }
        }
    }

    /** Something arrived against the line: its open quantity is below what was ordered. */
    private static boolean hasReceived(@NonNull PurchaseOrderLineEntity line) {
        BigDecimal open = line.getOpenQuantityDecimal();
        BigDecimal ordered = line.getQuantityDecimal();
        return open != null && ordered != null && open.compareTo(ordered) < 0;
    }

    /**
     * A revision may name another vendor while the order is a {@code DRAFT} (S24, #2517): how a buyer fixes a
     * requested order that named a feed id or an inactive vendor. The new vendor passes the same guard as a create.
     * An absent {@code vendorId}, or the order's own, keeps the vendor; another vendor on an order past {@code DRAFT}
     * is refused, because an approved order is a commitment to the vendor it names.
     */
    private void reviseVendor(@NonNull PurchaseOrderEntity po, @Nullable UUID vendorId) {
        if (vendorId == null || vendorId.equals(po.getVendorId())) {
            return;
        }
        if (po.getStatus() != PurchaseOrderStatus.DRAFT) {
            throw new PurchaseOrderStateConflictException(
                    "Only a DRAFT purchase order can change its vendor; this order is " + po.getStatus());
        }
        ExtSupplierVendor vendor = vendorGuard.requireActive(vendorId);
        po.setVendorId(vendorId);
        po.setSupplierRef(vendor.getPurchaseOrderSupplierRef());
    }

    /**
     * The stored version, once it is the one the caller built on (#2681). The caller holds the order's row lock, so
     * the comparison and the write that follows are one atomic step against any other revise or approve.
     */
    private static int requireVersion(@NonNull PurchaseOrderEntity po, int expectedVersionNumber) {
        int current = po.getVersionNumber() == null ? 1 : po.getVersionNumber();
        if (current != expectedVersionNumber) {
            throw new PurchaseOrderVersionConflictException(
                    po.getPurchaseOrderId(), po.getPoNumber(), expectedVersionNumber, current);
        }
        return current;
    }

    /**
     * Stamps the vendor's current purchasing channel on the order at approve (#2713) while nothing has been sent:
     * once transmitted, the stamp is frozen so a revision reaches the channel the original did. A vendor that gained
     * its channel after approval thereby becomes sendable without re-approval; at approve, a vendor without one keeps
     * the stamp the order had. (Create takes the vendor's channel or none; a vendor-changing revise takes the new
     * vendor's channel or none, since the old alias belonged to the old vendor.)
     */
    static void stampChannel(@NonNull PurchaseOrderEntity po, @NonNull ExtSupplierVendor vendor) {
        if (po.getTransmissionState() != TransmissionState.NOT_TRANSMITTED) {
            return;
        }
        if (vendor.hasPurchasingChannel()) {
            po.setSupplierRef(vendor.getPurchaseOrderSupplierRef());
        }
    }

    /**
     * What a transmit would find (#2713): the stamp, or, while nothing was sent (and after a vendor that was never
     * set up refused the first send), the channel the vendor copy holds now, since transmit stamps it then.
     */
    private boolean transmittable(PurchaseOrderEntity entity) {
        if (entity.getSupplierRef() != null && !entity.getSupplierRef().isBlank()) {
            return true;
        }
        return PurchaseOrderTransmissionService.stampsAtTransmit(entity.getTransmissionState())
                && vendorGuard
                        .copyOf(entity.getVendorId())
                        .filter(ExtSupplierVendor::hasPurchasingChannel)
                        .isPresent();
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
                .transmittable(transmittable(entity))
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
                .receivedQuantityDecimal(PurchaseOrderLineFigures.zeroIfNull(line.getReceivedQuantity()))
                .returnedQuantityDecimal(PurchaseOrderLineFigures.zeroIfNull(line.getReturnedQuantity()))
                .cancelledQuantityDecimal(PurchaseOrderLineFigures.zeroIfNull(line.getCancelledQuantity()))
                .taxCodeId(line.getTaxCodeId())
                .glAccountId(line.getGlAccountId())
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
