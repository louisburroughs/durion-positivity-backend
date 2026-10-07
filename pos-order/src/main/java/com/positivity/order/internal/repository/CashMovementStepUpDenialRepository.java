package com.positivity.order.internal.repository;

import com.positivity.order.internal.entity.CashMovementStepUpDenial;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Refused manager approvals per drawer and manager sign-in name (CAP:550 S16, #2512). */
public interface CashMovementStepUpDenialRepository extends JpaRepository<CashMovementStepUpDenial, UUID> {

    long countBySessionIdAndApproverUsername(UUID sessionId, String approverUsername);
}
