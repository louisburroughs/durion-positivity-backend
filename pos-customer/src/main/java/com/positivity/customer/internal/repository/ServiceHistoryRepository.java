package com.positivity.customer.internal.repository;

import com.positivity.customer.internal.entity.ServiceHistory;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
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
}
