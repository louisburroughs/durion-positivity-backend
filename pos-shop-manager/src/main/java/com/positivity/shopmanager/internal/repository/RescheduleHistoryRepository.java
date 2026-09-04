package com.positivity.shopmanager.internal.repository;

import com.positivity.shopmanager.internal.entity.RescheduleHistory;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository for reschedule history records.
 *
 * <p>
 * CAP-249 Story #11: used to count reschedules per appointment and store
 * the immutable audit trail.
 *
 * <p>
 * {@link RescheduleHistory} maps its parent as the {@code appointment} association; its
 * {@code getAppointmentId()} is a convenience getter, not a mapped attribute. A query derived
 * from a {@code ...ByAppointmentId} method name is therefore rendered as
 * {@code history.appointmentId}, which Hibernate rejects at first use (#1685, the same defect
 * fixed for {@link MechanicSkillRepository} in #1679). Both methods below walk the association
 * explicitly instead of relying on name derivation.
 */
public interface RescheduleHistoryRepository extends JpaRepository<RescheduleHistory, UUID> {

    /**
     * Returns all reschedule history records for the given appointment,
     * ordered most-recent first.
     */
    @Query("""
            SELECT history
            FROM RescheduleHistory history
            WHERE history.appointment.appointmentId = :appointmentId
            ORDER BY history.rescheduledAt DESC
            """)
    @NonNull
    List<RescheduleHistory> findByAppointmentIdOrderByRescheduledAtDesc(
            @Param("appointmentId") @NonNull UUID appointmentId);

    /** Returns the count of reschedules for the given appointment. */
    @Query(
            "SELECT COUNT(history) FROM RescheduleHistory history WHERE history.appointment.appointmentId = :appointmentId")
    long countByAppointmentId(@Param("appointmentId") @NonNull UUID appointmentId);
}
