package com.positivity.shopmanager.internal.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.shopmanager.internal.entity.RescheduleHistory;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RescheduleHistoryRepositoryTest {

    private static final UUID APPOINTMENT_ID = UUID.fromString("01960011-0000-7000-8000-000000000031");
    private static final UUID OTHER_APPOINTMENT_ID = UUID.fromString("01960011-0000-7000-8000-000000000032");
    private static final UUID EMPTY_APPOINTMENT_ID = UUID.fromString("01960011-0000-7000-8000-000000000033");

    private static final UUID OLDEST_RESCHEDULE_ID = UUID.fromString("01960011-0000-7000-8000-000000000041");
    private static final UUID MIDDLE_RESCHEDULE_ID = UUID.fromString("01960011-0000-7000-8000-000000000042");
    private static final UUID NEWEST_RESCHEDULE_ID = UUID.fromString("01960011-0000-7000-8000-000000000043");
    private static final UUID OTHER_RESCHEDULE_ID = UUID.fromString("01960011-0000-7000-8000-000000000044");

    @Autowired
    private RescheduleHistoryRepository rescheduleHistoryRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        insertAppointment(APPOINTMENT_ID);
        insertAppointment(OTHER_APPOINTMENT_ID);
        insertAppointment(EMPTY_APPOINTMENT_ID);

        insertRescheduleHistory(OLDEST_RESCHEDULE_ID, APPOINTMENT_ID, Instant.parse("2026-01-01T09:00:00Z"));
        insertRescheduleHistory(MIDDLE_RESCHEDULE_ID, APPOINTMENT_ID, Instant.parse("2026-01-02T09:00:00Z"));
        insertRescheduleHistory(NEWEST_RESCHEDULE_ID, APPOINTMENT_ID, Instant.parse("2026-01-03T09:00:00Z"));
        insertRescheduleHistory(OTHER_RESCHEDULE_ID, OTHER_APPOINTMENT_ID, Instant.parse("2026-01-04T09:00:00Z"));
    }

    /**
     * #1685: {@link RescheduleHistory} maps its parent as the {@code appointment} association, so a
     * lookup derived from an {@code ...ByAppointmentId} method name must walk that association
     * rather than a non-existent {@code appointmentId} attribute (the same defect fixed for
     * {@code MechanicSkillRepository} in #1679).
     */
    @Test
    void findByAppointmentIdOrdersMostRecentFirstAndExcludesOtherAppointments() {
        List<RescheduleHistory> history =
                rescheduleHistoryRepository.findByAppointmentIdOrderByRescheduledAtDesc(APPOINTMENT_ID);

        assertThat(history)
                .extracting(RescheduleHistory::getRescheduleId)
                .containsExactly(NEWEST_RESCHEDULE_ID, MIDDLE_RESCHEDULE_ID, OLDEST_RESCHEDULE_ID);
    }

    @Test
    void countByAppointmentIdCountsOnlyThatAppointmentsHistory() {
        assertThat(rescheduleHistoryRepository.countByAppointmentId(APPOINTMENT_ID))
                .isEqualTo(3);
        assertThat(rescheduleHistoryRepository.countByAppointmentId(OTHER_APPOINTMENT_ID))
                .isEqualTo(1);
    }

    @Test
    void countByAppointmentIdReturnsZeroWhenNoHistoryExists() {
        assertThat(rescheduleHistoryRepository.countByAppointmentId(EMPTY_APPOINTMENT_ID))
                .isZero();
    }

    /**
     * DECISION-SHOPMGMT-004: a shop-caused (exempt) reschedule is recorded with {@code
     * counts_against_allowance = false} and must not count toward the 2-free-reschedules
     * threshold, even though it still counts in the plain {@link
     * RescheduleHistoryRepository#countByAppointmentId} total.
     */
    @Test
    void countByAppointmentIdAndCountsAgainstAllowanceTrueExcludesExemptReschedules() {
        UUID appointmentId = UUID.fromString("01960011-0000-7000-8000-000000000035");
        insertAppointment(appointmentId);
        insertRescheduleHistory(
                UUID.fromString("01960011-0000-7000-8000-000000000045"),
                appointmentId,
                Instant.parse("2026-02-01T09:00:00Z"),
                true);
        insertRescheduleHistory(
                UUID.fromString("01960011-0000-7000-8000-000000000046"),
                appointmentId,
                Instant.parse("2026-02-02T09:00:00Z"),
                true);
        insertRescheduleHistory(
                UUID.fromString("01960011-0000-7000-8000-000000000047"),
                appointmentId,
                Instant.parse("2026-02-03T09:00:00Z"),
                false);

        assertThat(rescheduleHistoryRepository.countByAppointmentId(appointmentId))
                .isEqualTo(3);
        assertThat(rescheduleHistoryRepository.countByAppointmentIdAndCountsAgainstAllowanceTrue(appointmentId))
                .isEqualTo(2);
    }

    private void insertAppointment(UUID appointmentId) {
        jdbcTemplate.update("""
                INSERT INTO appointment
                    (tenant_id, appointment_id, status, location_id, crm_customer_id, crm_vehicle_id,
                     start_at, end_at, reopen_flag, created_at, updated_at)
                VALUES ('01900000-0000-7000-8000-000000000001', ?, 'SCHEDULED', ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, false,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, appointmentId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    }

    private void insertRescheduleHistory(UUID rescheduleId, UUID appointmentId, Instant rescheduledAt) {
        insertRescheduleHistory(rescheduleId, appointmentId, rescheduledAt, true);
    }

    private void insertRescheduleHistory(
            UUID rescheduleId, UUID appointmentId, Instant rescheduledAt, boolean countsAgainstAllowance) {
        jdbcTemplate.update("""
                INSERT INTO reschedule_history
                    (tenant_id, reschedule_id, appointment_id, previous_start_at, previous_end_at,
                     new_start_at, new_end_at, reschedule_reason, rescheduled_by, rescheduled_at,
                     notify_customer, counts_against_allowance, created_at)
                VALUES ('01900000-0000-7000-8000-000000000001', ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                        'CUSTOMER_REQUEST', 'test-user', ?, false, ?, CURRENT_TIMESTAMP)
                """, rescheduleId, appointmentId, rescheduledAt, countsAgainstAllowance);
    }
}
