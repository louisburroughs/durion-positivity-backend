package com.positivity.order.internal.repository;

import com.positivity.order.internal.entity.CashMovement;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CashMovementRepository extends JpaRepository<CashMovement, UUID> {

    List<CashMovement> findBySessionIdOrderByOccurredAtAsc(UUID sessionId);

    /** The movement a register request already recorded, for an idempotent replay (#2512, §8.2). */
    Optional<CashMovement> findByRequestId(UUID requestId);
}
