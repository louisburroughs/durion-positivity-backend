package com.positivity.inventory.internal.service;

import com.positivity.domainevents.order.PurchaseOrderUpdatedV1;
import com.positivity.inventory.internal.entity.ExtPurchaseOrderLineReplica;
import com.positivity.inventory.internal.entity.ExtPurchaseOrderReplica;
import com.positivity.inventory.internal.enums.SourceDocumentType;
import com.positivity.inventory.internal.exception.InvalidPoReferenceException;
import com.positivity.inventory.internal.exception.SourceDocumentAlreadyReceivedException;
import com.positivity.inventory.internal.exception.SourceDocumentNotFoundException;
import com.positivity.inventory.internal.exception.UnsupportedSourceDocumentTypeException;
import com.positivity.inventory.internal.repository.ExtPurchaseOrderLineRepository;
import com.positivity.inventory.internal.repository.ExtPurchaseOrderRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
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

    /**
     * Resolves the still-expected lines of {@code sourceDocumentId}.
     *
     * @throws UnsupportedSourceDocumentTypeException when the type is not {@link SourceDocumentType#PO}
     * @throws SourceDocumentNotFoundException when no such order has been projected
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
                .orElseThrow(() -> new SourceDocumentNotFoundException(
                        "No purchase order " + sourceDocumentId + " has been projected into pos-inventory"));

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
     * hold, so it is reported the same way an unknown one is.
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
