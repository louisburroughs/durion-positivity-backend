package com.positivity.order.internal.repository;

import com.positivity.order.internal.entity.CashMovementApproval;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Manager approvals of drawer cash movements, found by the hash of the token presented. */
public interface CashMovementApprovalRepository extends JpaRepository<CashMovementApproval, UUID> {

    Optional<CashMovementApproval> findByTokenHash(String tokenHash);
}
