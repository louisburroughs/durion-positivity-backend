package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.entity.ExtPurchaseOrderLineReplica;
import com.positivity.inventory.internal.entity.ExtPurchaseOrderReplica;
import com.positivity.inventory.internal.repository.ExtPurchaseOrderLineRepository;
import com.positivity.inventory.internal.repository.ExtPurchaseOrderRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds {@code ext_purchase_order} the way a published fact would (CAP-320 #1334).
 *
 * <h2>Why tests build orders this way now</h2>
 *
 * pos-inventory has no purchase-order table any more. Everything here that reasons about incoming
 * supply — availability-to-promise, replenishment, receiving, traceability — reads the projection,
 * so a test that wants an order in play states what the order says rather than constructing one.
 * That is not a testing shortcut: it is the only shape the running system has.
 *
 * <p>Before the aggregate moved, this helper wrote both sides and borrowed the production mapping
 * to keep them consistent. There is no second side left to be consistent with.
 */
@Component
@RequiredArgsConstructor
public class PurchaseOrderProjectionTestSupport {

    private final ExtPurchaseOrderRepository extOrderRepository;
    private final ExtPurchaseOrderLineRepository extLineRepository;

    /** One projected line: what was ordered and how much of it is still outstanding. */
    public record Line(UUID skuId, String orderedQuantity, String openQuantity) {}

    /** Projects an order with a single line, the common case in these tests. */
    @Transactional
    public UUID project(String status, UUID siteId, LocalDate expectedDeliveryDate, UUID skuId, String openQuantity) {
        return project(
                UUID.randomUUID(),
                status,
                siteId,
                expectedDeliveryDate,
                List.of(new Line(skuId, openQuantity, openQuantity)));
    }

    /** Projects an order with the lines given, as though its fact had just been applied. */
    @Transactional
    public UUID project(
            UUID purchaseOrderId, String status, UUID siteId, LocalDate expectedDeliveryDate, List<Line> lines) {
        extOrderRepository.save(ExtPurchaseOrderReplica.builder()
                .purchaseOrderId(purchaseOrderId)
                .poNumber("PO-" + purchaseOrderId.toString().substring(0, 8))
                .vendorId(UUID.randomUUID())
                .status(status)
                .shipToLocationId(siteId)
                // Carried through exactly: an undated order stays undated, because the
                // horizon-bounded supply query excludes it rather than treating it as due today.
                .expectedDeliveryDate(expectedDeliveryDate)
                .currency("USD")
                .grandTotalMinor(0L)
                .openBalanceMinor(0L)
                .aggregateVersion(1L)
                .occurredAt(Instant.now())
                .updatedAt(Instant.now())
                .build());

        // Replace, not merge — the same rule the listener follows, so re-projecting an order after
        // a change shows the change rather than both versions of it.
        extLineRepository.deleteByPurchaseOrderId(purchaseOrderId);
        int lineNumber = 1;
        List<ExtPurchaseOrderLineReplica> saved = new ArrayList<>();
        for (Line line : lines) {
            saved.add(extLineRepository.save(ExtPurchaseOrderLineReplica.builder()
                    .lineId(UUID.randomUUID())
                    .purchaseOrderId(purchaseOrderId)
                    .lineNumber(lineNumber++)
                    .skuId(line.skuId())
                    .orderedQuantity(new BigDecimal(line.orderedQuantity()))
                    .openQuantity(new BigDecimal(line.openQuantity()))
                    .unitCostMinor(0L)
                    .description("projection test fixture")
                    .build()));
        }
        return purchaseOrderId;
    }

    /**
     * Projects an order that a receipt will be recorded against.
     *
     * <p>The outstanding balance matters here and nowhere else: it is what the over-receipt guard
     * compares a receipt's value against, so a fixture that left it at zero would have every
     * receipt rejected for reasons that have nothing to do with what it is testing.
     */
    @Transactional
    public UUID projectReceivable(String status, UUID siteId, UUID skuId, String openQuantity, long openBalanceMinor) {
        UUID purchaseOrderId =
                project(UUID.randomUUID(), status, siteId, null, List.of(new Line(skuId, openQuantity, openQuantity)));
        extOrderRepository.findById(purchaseOrderId).ifPresent(order -> {
            order.setOpenBalanceMinor(openBalanceMinor);
            extOrderRepository.save(order);
        });
        return purchaseOrderId;
    }

    /** The single line projected for an order, for tests that need its id. */
    @Transactional
    public UUID lineIdOf(UUID purchaseOrderId) {
        return extLineRepository.findByPurchaseOrderId(purchaseOrderId).stream()
                .findFirst()
                .map(ExtPurchaseOrderLineReplica::getLineId)
                .orElseThrow();
    }

    /** Re-projects an order at a new status, keeping its lines — an approval or a cancellation. */
    @Transactional
    public void restate(UUID purchaseOrderId, String status) {
        extOrderRepository.findById(purchaseOrderId).ifPresent(order -> {
            order.setStatus(status);
            order.setAggregateVersion(order.getAggregateVersion() + 1);
            extOrderRepository.save(order);
        });
    }

    /** Sets one line's outstanding quantity, as a receipt would. */
    @Transactional
    public void setOpenQuantity(UUID purchaseOrderId, String openQuantity) {
        for (ExtPurchaseOrderLineReplica line : extLineRepository.findByPurchaseOrderId(purchaseOrderId)) {
            line.setOpenQuantity(new BigDecimal(openQuantity));
            extLineRepository.save(line);
        }
    }
}
