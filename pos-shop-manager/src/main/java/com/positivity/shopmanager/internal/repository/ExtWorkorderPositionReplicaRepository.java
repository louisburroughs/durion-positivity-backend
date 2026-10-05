package com.positivity.shopmanager.internal.repository;

import com.positivity.shopmanager.internal.entity.ExtWorkorderPositionReplica;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Replicated service-position history of workorders (#2530), replace-set by the consumer. */
public interface ExtWorkorderPositionReplicaRepository extends JpaRepository<ExtWorkorderPositionReplica, UUID> {

    @NonNull
    List<ExtWorkorderPositionReplica> findAllByWorkorderIdOrderByAssignedAtAsc(@NonNull UUID workorderId);

    void deleteAllByWorkorderId(@NonNull UUID workorderId);

    /**
     * Every bay a workorder held at the location for any part of {@code [windowStart, windowEnd)}:
     * taken before the window ends and not yet released, or released after the window began. One
     * query for the whole capacity window (#2023 AC7); bay membership is settled in memory against
     * the active bay roster, as the appointment query does.
     */
    @Query("SELECT p FROM ExtWorkorderPositionReplica p WHERE p.locationId = :locationId"
            + " AND p.resourceType = 'BAY'"
            + " AND p.assignedAt < :windowEnd"
            + " AND (p.releasedAt IS NULL OR p.releasedAt > :windowStart)")
    @NonNull
    List<ExtWorkorderPositionReplica> findBaysHeldAtLocation(
            @Param("locationId") @NonNull UUID locationId,
            @Param("windowStart") @NonNull Instant windowStart,
            @Param("windowEnd") @NonNull Instant windowEnd);
}
