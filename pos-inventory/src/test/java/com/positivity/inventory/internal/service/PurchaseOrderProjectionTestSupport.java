package com.positivity.inventory.internal.service;

import com.positivity.domainevents.order.PurchaseOrderLine;
import com.positivity.domainevents.order.PurchaseOrderUpdatedV1;
import com.positivity.inventory.internal.entity.ExtPurchaseOrderLineReplica;
import com.positivity.inventory.internal.entity.ExtPurchaseOrderReplica;
import com.positivity.inventory.internal.entity.PurchaseOrderEntity;
import com.positivity.inventory.internal.repository.ExtPurchaseOrderLineRepository;
import com.positivity.inventory.internal.repository.ExtPurchaseOrderRepository;
import com.positivity.inventory.internal.repository.PurchaseOrderRepository;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Projects a purchase order into {@code ext_purchase_order} the way the running system would.
 *
 * <h2>Why tests need this at all</h2>
 *
 * Availability-to-promise reads the projection, not the aggregate (#1333). A test that saves a
 * {@link PurchaseOrderEntity} and expects supply to move is describing a state the system never
 * reaches: in production the order is published as a fact and applied by
 * {@link PurchaseOrderProjectionListener}. Without Kafka in the test context nothing closes that
 * loop, so this helper closes it explicitly — one call, at the point the order is saved.
 *
 * <h2>Why it borrows the production mapping</h2>
 *
 * It builds the same {@link PurchaseOrderUpdatedV1} the publisher builds, through the same
 * package-private {@code toFact}. A helper carrying its own copy of the mapping would keep the
 * tests green while the real one drifted — the null-open-quantity rule in particular, where
 * "never received against" must mean "all of it is still outstanding" rather than "none of it is".
 * Copying that rule here would let a regression in the real one go unnoticed.
 *
 * <p>Once the aggregate moves to pos-order (#1334) this becomes the only way these tests can seed
 * supply, because the aggregate will no longer exist in this module.
 */
@Component
@RequiredArgsConstructor
public class PurchaseOrderProjectionTestSupport {

    private final PurchaseOrderRepository purchaseOrderRepository;
    private final ExtPurchaseOrderRepository extOrderRepository;
    private final ExtPurchaseOrderLineRepository extLineRepository;

    /** Projects an order already held in memory, lines included. */
    public void project(PurchaseOrderEntity order) {
        apply(PurchaseOrderFactPublisher.toFact(order, order.getLines(), Instant.now()));
    }

    /**
     * Projects an order by id, for tests that create or mutate one through the real service or raw
     * SQL. Transactional so the order's lazily loaded lines are readable.
     */
    @Transactional
    public void project(UUID purchaseOrderId) {
        purchaseOrderRepository.findById(purchaseOrderId).ifPresent(this::project);
    }

    private void apply(PurchaseOrderUpdatedV1 fact) {
        extOrderRepository.save(ExtPurchaseOrderReplica.builder()
                .purchaseOrderId(fact.purchaseOrderId())
                .poNumber(fact.poNumber())
                .vendorId(fact.vendorId())
                .status(fact.status())
                .shipToLocationId(fact.shipToLocationId())
                .expectedDeliveryDate(fact.expectedDeliveryDate())
                .currency(fact.currency())
                .grandTotalMinor(fact.grandTotalMinor())
                .aggregateVersion(1L)
                .occurredAt(fact.occurredAt())
                .build());

        // Replace, not merge — the same rule the listener follows, so a test that re-projects an
        // order after a change sees the change rather than both versions of it.
        extLineRepository.deleteByPurchaseOrderId(fact.purchaseOrderId());
        for (PurchaseOrderLine line : fact.lines()) {
            extLineRepository.save(ExtPurchaseOrderLineReplica.builder()
                    .lineId(line.lineId() == null ? UUID.randomUUID() : line.lineId())
                    .purchaseOrderId(fact.purchaseOrderId())
                    .lineNumber(line.lineNumber())
                    .skuId(line.skuId())
                    .orderedQuantity(line.orderedQuantity())
                    .openQuantity(line.openQuantity())
                    .unitCostMinor(line.unitCostMinor())
                    .description(line.description())
                    .build());
        }
    }
}
