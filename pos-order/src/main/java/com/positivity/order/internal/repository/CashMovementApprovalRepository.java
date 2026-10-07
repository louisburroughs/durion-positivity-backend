package com.positivity.order.internal.repository;

import com.positivity.order.internal.entity.CashMovementApproval;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Manager approvals of drawer cash movements, found by the hash of the token presented. */
public interface CashMovementApprovalRepository extends JpaRepository<CashMovementApproval, UUID> {

    Optional<CashMovementApproval> findByTokenHash(String tokenHash);

    /** Marks an approval EXPIRED once a use found it past its expiry (CAP:550 S16). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update CashMovementApproval a set a.status = com.positivity.order.internal.entity"
            + ".CashMovementApprovalStatus.EXPIRED where a.approvalId = :approvalId and a.status = com.positivity"
            + ".order.internal.entity.CashMovementApprovalStatus.ISSUED")
    int markExpired(@Param("approvalId") UUID approvalId);
}
