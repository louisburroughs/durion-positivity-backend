package com.positivity.order.internal.repository;

import com.positivity.order.internal.entity.PurchaseOrderTransmissionEvent;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PurchaseOrderTransmissionEventRepository extends JpaRepository<PurchaseOrderTransmissionEvent, UUID> {

    /** The order's timeline, in the sequence the vendor observed rather than the one we heard. */
    List<PurchaseOrderTransmissionEvent> findByPurchaseOrderIdOrderByObservedAtAsc(UUID purchaseOrderId);

    /**
     * One page of the order's timeline, in the sequence the vendor observed rather than the one we
     * heard. Ties on the vendor's clock break by receipt time and then by event id, so two
     * observations sharing an {@code observedAt} keep one stable order across reads and page
     * boundaries never wander.
     */
    @NonNull
    Page<PurchaseOrderTransmissionEvent> findByPurchaseOrderIdOrderByObservedAtAscRecordedAtAscEventIdAsc(
            @NonNull UUID purchaseOrderId, @NonNull Pageable pageable);
}
