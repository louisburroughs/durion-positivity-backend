package com.positivity.order.internal.repository;

import com.positivity.order.internal.entity.ReturnOrder;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReturnOrderRepository extends JpaRepository<ReturnOrder, UUID> {

    Optional<ReturnOrder> findByCreationIdempotencyKey(String creationIdempotencyKey);

    List<ReturnOrder> findByOriginalOrderIdOrderByCreatedAtDesc(UUID originalOrderId);
}
