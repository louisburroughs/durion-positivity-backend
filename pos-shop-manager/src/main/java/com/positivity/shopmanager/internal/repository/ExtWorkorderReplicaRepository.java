package com.positivity.shopmanager.internal.repository;

import com.positivity.shopmanager.internal.entity.ExtWorkorderReplica;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExtWorkorderReplicaRepository extends JpaRepository<ExtWorkorderReplica, UUID> {

    /**
     * Every open workorder at one location, already in the dashboard's presentation order
     * (#1658 AC3/AC4) — one query, page-limited so the 200-row cap never materializes a larger
     * result set.
     *
     * <p>The ordering is expressed in SQL rather than sorted in memory on purpose: the cap has to
     * be applied <em>after</em> the sort or the 200 rows returned are an arbitrary subset instead
     * of the first 200 the operator should see. The tiers are, in order:
     *
     * <ol>
     *   <li>unassigned first — a workorder nobody has put on a unit is the one needing a decision;
     *   <li>then by status band: blocked (waiting on parts or approval) → queued (not started) →
     *       active (being worked) → ready (finished, vehicle not collected);
     *   <li>then {@code promisedAt} ascending with nulls last — soonest promise first;
     *   <li>then {@code workorderNumber}, so the order is total and the page is stable.
     * </ol>
     *
     * <p>Openness is expressed as "not terminal", mirroring how pos-workorder itself derives
     * {@code getOpenStatuses()} — see
     * {@link com.positivity.shopmanager.internal.enums.WorkorderStatusMirror}. Status is compared
     * as the owner's string: this module replicates the name and never re-declares its enum
     * (ADR-0044 R6, one owner per fact).
     */
    @Query("""
            select w from ExtWorkorderReplica w
            where w.locationId = :locationId
              and (w.status is null or w.status not in :terminalStatuses)
            order by
              case when w.resourceId is null then 0 else 1 end,
              case w.status
                when 'AWAITING_PARTS' then 0
                when 'AWAITING_APPROVAL' then 0
                when 'DRAFT' then 1
                when 'APPROVED' then 1
                when 'ASSIGNED' then 1
                when 'WORK_IN_PROGRESS' then 2
                when 'READY_FOR_PICKUP' then 3
                else 4
              end,
              case when w.promisedAt is null then 1 else 0 end,
              w.promisedAt asc,
              w.workorderNumber asc
            """)
    @NonNull
    List<ExtWorkorderReplica> findOpenAtLocation(
            @Param("locationId") @NonNull UUID locationId,
            @Param("terminalStatuses") @NonNull Collection<String> terminalStatuses,
            @NonNull Pageable pageable);

    /**
     * Open workorders at one location that hold one of the given units — the unit roster's
     * occupancy source (#1658 AC1/AC6/AC7), in one query for the whole roster.
     *
     * <p>It is deliberately NOT derived from {@link #findOpenAtLocation}: that result is cut at the
     * 200-row cap, so at a busy location a unit whose workorder fell past the cap would read as
     * free. An empty bay is the most actionable thing on the board, so reporting one that is
     * actually occupied is the worst error this endpoint can make.
     *
     * <p>The result is bounded by the number of units at the location rather than by the number of
     * workorders, so it needs no page limit: more than a handful of open jobs on one bay is an
     * operational conflict, not a scale problem.
     */
    @Query("""
            select w from ExtWorkorderReplica w
            where w.locationId = :locationId
              and w.resourceId in :resourceIds
              and (w.status is null or w.status not in :terminalStatuses)
            """)
    @NonNull
    List<ExtWorkorderReplica> findOpenHoldingResources(
            @Param("locationId") @NonNull UUID locationId,
            @Param("terminalStatuses") @NonNull Collection<String> terminalStatuses,
            @Param("resourceIds") @NonNull Collection<UUID> resourceIds);
}
