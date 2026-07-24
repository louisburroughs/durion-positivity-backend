package com.positivity.invoice.internal.repository;

import com.positivity.invoice.internal.entity.DepositCredit;
import com.positivity.invoice.internal.enums.DepositCreditStatus;
import com.positivity.invoice.internal.enums.DepositSourceType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DepositCreditRepository extends JpaRepository<DepositCredit, UUID> {

    /** Deposit-take idempotency anchor: one credit per taking order. */
    Optional<DepositCredit> findByOrderId(UUID orderId);

    /**
     * Credits still drawable against a source document (AVAILABLE / PARTIALLY_APPLIED), oldest
     * first — the FIFO order in which they are applied to a settlement invoice.
     */
    List<DepositCredit> findBySourceTypeAndSourceIdAndStatusInOrderByCreatedAtAsc(
            DepositSourceType sourceType, UUID sourceId, List<DepositCreditStatus> statuses);

    List<DepositCredit> findBySourceTypeAndSourceId(DepositSourceType sourceType, UUID sourceId);
}
