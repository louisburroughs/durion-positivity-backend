package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.GoodsReceiptEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GoodsReceiptRepository extends JpaRepository<GoodsReceiptEntity, UUID> {

    List<GoodsReceiptEntity> findByPurchaseOrderId(UUID poId);

    /** The receipt a session call recorded under this idempotency key, if it has run before (#2455). */
    @NonNull
    Optional<GoodsReceiptEntity> findByReceivingSessionIdAndIdempotencyScopeAndIdempotencyKey(
            @NonNull UUID receivingSessionId, @NonNull String idempotencyScope, @NonNull String idempotencyKey);
}
