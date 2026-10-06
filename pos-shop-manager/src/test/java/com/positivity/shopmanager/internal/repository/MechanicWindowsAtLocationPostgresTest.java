package com.positivity.shopmanager.internal.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.positivity.shopmanager.PostgresSliceTestBase;
import com.positivity.shopmanager.internal.entity.Appointment;
import com.positivity.shopmanager.internal.entity.Assignment;
import com.positivity.shopmanager.internal.entity.AssignmentMechanic;
import com.positivity.shopmanager.internal.entity.Mechanic;
import com.positivity.shopmanager.internal.enums.AppointmentStatus;
import com.positivity.shopmanager.internal.enums.AssignmentStatusEnum;
import com.positivity.shopmanager.internal.enums.MechanicRoleEnum;
import com.positivity.shopmanager.internal.enums.MechanicStatus;
import com.positivity.shopmanager.internal.repository.AssignmentMechanicRepository.MechanicAppointmentWindow;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link AssignmentMechanicRepository#findMechanicWindowsAtLocation} against the real PostgreSQL
 * schema (#2527): the capacity read's technician section rests on this one statement, and only
 * PostgreSQL can show that the join path and the enum comparisons parse and filter as intended.
 */
@DisplayName("Mechanic appointment windows at a location on PostgreSQL (#2527)")
class MechanicWindowsAtLocationPostgresTest extends PostgresSliceTestBase {

    private static final Instant RANGE_START = Instant.parse("2026-10-05T00:00:00Z");
    private static final Instant RANGE_END = Instant.parse("2026-10-06T00:00:00Z");
    private static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");

    @Autowired
    private EntityManager em;

    @Autowired
    private AssignmentMechanicRepository assignmentMechanics;

    @Test
    @DisplayName("returns live assignments at the location overlapping the range, and nothing else")
    void returnsLiveAssignmentsOverlappingTheRange() {
        UUID locationId = UUID.randomUUID();
        UUID personId = UUID.randomUUID();
        Mechanic mechanic = mechanic(personId);
        Appointment live =
                appointment(locationId, "2026-10-05T10:00:00Z", "2026-10-05T12:00:00Z", AppointmentStatus.SCHEDULED);
        assign(mechanic, live, AssignmentStatusEnum.ASSIGNED);
        assign(
                mechanic,
                appointment(locationId, "2026-10-05T13:00:00Z", "2026-10-05T14:00:00Z", AppointmentStatus.CANCELLED),
                AssignmentStatusEnum.ASSIGNED);
        assign(
                mechanic,
                appointment(locationId, "2026-10-05T14:00:00Z", "2026-10-05T15:00:00Z", AppointmentStatus.SCHEDULED),
                AssignmentStatusEnum.CANCELLED);
        assign(
                mechanic,
                appointment(locationId, "2026-10-06T10:00:00Z", "2026-10-06T11:00:00Z", AppointmentStatus.SCHEDULED),
                AssignmentStatusEnum.ASSIGNED);
        assign(
                mechanic,
                appointment(
                        UUID.randomUUID(), "2026-10-05T10:00:00Z", "2026-10-05T11:00:00Z", AppointmentStatus.SCHEDULED),
                AssignmentStatusEnum.ASSIGNED);
        em.flush();
        em.clear();

        assertThat(assignmentMechanics.findMechanicWindowsAtLocation(locationId, RANGE_START, RANGE_END))
                .extracting(
                        MechanicAppointmentWindow::getMechanicPersonId,
                        MechanicAppointmentWindow::getAppointmentId,
                        MechanicAppointmentWindow::getStartAt,
                        MechanicAppointmentWindow::getEndAt)
                .containsExactly(tuple(
                        personId,
                        live.getAppointmentId(),
                        Instant.parse("2026-10-05T10:00:00Z"),
                        Instant.parse("2026-10-05T12:00:00Z")));
    }

    private Mechanic mechanic(UUID personId) {
        Mechanic mechanic = Mechanic.builder()
                .personId(personId)
                .firstName("Test")
                .lastName("Technician")
                .status(MechanicStatus.ACTIVE)
                .createdAt(NOW)
                .updatedAt(NOW)
                .build();
        em.persist(mechanic);
        return mechanic;
    }

    private Appointment appointment(UUID locationId, String startAt, String endAt, AppointmentStatus status) {
        Appointment appointment = Appointment.builder()
                .status(status)
                .locationId(locationId)
                .crmCustomerId(UUID.randomUUID())
                .crmVehicleId(UUID.randomUUID())
                .startAt(Instant.parse(startAt))
                .endAt(Instant.parse(endAt))
                .build();
        em.persist(appointment);
        return appointment;
    }

    private void assign(Mechanic mechanic, Appointment appointment, AssignmentStatusEnum status) {
        Assignment assignment = Assignment.builder()
                .appointment(appointment)
                .status(status)
                .createdAt(NOW)
                .updatedAt(NOW)
                .build();
        em.persist(assignment);
        em.persist(AssignmentMechanic.builder()
                .assignment(assignment)
                .mechanic(mechanic)
                .role(MechanicRoleEnum.LEAD)
                .createdAt(NOW)
                .updatedAt(NOW)
                .build());
    }
}
