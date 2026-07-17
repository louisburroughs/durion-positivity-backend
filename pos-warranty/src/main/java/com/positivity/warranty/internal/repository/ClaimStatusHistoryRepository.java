package com.positivity.warranty.internal.repository;

import com.positivity.warranty.internal.entity.ClaimStatusHistory;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClaimStatusHistoryRepository extends JpaRepository<ClaimStatusHistory, UUID> {

    /**
     * History rows ordered by creation time with the UUIDv7 id as a deterministic tiebreaker:
     * two transitions written in one transaction (e.g. SUBMITTED→IN_REVIEW then
     * IN_REVIEW→APPROVED inside decide()) can share the same {@code created_at} microsecond,
     * and ids are time-ordered within the JVM, so insertion order is preserved.
     */
    @NonNull
    List<ClaimStatusHistory> findByClaimIdOrderByCreatedAtAscIdAsc(@NonNull UUID claimId);
}
