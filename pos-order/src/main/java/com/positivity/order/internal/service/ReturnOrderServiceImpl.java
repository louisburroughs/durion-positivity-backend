package com.positivity.order.internal.service;

import com.positivity.order.internal.client.InvoicingPort;
import com.positivity.order.internal.client.PaymentReversalResult;
import com.positivity.order.internal.client.ReversePaymentCommand;
import com.positivity.order.internal.config.OrderDomainEventPublisher;
import com.positivity.order.internal.dto.ReturnOrderSummary;
import com.positivity.order.internal.dto.ReturnableLineView;
import com.positivity.order.internal.entity.OrderPaymentRecord;
import com.positivity.order.internal.entity.RefundMethod;
import com.positivity.order.internal.entity.ReturnCondition;
import com.positivity.order.internal.entity.ReturnOrder;
import com.positivity.order.internal.entity.ReturnOrderLine;
import com.positivity.order.internal.entity.ReturnOrderStatus;
import com.positivity.order.internal.entity.SalesOrder;
import com.positivity.order.internal.entity.SalesOrderLine;
import com.positivity.order.internal.entity.SalesOrderStatus;
import com.positivity.order.internal.entity.SourceType;
import com.positivity.order.internal.exception.OverCapReturnException;
import com.positivity.order.internal.exception.ReturnLineNotReturnableException;
import com.positivity.order.internal.exception.ReturnOrderNotFoundException;
import com.positivity.order.internal.exception.ReturnRequestValidationException;
import com.positivity.order.internal.exception.SalesOrderNotFoundException;
import com.positivity.order.internal.exception.WarrantyReturnRoutingException;
import com.positivity.order.internal.repository.OrderPaymentRecordRepository;
import com.positivity.order.internal.repository.ReturnOrderLineRepository;
import com.positivity.order.internal.repository.ReturnOrderRepository;
import com.positivity.order.internal.repository.SalesOrderLineRepository;
import com.positivity.order.internal.repository.SalesOrderRepository;
import com.positivity.order.internal.service.model.CreateReturnCommand;
import com.positivity.order.internal.service.model.ReturnLineCommand;
import com.positivity.security.common.SecurityContextHelper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Returns & refunds (parity story F1, issue #1086, spec R5.1–R5.5). Enforces the Odoo refund
 * invariants: line-by-line link to one COMPLETED source order, per-line cap at the un-refunded
 * remainder (row-locked at creation), and one source order per return. Refund exceeding the
 * approval threshold parks the return in PENDING_APPROVAL. The F2 saga (story #1087) advances the
 * status further.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReturnOrderServiceImpl implements ReturnOrderService {
    private static final String SYSTEM_ACTOR = "system";

    /** Returns in these states do not reserve quantity against the cap. */
    static final List<ReturnOrderStatus> CAP_EXCLUDED_STATUSES =
            List.of(ReturnOrderStatus.CANCELLED, ReturnOrderStatus.REJECTED);

    private static final String CURRENCY = "USD";

    private final SalesOrderRepository salesOrderRepository;
    private final SalesOrderLineRepository salesOrderLineRepository;
    private final ReturnOrderRepository returnOrderRepository;
    private final ReturnOrderLineRepository returnOrderLineRepository;
    private final OrderPaymentRecordRepository paymentRecordRepository;
    private final InvoicingPort invoicingPort;
    private final OrderDomainEventPublisher domainEventPublisher;
    private final Clock clock;

    /** Refund total above this amount requires order:return:approve before the saga runs. */
    @Value("${pos.order.return.approval-threshold:250.00}")
    private BigDecimal approvalThreshold;

    @Override
    @Transactional
    public @NonNull ReturnOrderSummary createReturn(@NonNull CreateReturnCommand command) {
        String idempotencyKey = normalizeBlank(command.idempotencyKey());
        if (idempotencyKey != null) {
            Optional<ReturnOrder> replayed = returnOrderRepository.findByCreationIdempotencyKey(idempotencyKey);
            if (replayed.isPresent()) {
                return toSummary(replayed.get());
            }
        }
        if (command.lines().isEmpty()) {
            throw new ReturnRequestValidationException("A return must have at least one line");
        }

        SalesOrder original = salesOrderRepository
                .findById(command.originalOrderId())
                .orElseThrow(() -> new SalesOrderNotFoundException(command.originalOrderId()));
        if (original.getStatus() != SalesOrderStatus.COMPLETED) {
            throw new IllegalStateException("Returns are only allowed against COMPLETED orders; order "
                    + original.getOrderId() + " is " + original.getStatus());
        }
        RefundMethod refundMethod = parseRefundMethod(command.refundMethod());

        Map<UUID, SalesOrderLine> soldLines = new HashMap<>();
        for (SalesOrderLine line : salesOrderLineRepository.findByOrder_OrderId(original.getOrderId())) {
            soldLines.put(line.getOrderLineId(), line);
        }

        String actor = SecurityContextHelper.getCurrentUsernameOrDefault(SYSTEM_ACTOR);
        ReturnOrder returnOrder = ReturnOrder.builder()
                .originalOrderId(original.getOrderId())
                .originalOrderNumber(original.getOrderNumber())
                .locationId(original.getLocationId())
                .customerId(original.getCustomerId())
                .originalInvoiceId(original.getInvoiceId())
                .refundMethod(refundMethod)
                .reasonCode(normalizeBlank(command.reasonCode()))
                .creationIdempotencyKey(idempotencyKey)
                .createdBy(actor)
                .updatedBy(actor)
                .build();

        BigDecimal totalRefund = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        List<OverCapReturnException.LineCap> overCap = new ArrayList<>();
        Set<UUID> seenLineIds = new HashSet<>();

        for (ReturnLineCommand lineCommand : command.lines()) {
            BigDecimal lineRefund =
                    appendReturnLine(returnOrder, lineCommand, original, soldLines, seenLineIds, overCap);
            if (lineRefund != null) {
                totalRefund = totalRefund.add(lineRefund);
            }
        }

        if (!overCap.isEmpty()) {
            throw new OverCapReturnException(overCap);
        }

        returnOrder.setTotalRefund(totalRefund);
        returnOrder.setStatus(
                totalRefund.compareTo(approvalThreshold) > 0
                        ? ReturnOrderStatus.PENDING_APPROVAL
                        : ReturnOrderStatus.RETURN_REQUESTED);
        ReturnOrder saved = returnOrderRepository.save(returnOrder);
        log.info(
                "Created return {} against order {} ({} lines, refund {}, status {})",
                saved.getReturnOrderId(),
                original.getOrderId(),
                saved.getLines().size(),
                totalRefund,
                saved.getStatus());
        return toSummary(saved);
    }

    /**
     * Cap-checks one requested line and, when it fits, adds it to the return.
     *
     * <p>Over-cap lines are collected rather than thrown on immediately, so one request reports
     * every line that exceeds its remainder instead of only the first.
     *
     * @return the line's refund, or {@code null} when it exceeded the cap and was recorded in
     *     {@code overCap} instead
     */
    @Nullable
    private BigDecimal appendReturnLine(
            ReturnOrder returnOrder,
            ReturnLineCommand lineCommand,
            SalesOrder original,
            Map<UUID, SalesOrderLine> soldLines,
            Set<UUID> seenLineIds,
            List<OverCapReturnException.LineCap> overCap) {
        ReturnCondition condition = parseCondition(lineCommand.condition());
        SalesOrderLine sold = requireReturnableSoldLine(lineCommand, condition, original, soldLines, seenLineIds);

        // Row-lock the sold line so concurrent returns of the same remainder serialize (R5.2).
        salesOrderLineRepository.findByOrderLineIdForUpdate(sold.getOrderLineId());
        int alreadyReturned = returnOrderLineRepository.sumReturnedQty(sold.getOrderLineId(), CAP_EXCLUDED_STATUSES);
        int returnableQty = Math.max(0, sold.getQuantity() - alreadyReturned);
        if (lineCommand.returnQty() > returnableQty) {
            overCap.add(
                    new OverCapReturnException.LineCap(sold.getOrderLineId(), lineCommand.returnQty(), returnableQty));
            return null;
        }

        BigDecimal lineRefund = perLineRefund(sold, lineCommand.returnQty());
        returnOrder.addLine(ReturnOrderLine.builder()
                .originalOrderLineId(sold.getOrderLineId())
                .itemSku(sold.getItemSku())
                .returnQty(lineCommand.returnQty())
                .condition(condition)
                .lineRefund(lineRefund)
                .serialNumbers(new ArrayList<>(lineCommand.serialNumbers()))
                .build());
        return lineRefund;
    }

    /**
     * The four ways a requested line can be rejected outright, before any cap arithmetic.
     *
     * <p>These are argument errors rather than cap failures, so each throws where it is found
     * instead of being collected: a duplicated, unknown, non-positive or non-returnable line means
     * the request itself is wrong, and reporting a remaining quantity for it would be meaningless.
     *
     * @return the sold line this command refers to
     */
    private SalesOrderLine requireReturnableSoldLine(
            ReturnLineCommand lineCommand,
            ReturnCondition condition,
            SalesOrder original,
            Map<UUID, SalesOrderLine> soldLines,
            Set<UUID> seenLineIds) {
        // One return line per sold line: duplicates would each cap-check against the same
        // remainder (letting the combined qty exceed the cap) and collide on the inventory
        // restock idempotency key (returnOrderId:originalOrderLineId).
        if (!seenLineIds.add(lineCommand.originalOrderLineId())) {
            throw new ReturnRequestValidationException("Duplicate return line for order line "
                    + lineCommand.originalOrderLineId() + "; combine the quantity into a single line");
        }
        SalesOrderLine sold = soldLines.get(lineCommand.originalOrderLineId());
        if (sold == null) {
            throw new ReturnRequestValidationException(
                    "Line " + lineCommand.originalOrderLineId() + " is not part of order " + original.getOrderId());
        }
        if (lineCommand.returnQty() <= 0) {
            throw new ReturnRequestValidationException(
                    "returnQty must be positive for line " + lineCommand.originalOrderLineId());
        }
        if (!lineReturnable(sold)) {
            if (condition == ReturnCondition.WARRANTY) {
                throw new WarrantyReturnRoutingException(sold.getOrderLineId());
            }
            throw new ReturnLineNotReturnableException(sold.getOrderLineId());
        }
        return sold;
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull ReturnOrderSummary getReturn(@NonNull UUID returnOrderId) {
        return toSummary(require(returnOrderId));
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull List<ReturnOrderSummary> listByOriginalOrder(@NonNull UUID originalOrderId) {
        return returnOrderRepository.findByOriginalOrderIdOrderByCreatedAtDesc(originalOrderId).stream()
                .map(this::toSummary)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull List<ReturnableLineView> returnableLines(@NonNull UUID originalOrderId) {
        SalesOrder original = salesOrderRepository
                .findById(originalOrderId)
                .orElseThrow(() -> new SalesOrderNotFoundException(originalOrderId));
        if (original.getStatus() != SalesOrderStatus.COMPLETED) {
            throw new IllegalStateException("Returnable lines are only available for COMPLETED orders; order "
                    + original.getOrderId() + " is " + original.getStatus());
        }
        List<SalesOrderLine> soldLines = salesOrderLineRepository.findByOrder_OrderId(original.getOrderId());
        List<UUID> lineIds =
                soldLines.stream().map(SalesOrderLine::getOrderLineId).toList();
        Map<UUID, Integer> returnedByLine = new HashMap<>();
        if (!lineIds.isEmpty()) {
            for (Object[] row : returnOrderLineRepository.sumReturnedQtyByLine(lineIds, CAP_EXCLUDED_STATUSES)) {
                returnedByLine.put((UUID) row[0], ((Number) row[1]).intValue());
            }
        }
        List<ReturnableLineView> views = new ArrayList<>();
        for (SalesOrderLine sold : soldLines) {
            boolean returnable = lineReturnable(sold);
            int already = returnedByLine.getOrDefault(sold.getOrderLineId(), 0);
            int returnableQty = returnable ? Math.max(0, sold.getQuantity() - already) : 0;
            views.add(new ReturnableLineView(
                    sold.getOrderLineId(), sold.getItemSku(), sold.getQuantity(), already, returnableQty, returnable));
        }
        return views;
    }

    @Override
    @Transactional
    public @NonNull ReturnOrderSummary approveReturn(@NonNull UUID returnOrderId) {
        ReturnOrder returnOrder = require(returnOrderId);
        if (returnOrder.getStatus() != ReturnOrderStatus.PENDING_APPROVAL) {
            throw new IllegalStateException("Only a PENDING_APPROVAL return can be approved; " + returnOrderId + " is "
                    + returnOrder.getStatus());
        }
        UUID reviewerUserId = SecurityContextHelper.getCurrentUserIdAsUuidOrDefault(
                UUID.fromString("00000000-0000-0000-0000-000000000000"));
        returnOrder.setStatus(ReturnOrderStatus.RETURN_REQUESTED);
        returnOrder.setApprovedByUserId(reviewerUserId);
        returnOrder.setApprovedAt(clock.instant());
        returnOrder.setUpdatedBy(SecurityContextHelper.getCurrentUsernameOrDefault(SYSTEM_ACTOR));
        return toSummary(returnOrderRepository.save(returnOrder));
    }

    @Override
    @Transactional
    public @NonNull ReturnOrderSummary rejectReturn(@NonNull UUID returnOrderId, @NonNull String reason) {
        ReturnOrder returnOrder = require(returnOrderId);
        if (returnOrder.getStatus() != ReturnOrderStatus.PENDING_APPROVAL) {
            throw new IllegalStateException("Only a PENDING_APPROVAL return can be rejected; " + returnOrderId + " is "
                    + returnOrder.getStatus());
        }
        returnOrder.setStatus(ReturnOrderStatus.REJECTED);
        returnOrder.setRejectionReason(reason);
        returnOrder.setUpdatedBy(SecurityContextHelper.getCurrentUsernameOrDefault(SYSTEM_ACTOR));
        return toSummary(returnOrderRepository.save(returnOrder));
    }

    @Override
    @Transactional
    public @NonNull ReturnOrderSummary processReturn(@NonNull UUID returnOrderId) {
        ReturnOrder returnOrder = require(returnOrderId);
        if (returnOrder.getStatus() == ReturnOrderStatus.COMPLETED) {
            return toSummary(returnOrder);
        }
        if (returnOrder.getStatus() != ReturnOrderStatus.RETURN_REQUESTED) {
            throw new IllegalStateException(
                    "Return saga requires RETURN_REQUESTED; " + returnOrderId + " is " + returnOrder.getStatus());
        }
        return runSaga(returnOrder);
    }

    @Override
    @Transactional
    public @NonNull ReturnOrderSummary retryReturn(@NonNull UUID returnOrderId) {
        ReturnOrder returnOrder = require(returnOrderId);
        if (returnOrder.getStatus() == ReturnOrderStatus.COMPLETED) {
            return toSummary(returnOrder);
        }
        if (returnOrder.getStatus() != ReturnOrderStatus.REFUND_FAILED) {
            throw new IllegalStateException("Return retry only allowed from REFUND_FAILED; " + returnOrderId + " is "
                    + returnOrder.getStatus());
        }
        return runSaga(returnOrder);
    }

    /**
     * Refund → emit → complete. The refund must succeed before any stock signal: a failure parks
     * the return at REFUND_FAILED (retryable) and stock is never touched (spec R5.3). Inventory
     * restock is event-driven — pos-inventory consumes {@code order.order.returned} and restocks
     * RESTOCK lines only (SCRAP skipped), per ADR-0044 (no synchronous pos-order → pos-inventory
     * call).
     */
    private ReturnOrderSummary runSaga(ReturnOrder returnOrder) {
        String actor = SecurityContextHelper.getCurrentUsernameOrDefault(SYSTEM_ACTOR);
        issueRefund(returnOrder, actor);

        returnOrder.setStatus(ReturnOrderStatus.REFUND_ISSUED);
        returnOrder.setFailureReason(null);
        returnOrder.setUpdatedBy(actor);
        returnOrderRepository.save(returnOrder);

        returnOrder.setStatus(ReturnOrderStatus.COMPLETED);
        returnOrder.setReturnedAt(clock.instant());
        returnOrder.setUpdatedBy(actor);
        ReturnOrder saved = returnOrderRepository.save(returnOrder);

        // Emit after the final COMPLETED state is persisted so the fact carries the completed
        // aggregate's version and returnedAt. The outbox write shares this transaction, so a publish
        // failure rolls the whole saga back to RETURN_REQUESTED (retryable via process).
        domainEventPublisher.publishOrderReturned(saved);
        log.info(
                "Return {} completed (refund {} via {})",
                saved.getReturnOrderId(),
                saved.getTotalRefund(),
                saved.getRefundMethod());
        return toSummary(saved);
    }

    private void issueRefund(ReturnOrder returnOrder, String actor) {
        switch (returnOrder.getRefundMethod()) {
            case ORIGINAL_TENDER -> reverseOriginalTender(returnOrder, actor);
            case STORE_CREDIT, ON_ACCOUNT_CREDIT -> {
                // Store credit requires a validated customer (story I2); on-account credit likewise
                // needs a customer account. The credit-issuance artifacts (store-credit ledger /
                // AR credit memo) are owned by pos-invoice / pos-accounting — a paired follow-up;
                // the saga records the refund intent here so the flow is complete and retryable.
                if (returnOrder.getCustomerId() == null) {
                    parkRefundFailed(
                            returnOrder,
                            returnOrder.getRefundMethod() + " refund requires a customer on the return",
                            actor);
                    throw new IllegalStateException(
                            returnOrder.getRefundMethod() + " refund requires a customer on the return");
                }
                log.info(
                        "Return {} refund recorded as {} for customer {}",
                        returnOrder.getReturnOrderId(),
                        returnOrder.getRefundMethod(),
                        returnOrder.getCustomerId());
            }
        }
    }

    /** Reverse the original order's settled payments up to the refund total (spec R5.3). */
    private void reverseOriginalTender(ReturnOrder returnOrder, String actor) {
        if (returnOrder.getTotalRefund().signum() <= 0) {
            return;
        }
        if (returnOrder.getOriginalInvoiceId() == null) {
            parkRefundFailed(returnOrder, "No invoice on the original order to refund against", actor);
            throw new IllegalStateException("No invoice on the original order to refund against");
        }
        Map<UUID, BigDecimal> netByIntent = netSettledByIntent(returnOrder.getOriginalOrderId());
        BigDecimal remaining = returnOrder.getTotalRefund();
        List<Map.Entry<UUID, BigDecimal>> intents = new ArrayList<>(netByIntent.entrySet());
        intents.sort(Map.Entry.<UUID, BigDecimal>comparingByValue().reversed());
        for (Map.Entry<UUID, BigDecimal> intent : intents) {
            if (remaining.signum() <= 0) {
                break;
            }
            BigDecimal amount = intent.getValue().min(remaining);
            if (amount.signum() <= 0) {
                continue;
            }
            PaymentReversalResult result = invoicingPort.reversePayment(
                    returnOrder.getOriginalInvoiceId(),
                    intent.getKey(),
                    new ReversePaymentCommand(
                            "REFUND",
                            amount,
                            CURRENCY,
                            "Return " + returnOrder.getReturnOrderId(),
                            returnOrder.getOriginalOrderId(),
                            returnOrder.getReturnOrderId() + "-" + intent.getKey()));
            if (!result.success()) {
                parkRefundFailed(returnOrder, "Payment reversal failed: " + result.resultMessage(), actor);
                throw new IllegalStateException("Payment reversal failed: " + result.resultMessage());
            }
            remaining = remaining.subtract(amount);
        }
        if (remaining.signum() > 0) {
            parkRefundFailed(
                    returnOrder,
                    "Insufficient settled original tender to refund " + returnOrder.getTotalRefund(),
                    actor);
            throw new IllegalStateException("Insufficient settled original tender to refund the return total");
        }
    }

    private void parkRefundFailed(ReturnOrder returnOrder, String reason, String actor) {
        returnOrder.setStatus(ReturnOrderStatus.REFUND_FAILED);
        returnOrder.setFailureReason(reason);
        returnOrder.setUpdatedBy(actor);
        returnOrderRepository.save(returnOrder);
        log.warn("Return {} refund failed: {}", returnOrder.getReturnOrderId(), reason);
    }

    /** Net settled amount per payment intent (Σ SETTLED − Σ REVERSED), positive entries only. */
    private Map<UUID, BigDecimal> netSettledByIntent(UUID orderId) {
        Map<UUID, BigDecimal> net = new LinkedHashMap<>();
        for (OrderPaymentRecord record : paymentRecordRepository.findByOrderId(orderId)) {
            if (record.getPaymentIntentId() == null) {
                continue;
            }
            BigDecimal signed = record.getRecordType() == OrderPaymentRecord.RecordType.SETTLED
                    ? record.getAmount()
                    : record.getAmount().negate();
            net.merge(record.getPaymentIntentId(), signed, BigDecimal::add);
        }
        net.values().removeIf(amount -> amount.signum() <= 0);
        return net;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private ReturnOrder require(UUID returnOrderId) {
        return returnOrderRepository
                .findById(returnOrderId)
                .orElseThrow(() -> new ReturnOrderNotFoundException(returnOrderId));
    }

    /**
     * A line is returnable when explicitly flagged returnable, or — for counter lines that leave
     * the flag null — by policy. Workorder-consumed lines without the imported returnable flag are
     * not returnable at the counter (warranty claims route to pos-warranty, resolved Q6).
     */
    private static boolean lineReturnable(SalesOrderLine line) {
        if (Boolean.TRUE.equals(line.getReturnable())) {
            return true;
        }
        if (Boolean.FALSE.equals(line.getReturnable())) {
            return false;
        }
        return line.getSourceType() != SourceType.WORKORDER;
    }

    /** Pro-rata per-unit refund (line total, tax included) × returnQty. */
    private static BigDecimal perLineRefund(SalesOrderLine sold, int returnQty) {
        BigDecimal lineTotal = sold.getLineTotal() == null ? BigDecimal.ZERO : sold.getLineTotal();
        if (sold.getQuantity() <= 0) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        return lineTotal
                .multiply(BigDecimal.valueOf(returnQty))
                .divide(BigDecimal.valueOf(sold.getQuantity()), 4, RoundingMode.HALF_UP);
    }

    private static RefundMethod parseRefundMethod(String raw) {
        try {
            return RefundMethod.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new ReturnRequestValidationException("Unknown refund method: " + raw);
        }
    }

    private static ReturnCondition parseCondition(String raw) {
        try {
            return ReturnCondition.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new ReturnRequestValidationException("Unknown return condition: " + raw);
        }
    }

    private static String normalizeBlank(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private ReturnOrderSummary toSummary(ReturnOrder r) {
        List<ReturnOrderSummary.Line> lines = r.getLines().stream()
                .map(l -> new ReturnOrderSummary.Line(
                        l.getReturnLineId(),
                        l.getOriginalOrderLineId(),
                        l.getItemSku(),
                        l.getReturnQty(),
                        l.getCondition().name(),
                        l.getLineRefund(),
                        l.getSerialNumbers() == null ? List.of() : List.copyOf(l.getSerialNumbers())))
                .toList();
        return new ReturnOrderSummary(
                r.getReturnOrderId(),
                r.getOriginalOrderId(),
                r.getOriginalOrderNumber(),
                r.getLocationId(),
                r.getCustomerId(),
                r.getRefundMethod().name(),
                r.getStatus().name(),
                r.getReasonCode(),
                r.getTotalRefund(),
                r.getFailureReason(),
                lines,
                r.getReturnedAt(),
                r.getCreatedAt());
    }
}
