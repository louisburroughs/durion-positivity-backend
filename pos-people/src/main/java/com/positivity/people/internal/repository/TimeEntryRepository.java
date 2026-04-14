package com.positivity.people.internal.repository;

import com.positivity.people.internal.entity.TimeEntry;
import com.positivity.people.internal.enums.TimeEntryStatus;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
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
              AND (:includeAllTechnicians = true OR t.person.id IN :technicianIds)
            """)
    List<TimeEntry> findAttendanceOverlappingWindow(@Param("windowStartInclusive") Instant windowStartInclusive,
            @Param("windowEndExclusive") Instant windowEndExclusive, @Param("locationId") UUID locationId,
            @Param("technicianIds") List<UUID> technicianIds,
            @Param("includeAllTechnicians") boolean includeAllTechnicians);

    @NonNull
    @Query("""
            SELECT t
            FROM TimeEntry t
            LEFT JOIN FETCH t.person p
            WHERE t.status = :status
              AND t.attendanceStartAt >= :windowStartInclusive
              AND t.attendanceStartAt < :windowEndExclusive
              AND t.locationId IN :locationIds
            """)
    List<TimeEntry> findApprovedForExport(@Param("status") TimeEntryStatus status,
            @Param("windowStartInclusive") Instant windowStartInclusive,
            @Param("windowEndExclusive") Instant windowEndExclusive, @Param("locationIds") List<UUID> locationIds);

}
