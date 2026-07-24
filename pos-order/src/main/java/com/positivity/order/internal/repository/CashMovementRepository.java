package com.positivity.order.internal.repository;

import com.positivity.order.internal.entity.CashMovement;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CashMovementRepository extends JpaRepository<CashMovement, UUID> {

    List<CashMovement> findBySessionIdOrderByOccurredAtAsc(UUID sessionId);
}
