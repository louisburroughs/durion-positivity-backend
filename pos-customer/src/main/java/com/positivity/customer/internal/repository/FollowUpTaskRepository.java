package com.positivity.customer.internal.repository;

import com.positivity.customer.internal.entity.FollowUpTask;
import com.positivity.customer.internal.enums.FollowUpStatus;
import com.positivity.customer.internal.enums.FollowUpType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface FollowUpTaskRepository extends JpaRepository<FollowUpTask, UUID> {

    Page<FollowUpTask> findByPartyIdOrderByDueDateAscCreatedAtAsc(UUID partyId, Pageable pageable);

    Page<FollowUpTask> findByPartyIdAndStatusOrderByDueDateAscCreatedAtAsc(
            UUID partyId, FollowUpStatus status, Pageable pageable);

    /** Idempotency guard for the event-fed path (FI-3, #1133). */
    boolean existsBySourceEventId(String sourceEventId);

    /**
     * The CSR queue. Every filter is optional so one query backs "my open tasks", "everything
     * overdue", and "all declined-service chases" without three near-identical methods drifting
     * apart.
     */
    @Query("""
            SELECT t FROM FollowUpTask t
            WHERE (:status IS NULL OR t.status = :status)
              AND (:assignedTo IS NULL OR t.assignedTo = :assignedTo)
              AND (:type IS NULL OR t.type = :type)
            ORDER BY t.dueDate ASC NULLS LAST, t.createdAt ASC
            """)
    Page<FollowUpTask> findQueue(
            @Param("status") FollowUpStatus status,
            @Param("assignedTo") String assignedTo,
            @Param("type") FollowUpType type,
            Pageable pageable);

    /**
     * Most recent declined-service follow-up per party for the given candidates — the batch query
     * the segment resolver uses to derive the "declined service in last N days" predicate (FI-3,
     * #1133). A declined follow-up is not removed when closed, so it remains the record that a
     * decline occurred regardless of whether it was later worked.
     */
    @Query("select t.partyId as partyId, max(t.createdAt) as lastDeclinedAt from FollowUpTask t"
            + " where t.type = com.positivity.customer.internal.enums.FollowUpType.DECLINED_SERVICE_FOLLOWUP"
            + " and t.partyId in :partyIds group by t.partyId")
    List<PartyLastDecline> findLastDeclinedByParty(@Param("partyIds") Collection<UUID> partyIds);

    /** Projection: a party and the timestamp of its most recent declined-service follow-up. */
    interface PartyLastDecline {
        UUID getPartyId();

        Instant getLastDeclinedAt();
    }
}
