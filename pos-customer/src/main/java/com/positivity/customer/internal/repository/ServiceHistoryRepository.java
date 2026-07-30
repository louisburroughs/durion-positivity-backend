package com.positivity.customer.internal.repository;

import com.positivity.customer.internal.entity.ServiceHistory;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ServiceHistoryRepository extends JpaRepository<ServiceHistory, UUID> {

    /**
     * Idempotency backstop for the event-fed path (FI-3, #1133), independent of
     * processed_events.
     */
    boolean existsBySourceEventId(String sourceEventId);

    /**
     * Most recent completion per party for the given candidates — the single batch
     * query the
     * segment resolver uses to derive last-service age and service-due (FI-3,
     * #1133).
     */
    @Query("select sh.partyId as partyId, max(sh.completedAt) as lastCompletedAt"
            + " from ServiceHistory sh where sh.partyId in :partyIds group by sh.partyId")
    @NonNull
    List<PartyLastServiceView> findLastServiceByParty(@Param("partyIds") @NonNull Collection<UUID> partyIds);

    /** Projection: a party and its most recent service-completion timestamp. */
    interface PartyLastServiceView {
        UUID getPartyId();

        Instant getLastCompletedAt();
    }

    /**
     * Service-due candidates for the reminder job (Story #1153): the most recent completion
     * per (party, vehicle) that is older than {@code cutoff} and does not yet have a follow-up
     * task keyed {@code sourcePrefix + serviceHistoryId}. The not-exists runs here rather than
     * in Java so already-reminded rows never occupy batch slots and starve newer candidates.
     */
    @Query("""
            select sh from ServiceHistory sh
            where sh.completedAt < :cutoff
              and sh.completedAt = (
                  select max(sh2.completedAt) from ServiceHistory sh2
                  where sh2.partyId = sh.partyId
                    and ((sh2.vehicleId is null and sh.vehicleId is null) or sh2.vehicleId = sh.vehicleId))
              and not exists (
                  select t.taskId from FollowUpTask t
                  where t.sourceEventId = concat(:sourcePrefix, cast(sh.serviceHistoryId as string)))
            order by sh.completedAt asc, sh.serviceHistoryId asc
            """)
    @NonNull
    List<ServiceHistory> findServiceDueCandidates(
            @Param("cutoff") @NonNull Instant cutoff,
            @Param("sourcePrefix") @NonNull String sourcePrefix,
            @NonNull Pageable pageable);
}
