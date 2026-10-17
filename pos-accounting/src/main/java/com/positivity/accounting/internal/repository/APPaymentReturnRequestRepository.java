package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.APPaymentReturnRequest;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** The {@code record-return} replay table (#2642). */
public interface APPaymentReturnRequestRepository extends JpaRepository<APPaymentReturnRequest, UUID> {
    Optional<APPaymentReturnRequest> findByRequestId(UUID requestId);
}
