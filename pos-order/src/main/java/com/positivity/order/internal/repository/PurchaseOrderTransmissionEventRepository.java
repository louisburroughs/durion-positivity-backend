package com.positivity.order.internal.repository;

import com.positivity.order.internal.entity.PurchaseOrderTransmissionEvent;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PurchaseOrderTransmissionEventRepository extends JpaRepository<PurchaseOrderTransmissionEvent, UUID> {

    /** The order's timeline, in the sequence the vendor observed rather than the one we heard. */
    List<PurchaseOrderTransmissionEvent> findByPurchaseOrderIdOrderByObservedAtAsc(UUID purchaseOrderId);
}
