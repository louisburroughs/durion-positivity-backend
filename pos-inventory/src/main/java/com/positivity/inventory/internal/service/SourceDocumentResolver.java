package com.positivity.inventory.internal.service;

import com.positivity.domainevents.order.PurchaseOrderUpdatedV1;
import com.positivity.inventory.internal.entity.ExtPurchaseOrderLineReplica;
import com.positivity.inventory.internal.entity.ExtPurchaseOrderReplica;
import com.positivity.inventory.internal.enums.SourceDocumentType;
import com.positivity.inventory.internal.exception.InvalidPoReferenceException;
import com.positivity.inventory.internal.exception.SourceDocumentAlreadyReceivedException;
import com.positivity.inventory.internal.exception.SourceDocumentLinesUnavailableException;
import com.positivity.inventory.internal.exception.SourceDocumentNotFoundException;
import com.positivity.inventory.internal.exception.UnsupportedSourceDocumentTypeException;
import com.positivity.inventory.internal.repository.ExtPurchaseOrderLineRepository;
import com.positivity.inventory.internal.repository.ExtPurchaseOrderRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves a receiving session's source document from the projected purchase order (issue #1480).
 *
 * <p>Replaces {@code SourceDocumentStubClient}, which was gated behind
 * {@code pos.inventory.receiving.stub.enabled} — false by default, which returned no lines and made
 * every {@code POST /v1/inventory/receiving/sessions} answer {@code 404}. Enabling it pointed at
 * {@code /stub/v1/source-documents/...}, a path no service serves. Everything built on a session
 * (staging, cross-dock, session reads) was unreachable as a result.
 *
 * <h2>Why a replica and not a call to pos-order</h2>
 *
 * pos-order owns the purchase order, so the obvious fix is to ask it. ADR-0044 forbids that: a
 * synchronous {@code internal.client} from one domain module to another fails the build, and
 * amending the wall for this would need an ADR amendment it does not deserve — because the data is
 * already here. {@code PurchaseOrderUpdatedV1} projects every order onto
 * {@code ext_purchase_order} / {@code ext_purchase_order_line}, and that fact's own contract names
 * receiving as one of the consumers it exists for ("receiving asks what was ordered"). The
 * goods-receipt path in this module already resolves POs exactly this way, which is why goods
 * receipts worked while sessions did not.
 *
 * <h2>What it reports</h2>
 *
 * Expected quantity is the line's {@code openQuantity} — what is still outstanding — so a partially
 * received order never re-expects what has already arrived, and a line with nothing open is
 * dropped. An order whose lines are all closed reads as already received rather than as an empty
 * session. Only {@link PurchaseOrderUpdatedV1#OPEN_SUPPLY_STATUSES} can be received against: a
 * DRAFT order has not been committed to and a CANCELLED one never will be.
 *
 * <h2>Not found vs. not yet replicated (#1492)</h2>
 *
 * The header and its lines are projected atomically, so a missing {@code ext_purchase_order} row
 * could mean either that replication has not caught up yet or that the id names no order at all —
 * and, per ADR-0044, this module cannot call pos-order to tell the two apart. A source document id
 * this module could never parse as a purchase order UUID is reported as not found outright; a
 * well-formed UUID absent from the projection is reported as unavailable, not missing, leaving the
 * caller to retry.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SourceDocumentResolver {

    /** Statuses on which an order has arrived in full and has nothing left to receive. */
    private static final List<String> RECEIVED_STATUSES = List.of("FULLY_RECEIVED", "RECEIVED", "COMPLETED", "CLOSED");

    private final ExtPurchaseOrderRepository purchaseOrderRepository;
    private final ExtPurchaseOrderLineRepository purchaseOrderLineRepository;
    private final ReceiptCostCurrencyPolicy receiptCostCurrencyPolicy;

    /**
     * Resolves the still-expected lines of {@code sourceDocumentId}.
     *
     * @throws UnsupportedSourceDocumentTypeException when the type is not {@link SourceDocumentType#PO}
     * @throws SourceDocumentNotFoundException when {@code sourceDocumentId} cannot even parse as a
     *     purchase order identifier
     * @throws SourceDocumentLinesUnavailableException when {@code sourceDocumentId} is a well-formed
     *     purchase order id absent from the projection — not yet replicated, or unknown; this module
     *     cannot tell which
     * @throws SourceDocumentAlreadyReceivedException when the order has nothing left to receive
     * @throws InvalidPoReferenceException when the order is not in a receivable status
     */
    @NonNull
    public SourceDocument resolve(@NonNull SourceDocumentType sourceDocumentType, @NonNull String sourceDocumentId) {
        if (sourceDocumentType != SourceDocumentType.PO) {
            throw new UnsupportedSourceDocumentTypeException(sourceDocumentType.name());
        }

        UUID poId = parsePurchaseOrderId(sourceDocumentId);
        ExtPurchaseOrderReplica order = purchaseOrderRepository
                .findById(poId)
                .orElseThrow(() -> new SourceDocumentLinesUnavailableException(
                        "Purchase order " + sourceDocumentId + " has not replicated its lines into pos-inventory yet"));

        String status = order.getStatus();
        if (isReceivedStatus(status)) {
            throw new SourceDocumentAlreadyReceivedException(sourceDocumentId + " has already been fully received");
        }
        if (!PurchaseOrderUpdatedV1.OPEN_SUPPLY_STATUSES.contains(status)) {
            throw new InvalidPoReferenceException("INVALID_PO_REFERENCE: purchase order " + sourceDocumentId + " is "
                    + status + " and cannot be received against");
        }

        List<SourceDocumentLine> lines = receivableLines(poId);
        if (lines.isEmpty()) {
            // Every line is closed out even though the header has not been marked received.
            throw new SourceDocumentAlreadyReceivedException(sourceDocumentId + " has already been fully received");
        }

        log.debug("Resolved purchase order {}: status={} receivableLines={}", poId, status, lines.size());
        return new SourceDocument(sourceDocumentId, status, lines);
    }

    /**
     * The location the session's source document is being received at — the purchase order's
     * ship-to, which is the site its stock lands at (#2009).
     *
     * <p>Answered from the same projection {@link #resolve} reads, and empty rather than throwing
     * whenever it cannot be answered: a document type with no ship-to, an id that is not a purchase
     * order identifier, or an order not yet replicated. Callers use it to pick the site whose
     * declared staging location applies, and a fallback already covers the unknown case.
     */
    public Optional<UUID> resolveShipToLocationId(
            @Nullable SourceDocumentType sourceDocumentType, @Nullable String sourceDocumentId) {
        if (sourceDocumentType != SourceDocumentType.PO || sourceDocumentId == null) {
            return Optional.empty();
        }

        UUID poId;
        try {
            poId = UUID.fromString(sourceDocumentId.trim());
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }

        return purchaseOrderRepository.findById(poId).map(ExtPurchaseOrderReplica::getShipToLocationId);
    }

    /**
     * The per-base-unit document cost a receiving session's {@code GOODS_RECEIPT} row carries
     * (#2203, ADR-0048 IMP-002): the purchase order line's price, divided by the base units it
     * prices and moved from minor to major units of the order's currency.
     *
     * <p>The line is the one the receiving line was built from; a receiving line built before that
     * link was kept, or whose linked line a revision has since replaced, falls back to the order's
     * only line for the product. Empty whenever the cost
     * cannot be known — not a purchase order, no such line (or several candidates), an unpriced
     * line, a line projected before pos-order published what its price is per, or an order not in
     * the functional currency ({@link #receiptCostHold}, ADR-0067 DF-6) — and the receipt then posts
     * without a document cost, entering at the product's current average.
     */
    public Optional<BigDecimal> resolveReceiptUnitCost(
            @Nullable SourceDocumentType sourceDocumentType,
            @Nullable String sourceDocumentId,
            @Nullable UUID sourceLineId,
            @Nullable String productId) {
        if (sourceDocumentType != SourceDocumentType.PO || sourceDocumentId == null) {
            return Optional.empty();
        }
        UUID poId;
        try {
            poId = UUID.fromString(sourceDocumentId.trim());
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
        Optional<ExtPurchaseOrderReplica> order = purchaseOrderRepository.findById(poId);
        if (order.isEmpty()
                || receiptCostCurrencyPolicy
                        .awaitingCostReason(order.get().getCurrency())
                        .isPresent()) {
            return Optional.empty();
        }
        String currency = order.get().getCurrency();

        // A revision rebuilds the order's lines under new ids, so a link can outlive its line; the
        // sole-line fallback then applies exactly as for a line that was never linked.
        Optional<ExtPurchaseOrderLineReplica> orderLine = (sourceLineId != null
                        ? purchaseOrderLineRepository.findById(sourceLineId)
                        : Optional.<ExtPurchaseOrderLineReplica>empty())
                .or(() -> soleLineForProduct(poId, productId));
        return orderLine
                .filter(line -> line.getUnitCostMinor() != null && line.getConversionFactor() != null)
                .map(line ->
                        ReceiptUnitCosts.perBaseUnit(line.getUnitCostMinor(), line.getConversionFactor(), currency));
    }

    /**
     * Why a receipt against this source document takes no document cost, when the reason is its
     * currency (ADR-0067 DF-6, #2314): the purchase order is priced in a currency other than the
     * functional one, or in none. Empty for an order in the functional currency, and whenever the
     * document is not a projected purchase order, since then there is no cost to hold back.
     */
    public Optional<String> receiptCostHold(
            @Nullable SourceDocumentType sourceDocumentType, @Nullable String sourceDocumentId) {
        if (sourceDocumentType != SourceDocumentType.PO || sourceDocumentId == null) {
            return Optional.empty();
        }
        UUID poId;
        try {
            poId = UUID.fromString(sourceDocumentId.trim());
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
        return purchaseOrderRepository
                .findById(poId)
                .flatMap(order -> receiptCostCurrencyPolicy.awaitingCostReason(order.getCurrency()));
    }

    /**
     * The purchase order a session on this source document receives against (#2417): the order
     * its {@code goodsreceipt.recorded} names. Empty for anything that is not a purchase order
     * identifier — such a session has no order whose outstanding quantities it could settle.
     */
    public Optional<UUID> receivingPurchaseOrderId(
            @Nullable SourceDocumentType sourceDocumentType, @Nullable String sourceDocumentId) {
        if (sourceDocumentType != SourceDocumentType.PO || sourceDocumentId == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(sourceDocumentId.trim()));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    /**
     * The document currency of the purchase order a session receives against, as the order replica
     * states it (CAP:550 S41 #2602): the currency of every amount on its {@code goodsreceipt.recorded}.
     * Never defaulted (ADR-0067 R-2): empty when the order is not projected or states none.
     */
    public Optional<String> purchaseOrderCurrency(@NonNull UUID purchaseOrderId) {
        return purchaseOrderRepository.findById(purchaseOrderId).map(ExtPurchaseOrderReplica::getCurrency);
    }

    /**
     * Which purchase-order line a received base quantity settles, and what it is worth in minor
     * units of the order's currency (#2417) — the two things {@code goodsreceipt.recorded} needs
     * per line for pos-order to reduce the line's open quantity and the order's open balance.
     *
     * <p>The line is found exactly as {@link #resolveReceiptUnitCost} finds it: the linked line,
     * else the order's only line for the product. Unlike the ledger's cost, the value is not held
     * back for an order outside the functional currency: it is deducted from that order's own
     * balance, which is kept in the order's currency. An unpriced line, or one projected without a
     * conversion factor, is attributed but unvalued: pos-order reduces its open quantity and leaves
     * the balance. With no line at all the receipt line is unattributed and unvalued, and pos-order
     * changes nothing for it.
     */
    @NonNull
    public ReceiptLineValue valueReceiptLine(
            @NonNull UUID purchaseOrderId,
            @Nullable UUID sourceLineId,
            @Nullable String productId,
            @NonNull BigDecimal baseQuantity) {
        Optional<ExtPurchaseOrderLineReplica> orderLine = (sourceLineId != null
                        ? purchaseOrderLineRepository.findById(sourceLineId)
                        : Optional.<ExtPurchaseOrderLineReplica>empty())
                .or(() -> soleLineForProduct(purchaseOrderId, productId));
        if (orderLine.isEmpty()) {
            return new ReceiptLineValue(null, 0L);
        }
        ExtPurchaseOrderLineReplica line = orderLine.get();
        return new ReceiptLineValue(line.getLineId(), accruedMinor(line, baseQuantity));
    }

    /**
     * {@code baseQuantity} priced at the line's price per base unit, HALF_UP to whole minor units
     * (ADR-0067 OP-11; CAP:550 S41 #2602), the rounding the line's inventory value takes too.
     */
    private static long accruedMinor(@NonNull ExtPurchaseOrderLineReplica line, @NonNull BigDecimal baseQuantity) {
        Long unitCostMinor = line.getUnitCostMinor();
        if (unitCostMinor == null) {
            return 0L;
        }
        BigDecimal factor = line.getConversionFactor();
        if (factor == null || factor.signum() <= 0) {
            // A null factor marks a line projected before pos-order published what its price is
            // per, so no per-base-unit price can be derived — the same reason
            // resolveReceiptUnitCost declines to cost it. Guessing one would deduct the wrong value.
            log.warn(
                    "Purchase order line {} has no usable conversion factor ({}); its receipt is reported unvalued",
                    line.getLineId(),
                    factor);
            return 0L;
        }
        return baseQuantity
                .multiply(BigDecimal.valueOf(unitCostMinor))
                .divide(factor, 0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    private Optional<ExtPurchaseOrderLineReplica> soleLineForProduct(UUID poId, @Nullable String productId) {
        if (productId == null) {
            return Optional.empty();
        }
        List<ExtPurchaseOrderLineReplica> matches = purchaseOrderLineRepository.findByPurchaseOrderId(poId).stream()
                .filter(line ->
                        line.getSkuId() != null && line.getSkuId().toString().equalsIgnoreCase(productId.trim()))
                .toList();
        return matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
    }

    private List<SourceDocumentLine> receivableLines(UUID poId) {
        List<ExtPurchaseOrderLineReplica> replicas =
                new ArrayList<>(purchaseOrderLineRepository.findByPurchaseOrderId(poId));
        replicas.sort(Comparator.comparingInt(ExtPurchaseOrderLineReplica::getLineNumber));

        List<SourceDocumentLine> lines = new ArrayList<>(replicas.size());
        for (ExtPurchaseOrderLineReplica replica : replicas) {
            BigDecimal open = replica.getOpenQuantity();
            if (open == null || open.signum() <= 0) {
                continue;
            }
            SourceDocumentLine line = new SourceDocumentLine();
            line.setSourceLineId(
                    replica.getLineId() == null ? null : replica.getLineId().toString());
            line.setProductId(
                    replica.getSkuId() == null ? null : replica.getSkuId().toString());
            line.setExpectedQuantity(open);
            lines.add(line);
        }
        return lines;
    }

    /**
     * A source document id that is not a purchase-order UUID names no order this module could ever
     * hold, projected or not — a genuine not-found, unlike a well-formed id absent from the
     * projection (#1492).
     */
    private static UUID parsePurchaseOrderId(String sourceDocumentId) {
        try {
            return UUID.fromString(sourceDocumentId);
        } catch (IllegalArgumentException e) {
            throw new SourceDocumentNotFoundException(
                    "Source document id " + sourceDocumentId + " is not a purchase order identifier");
        }
    }

    private static boolean isReceivedStatus(@Nullable String status) {
        return status != null && RECEIVED_STATUSES.contains(status.trim().toUpperCase(Locale.ROOT));
    }

    /**
     * A source document as receiving needs it: its identifier, the owner's status string, and the
     * lines that still expect goods.
     */
    public record SourceDocument(
            @NonNull String sourceDocumentId,
            @Nullable String status,
            @NonNull List<SourceDocumentLine> lines) {

        public SourceDocument {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }

    /**
     * What one received line settles on its purchase order (#2417).
     *
     * @param poLineId           the order line received against; null when none could be attributed
     * @param accruedAmountMinor the received quantity's value in minor units of the order's currency
     */
    public record ReceiptLineValue(@Nullable UUID poLineId, long accruedAmountMinor) {}

    /**
     * One receivable line of a source document.
     *
     * <p>{@code expectedQuantity} is in the product's base unit, matching what a receiving line
     * compares received quantities against. The document unit the order was keyed in is
     * deliberately not carried here: it belongs to the ordered quantity, not to this still-open
     * base quantity, and pairing the two would label a base number with a document unit. Receiving
     * takes its document unit from the receive request instead (odoo-parity B2, #1034).
     */
    @lombok.Data
    public static class SourceDocumentLine {
        private String sourceLineId;
        private String productId;
        private BigDecimal expectedQuantity;
    }
}
