package com.positivity.shopmanager.internal.repository;

import com.positivity.shopmanager.internal.entity.RescheduleHistory;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository for reschedule history records.
 *
 * <p>
 * CAP-249 Story #11: used to count reschedules per appointment and store
 * the immutable audit trail.
 */
@Repository
public interface RescheduleHistoryRepository extends JpaRepository<RescheduleHistory, UUID> {

    /**
     * Returns all reschedule history records for the given appointment,
     * ordered most-recent first.
     */
    @NonNull
    List<RescheduleHistory> findByAppointmentIdOrderByRescheduledAtDesc(
            @NonNull UUID appointmentId);

    /** Returns the count of reschedules for the given appointment. */
    long countByAppointmentId(@NonNull UUID appointmentId);
}
