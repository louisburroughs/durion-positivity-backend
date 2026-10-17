package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.entity.ExtOrderPurchaseOrder;
import com.positivity.accounting.internal.repository.ExtOrderPurchaseOrderRepository;
import com.positivity.domainevents.ReplicaVersionGuard;
import com.positivity.domainevents.order.PurchaseOrderUpdatedV1;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The replica of pos-order's purchase orders (#2515; ADR-0044 R3), read for the payment terms of an estimated vendor
 * bill due date. Written only by {@link OrderEventsListener}, in the handler transaction that marks the fact
 * processed. Version-guarded on the envelope's aggregate version ({@link ReplicaVersionGuard}: equal versions apply),
 * so a redelivered or reordered older fact never overwrites newer terms.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PurchaseOrderReplica {

    private final ExtOrderPurchaseOrderRepository orders;

    /** Upserts the order unless {@code aggregateVersion} is older than the stored one; joins the caller's transaction. */
    @Transactional
    public void updated(@NonNull PurchaseOrderUpdatedV1 fact, long aggregateVersion) {
        ExtOrderPurchaseOrder row = orders.findById(fact.purchaseOrderId()).orElse(null);
        if (row != null && ReplicaVersionGuard.isStale(row.getAggregateVersion(), aggregateVersion)) {
            log.debug(
                    "Skipping stale purchaseorder.updated purchaseOrderId={} version={}",
                    fact.purchaseOrderId(),
                    aggregateVersion);
            return;
        }
        if (row == null) {
            row = new ExtOrderPurchaseOrder();
            row.setPurchaseOrderId(fact.purchaseOrderId());
        }
        row.setPoNumber(fact.poNumber());
        row.setVendorId(fact.vendorId());
        row.setPaymentTermsId(fact.paymentTermsId());
        row.setAggregateVersion(aggregateVersion);
        orders.save(row);
    }
}
