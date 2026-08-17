package com.positivity.workorder.internal.repository;

import com.positivity.workorder.internal.entity.WorkorderFleetAuthorization;
import com.positivity.workorder.internal.enums.FleetAuthorizationStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Fleet payment authorizations (#1346). */
@Repository
public interface WorkorderFleetAuthorizationRepository extends JpaRepository<WorkorderFleetAuthorization, UUID> {

    /**
     * The authorization for a workorder, if one was ever opened.
     *
     * <p>One per workorder by unique index. That is what makes a re-request an update rather than a
     * second opinion: two rows would let a refusal and a grant coexist with nothing to say which the
     * start gate should honour.
     */
    @NonNull
    Optional<WorkorderFleetAuthorization> findByWorkorderId(@NonNull UUID workorderId);

    /** Authorizations awaiting a person, for an advisor's queue. */
    @NonNull
    List<WorkorderFleetAuthorization> findByStatusInOrderByUpdatedAtDesc(
            @NonNull Collection<FleetAuthorizationStatus> statuses, @NonNull Limit limit);

    /**
     * Blocked authorizations whose held resources are now due for release.
     *
     * <p>Measured from {@code firstBlockedAt} — when somebody first tried to start the job — rather
     * than from the request. A job nobody has tried to start is not holding a bay up, and releasing
     * its assignment would cost capacity to solve a problem that does not exist yet.
     */
    @Query("""
            select a from WorkorderFleetAuthorization a
            where a.resourcesReleasedAt is null
              and a.firstBlockedAt is not null
              and a.firstBlockedAt < :releaseDueBefore
              and a.status in :blockingStatuses
            order by a.firstBlockedAt asc
            """)
    @NonNull
    List<WorkorderFleetAuthorization> findDueForResourceRelease(
            @Param("releaseDueBefore") @NonNull Instant releaseDueBefore,
            @Param("blockingStatuses") @NonNull Collection<FleetAuthorizationStatus> blockingStatuses,
            @NonNull Limit limit);
}
