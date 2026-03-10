package com.positivity.workorder.internal.repository;

import com.positivity.workorder.internal.entity.WorkorderLaborEntry;
import com.positivity.workorder.internal.enums.WorkorderStatus;

import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Repository for WorkorderLaborEntry entities.
 * 
 * <p>
 * Implements CAP-005 Story #159 - Record Labor Performed
 */
@Repository
public interface WorkorderLaborEntryRepository extends JpaRepository<WorkorderLaborEntry, UUID> {

        /**
         * Find all labor entries for a workorder, ordered by start time descending
         * (newest first).
         * 
         * @param workorderId the workorder ID
         * @return list of labor entries
         */
        @NonNull
        List<WorkorderLaborEntry> findByWorkorder_IdOrderByStartTimeDesc(@NonNull UUID workorderId);

        /**
         * Find all labor entries for a specific service, ordered by start time
         * descending.
         * 
         * @param workorderServiceId the service ID
         * @return list of labor entries
         */
        @NonNull
        List<WorkorderLaborEntry> findByWorkorderServiceIdOrderByStartTimeDesc(@NonNull UUID workorderServiceId);

        /**
         * Find active (not stopped) labor entry for a specific service.
         * 
         * @param workorderServiceId the service ID
         * @return optional active labor entry
         */
        @NonNull
        Optional<WorkorderLaborEntry> findByWorkorderServiceIdAndEndTimeIsNull(@NonNull UUID workorderServiceId);

        @NonNull
        List<WorkorderLaborEntry> findByTechnicianIdAndEndTimeIsNullOrderByStartTimeDesc(@NonNull UUID technicianId);

        @NonNull
        List<WorkorderLaborEntry> findByEndTimeIsNotNullAndEndTimeBetween(
                        @NonNull LocalDateTime startInclusive,
                        @NonNull LocalDateTime endExclusive);

        @Query("""
                        SELECT e
                        FROM WorkorderLaborEntry e
                        JOIN e.workorder w
                        WHERE e.endTime IS NOT NULL
                          AND e.endTime BETWEEN :startInclusive AND :endExclusive
                          AND w.status = :finalizedStatus
                          AND (w.isReopened IS NULL OR w.isReopened = false)
                        """)
        @NonNull
        List<WorkorderLaborEntry> findFinalizedByEndTimeBetween(
                        @NonNull @Param("startInclusive") LocalDateTime startInclusive,
                        @NonNull @Param("endExclusive") LocalDateTime endExclusive,
                        @NonNull @Param("finalizedStatus") WorkorderStatus finalizedStatus);
}
