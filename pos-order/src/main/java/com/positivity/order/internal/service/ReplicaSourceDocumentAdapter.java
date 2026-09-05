package com.positivity.order.internal.service;

import com.positivity.order.internal.client.SourceDocumentLine;
import com.positivity.order.internal.client.SourceDocumentPort;
import com.positivity.order.internal.entity.ExtEstimateLine;
import com.positivity.order.internal.entity.ExtProduct;
import com.positivity.order.internal.entity.ExtWorkorderLine;
import com.positivity.order.internal.entity.SourceType;
import com.positivity.order.internal.exception.SalesOrderRequestValidationException;
import com.positivity.order.internal.exception.SalesOrderUnprocessableException;
import com.positivity.order.internal.repository.ExtEstimateLineRepository;
import com.positivity.order.internal.repository.ExtEstimateRepository;
import com.positivity.order.internal.repository.ExtProductRepository;
import com.positivity.order.internal.repository.ExtWorkorderLineRepository;
import com.positivity.order.internal.repository.ExtWorkorderRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Replica-backed {@link SourceDocumentPort} (parity story E1, spec R7.1): estimate and workorder
 * lines are read from the event-fed {@code ext_estimate(_line)} / {@code ext_workorder(_line)}
 * replicas — pos-workorder is a domain module, so synchronous REST toward it is barred by
 * ADR-0044. Lines import priced-as-approved (never repriced): estimates contribute only
 * APPROVED, non-deleted items; workorders contribute non-declined lines with their explicit
 * {@code returnable} flag (resolved Q6).
 *
 * <p>An unknown document — including a replica that has simply not caught up — fails the link
 * loudly rather than importing nothing silently.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReplicaSourceDocumentAdapter implements SourceDocumentPort {
    private static final String LABOR = "LABOR";

    private static final String APPROVED = "APPROVED";

    private final ExtWorkorderRepository extWorkorderRepository;
    private final ExtWorkorderLineRepository extWorkorderLineRepository;
    private final ExtEstimateRepository extEstimateRepository;
    private final ExtEstimateLineRepository extEstimateLineRepository;
    private final ExtProductRepository extProductRepository;

    @Override
    public @NonNull List<SourceDocumentLine> fetchLines(@NonNull SourceType sourceType, @NonNull String sourceId) {
        UUID documentId = parseId(sourceId);
        return switch (sourceType) {
            case WORKORDER -> workorderLines(documentId);
            case ESTIMATE -> estimateLines(documentId);
        };
    }

    private List<SourceDocumentLine> workorderLines(UUID workorderId) {
        if (extWorkorderRepository.findById(workorderId).isEmpty()) {
            throw new SalesOrderUnprocessableException("Workorder " + workorderId
                    + " is not present in the replica; verify the id or retry once the event feed catches up");
        }
        return extWorkorderLineRepository.findByWorkorderId(workorderId).stream()
                .filter(line -> !Boolean.TRUE.equals(line.getDeclined()))
                .map(this::toWorkorderLine)
                .toList();
    }

    private List<SourceDocumentLine> estimateLines(UUID estimateId) {
        if (extEstimateRepository.findById(estimateId).isEmpty()) {
            throw new SalesOrderUnprocessableException("Estimate " + estimateId
                    + " is not present in the replica; verify the id or retry once the event feed catches up");
        }
        // Spec R7.1: only approved items are contractual.
        return extEstimateLineRepository.findByEstimateId(estimateId).stream()
                .filter(item -> !Boolean.TRUE.equals(item.getDeleted()))
                .filter(item -> APPROVED.equalsIgnoreCase(item.getApprovalStatus()))
                .map(this::toEstimateLine)
                .toList();
    }

    private SourceDocumentLine toWorkorderLine(ExtWorkorderLine line) {
        String sku = line.getLineKind() == ExtWorkorderLine.LineKind.SERVICE
                ? LABOR
                : resolveSku(line.getProductId(), line.getLineId());
        Quantities quantities = normalize(line.getQuantity(), line.getUnitPrice(), line.getLineTotal());
        return new SourceDocumentLine(
                sku,
                line.getDescription() == null ? sku : line.getDescription(),
                quantities.quantity(),
                quantities.unitPrice(),
                line.getLineId().toString(),
                line.getLineKind() == ExtWorkorderLine.LineKind.SERVICE ? Boolean.FALSE : line.getReturnable());
    }

    private SourceDocumentLine toEstimateLine(ExtEstimateLine item) {
        String sku =
                LABOR.equalsIgnoreCase(item.getItemType()) ? LABOR : resolveSku(item.getProductId(), item.getItemId());
        Quantities quantities = normalize(item.getQuantity(), item.getUnitPrice(), item.getLineTotal());
        // Estimate items are pre-work: returnability is decided at settlement (Q6), never here.
        return new SourceDocumentLine(
                sku,
                item.getDescription() == null ? sku : item.getDescription(),
                quantities.quantity(),
                quantities.unitPrice(),
                item.getItemId().toString(),
                null);
    }

    /**
     * Order lines carry integer quantities; source documents carry 4dp decimals (labor hours).
     * Integral quantities import as-is; fractional ones collapse to a single line-total unit so
     * the contractual amount is preserved exactly.
     */
    private static Quantities normalize(
            @Nullable BigDecimal quantity, @Nullable BigDecimal unitPrice, @Nullable BigDecimal lineTotal) {
        BigDecimal qty = quantity == null ? BigDecimal.ONE : quantity;
        BigDecimal price = unitPrice == null ? BigDecimal.ZERO : unitPrice;
        boolean integral = qty.stripTrailingZeros().scale() <= 0 && qty.signum() > 0;
        if (integral) {
            return new Quantities(qty.intValueExact(), price.setScale(4, RoundingMode.HALF_UP));
        }
        BigDecimal total = lineTotal != null ? lineTotal : price.multiply(qty);
        return new Quantities(1, total.setScale(4, RoundingMode.HALF_UP));
    }

    private String resolveSku(@Nullable UUID productId, UUID lineId) {
        if (productId == null) {
            return "ITEM-" + lineId;
        }
        return extProductRepository
                .findById(productId)
                .map(ExtProduct::getSku)
                .filter(sku -> sku != null && !sku.isBlank())
                .orElse("PROD-" + productId);
    }

    private static UUID parseId(String sourceId) {
        try {
            return UUID.fromString(sourceId);
        } catch (IllegalArgumentException e) {
            throw new SalesOrderRequestValidationException("sourceId must be a document UUID: " + sourceId);
        }
    }

    private record Quantities(int quantity, BigDecimal unitPrice) {}
}
