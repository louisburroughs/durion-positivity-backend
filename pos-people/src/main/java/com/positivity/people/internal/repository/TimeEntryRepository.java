package com.positivity.people.internal.repository;

import com.positivity.people.internal.entity.TimeEntry;
import com.positivity.people.internal.enums.TimeEntryStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TimeEntryRepository extends JpaRepository<TimeEntry, UUID> {

    List<TimeEntry> findByTimeEntryIdIn(List<UUID> ids);

    long countByStatus(TimeEntryStatus status);

    @NonNull
    @Query("""
                        SELECT t
                        FROM TimeEntry t
                        WHERE t.attendanceStartAt IS NOT NULL
                          AND t.attendanceStartAt < :windowEndExclusive
                          AND (t.attendanceEndAt IS NULL OR t.attendanceEndAt > :windowStartInclusive)
                          AND (:locationId IS NULL OR t.locationId = :locationId)
                          AND (:includeAllTechnicians = true OR t.personId IN :technicianIds)
                        """)
    List<TimeEntry> findAttendanceOverlappingWindow(
            @Param("windowStartInclusive") Instant windowStartInclusive,
            @Param("windowEndExclusive") Instant windowEndExclusive,
            @Param("locationId") UUID locationId,
            @Param("technicianIds") List<UUID> technicianIds,
            @Param("includeAllTechnicians") boolean includeAllTechnicians);

    /**
     * The approvals-queue finder (#1573): status, person and location are each optional and
     * applied only when supplied, so the same query serves "everything pending today", "one
     * person's week", and an unfiltered browse.
     *
     * <p>The day filter is a half-open window on {@code attendanceStartAt} rather than a
     * work-date column, because {@code time_entry} stores only the attendance instants; the
     * caller decides which zone's day the window covers. The window bounds are always bound to
     * a real instant — an absent filter widens them instead of nulling them — because a null
     * timestamp parameter has no type Postgres can infer inside a comparison.
     *
     * <p>An entry with no {@code attendanceStartAt} is invisible to a windowed query. That is
     * correct for this surface: an entry with no clock-in has no day to be approved for.
     *
     * <p>Ordered oldest submission first so a supervisor works the queue in the order employees
     * submitted, with {@code timeEntryId} breaking ties for a stable page boundary.
     */
    @NonNull
    @Query(value = """
                        SELECT t
                        FROM TimeEntry t
                        WHERE (:status IS NULL OR t.status = :status)
                          AND (:personId IS NULL OR t.personId = :personId)
                          AND (:locationId IS NULL OR t.locationId = :locationId)
                          AND t.attendanceStartAt >= :windowStartInclusive
                          AND t.attendanceStartAt < :windowEndExclusive
                        ORDER BY t.submittedAt ASC NULLS LAST, t.timeEntryId ASC
                        """, countQuery = """
                        SELECT COUNT(t)
                        FROM TimeEntry t
                        WHERE (:status IS NULL OR t.status = :status)
                          AND (:personId IS NULL OR t.personId = :personId)
                          AND (:locationId IS NULL OR t.locationId = :locationId)
                          AND t.attendanceStartAt >= :windowStartInclusive
                          AND t.attendanceStartAt < :windowEndExclusive
                        """)
    Page<TimeEntry> findForApprovalQueue(
            @Param("status") TimeEntryStatus status,
            @Param("personId") UUID personId,
            @Param("locationId") UUID locationId,
            @Param("windowStartInclusive") Instant windowStartInclusive,
            @Param("windowEndExclusive") Instant windowEndExclusive,
            Pageable pageable);

    @NonNull
    @Query("""
                        SELECT t
                        FROM TimeEntry t
                        WHERE t.status = :status
                          AND t.attendanceStartAt >= :windowStartInclusive
                          AND t.attendanceStartAt < :windowEndExclusive
                          AND t.locationId IN :locationIds
                        """)
    List<TimeEntry> findApprovedForExport(
            @Param("status") TimeEntryStatus status,
            @Param("windowStartInclusive") Instant windowStartInclusive,
            @Param("windowEndExclusive") Instant windowEndExclusive,
            @Param("locationIds") List<UUID> locationIds);
}
