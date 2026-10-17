package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.APPaymentAllocationRequest;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Idempotency records of the allocate-a-cash-payment command (S17, #2513). */
public interface APPaymentAllocationRequestRepository extends JpaRepository<APPaymentAllocationRequest, UUID> {

    /** The earlier request under the caller's {@code requestId}, within the tenant (RLS). */
    Optional<APPaymentAllocationRequest> findByRequestId(UUID requestId);
}
