package com.positivity.mcp.internal.repository;

import com.positivity.mcp.internal.entity.NltiWritePlan;
import com.positivity.mcp.internal.enums.NltiRequestStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NltiWritePlanRepository extends JpaRepository<NltiWritePlan, UUID> {

    Optional<NltiWritePlan> findByRequestId(UUID requestId);

    Optional<NltiWritePlan> findByIdempotencyKey(String idempotencyKey);

    /** All plans for a session in the given status; single-pending is enforced in the service. */
    List<NltiWritePlan> findBySessionIdAndStatus(UUID sessionId, NltiRequestStatus status);
}
