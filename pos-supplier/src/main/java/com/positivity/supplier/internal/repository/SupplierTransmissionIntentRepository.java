package com.positivity.supplier.internal.repository;

import com.positivity.supplier.internal.entity.SupplierTransmissionIntentEntity;
import com.positivity.supplier.internal.enums.TransmissionAttemptState;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** The transmission ledger and its dispatch queue (ADR-0052 §§1–2). */
public interface SupplierTransmissionIntentRepository extends JpaRepository<SupplierTransmissionIntentEntity, UUID> {

    /**
     * The intent holding a tuple's active claim, if any.
     *
     * <p>This is the duplicate-order lookup: a command whose tuple is already claimed is a repeat,
     * not a new order.
     */
    Optional<SupplierTransmissionIntentEntity> findByActiveIntentKey(String activeIntentKey);

    /** Lookup by the wire document id — how a vendor's answer finds its intent. */
    Optional<SupplierTransmissionIntentEntity> findByDocumentId(String documentId);

    /**
     * Dispatchable work, oldest first. UUIDv7 ids are time-ordered, so a vendor receives one
     * customer's orders in the order they were placed.
     */
    List<SupplierTransmissionIntentEntity> findTop50ByAttemptStateOrderByTransmissionIntentIdAsc(
            TransmissionAttemptState attemptState);

    /**
     * Intents left mid-flight by a crash (ADR-0052 §2).
     *
     * <p>Never re-dispatched: they go to status reconciliation, or straight to
     * {@code MANUAL_REVIEW} when the profile has no {@code ORDER_STATUS} binding.
     */
    List<SupplierTransmissionIntentEntity> findByAttemptState(TransmissionAttemptState attemptState);

    /**
     * Orders the status poller still has something to learn about, least-recently-asked first.
     *
     * <p>Ordered by when the vendor was last <em>asked</em> rather than when its answer last
     * changed: ordering by the latter would starve an order whose answer never changes, which is
     * precisely the order most likely to need a human eventually.
     */
    List<SupplierTransmissionIntentEntity> findTop100ByStatusPollingActiveTrueOrderByLastPolledAtAsc();

    /** The transmission history of one purchase order, newest intent first. */
    List<SupplierTransmissionIntentEntity> findByPurchaseOrderIdOrderByTransmissionIntentIdDesc(UUID purchaseOrderId);
}
