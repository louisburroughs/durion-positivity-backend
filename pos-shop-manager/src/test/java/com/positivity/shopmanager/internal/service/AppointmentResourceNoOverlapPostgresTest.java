package com.positivity.shopmanager.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.positivity.shopmanager.PostgresSliceTestBase;
import com.positivity.shopmanager.internal.entity.Appointment;
import com.positivity.shopmanager.internal.enums.AppointmentStatus;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@code appointment_resource_no_overlap} (V8, CAP-326, spec D17) against the real schema: what the
 * constraint refuses, what it lets through, and that {@link ResourceOverlapViolation} recognises
 * the refusal the driver actually raises. Each refusal aborts the test's transaction, so a test
 * holds at most one.
 *
 * <p>Requires Docker, like every slice on {@link PostgresSliceTestBase}.
 */
@DisplayName("appointment_resource_no_overlap on PostgreSQL (CAP-326)")
class AppointmentResourceNoOverlapPostgresTest extends PostgresSliceTestBase {

    private static final UUID LOCATION = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final String BAY_1 = "00000000-0000-0000-0000-0000000000b1";
    private static final String BAY_2 = "00000000-0000-0000-0000-0000000000b2";
    private static final Instant T09 = Instant.parse("2026-06-16T14:00:00Z");
    private static final Instant T10 = Instant.parse("2026-06-16T15:00:00Z");
    private static final Instant T11 = Instant.parse("2026-06-16T16:00:00Z");
    private static final Instant T12 = Instant.parse("2026-06-16T17:00:00Z");

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("two held bookings of one bay with overlapping windows: the second is refused with 23P01")
    void overlappingHeldBookingsAreRefused() {
        persist(BAY_1, T09, T11, AppointmentStatus.SCHEDULED);
        entityManager.flush();

        persist(BAY_1, T10, T12, AppointmentStatus.CHECKED_IN);
        Throwable refused = catchThrowable(entityManager::flush);

        assertThat(refused).isNotNull();
        assertThat(ResourceOverlapViolation.matches(refused))
                .as("the refusal is recognised by SQLSTATE 23P01 + constraint name: %s", refused)
                .isTrue();
    }

    @Test
    @DisplayName("back-to-back windows on one bay do not conflict — the range is half-open")
    void backToBackWindowsDoNotConflict() {
        persist(BAY_1, T09, T10, AppointmentStatus.SCHEDULED);
        persist(BAY_1, T10, T11, AppointmentStatus.SCHEDULED);
        entityManager.flush();
    }

    @Test
    @DisplayName("a cancelled appointment no longer holds its slot")
    void cancelledDoesNotHoldTheSlot() {
        persist(BAY_1, T09, T11, AppointmentStatus.CANCELLED);
        persist(BAY_1, T10, T12, AppointmentStatus.SCHEDULED);
        entityManager.flush();
    }

    @Test
    @DisplayName("the same window on two bays is two bookings")
    void differentBaysDoNotConflict() {
        persist(BAY_1, T09, T11, AppointmentStatus.SCHEDULED);
        persist(BAY_2, T09, T11, AppointmentStatus.SCHEDULED);
        entityManager.flush();
    }

    @Test
    @DisplayName("unassigned bookings are not bay bookings and never collide")
    void unassignedNeverCollides() {
        persist("UNASSIGNED", T09, T11, AppointmentStatus.SCHEDULED);
        persist("UNASSIGNED", T09, T11, AppointmentStatus.SCHEDULED);
        persist(null, T09, T11, AppointmentStatus.SCHEDULED);
        persist(null, T09, T11, AppointmentStatus.SCHEDULED);
        entityManager.flush();
    }

    @Test
    @DisplayName("rescheduling into an occupied window is refused the same way — the UPDATE is checked too")
    void reschedulingIntoAnOccupiedWindowIsRefused() {
        persist(BAY_1, T09, T10, AppointmentStatus.SCHEDULED);
        Appointment later = persist(BAY_1, T11, T12, AppointmentStatus.SCHEDULED);
        entityManager.flush();

        later.setStartAt(T09.plusSeconds(1800));
        later.setEndAt(T10.plusSeconds(1800));
        Throwable refused = catchThrowable(entityManager::flush);

        assertThat(refused).isNotNull();
        assertThat(ResourceOverlapViolation.matches(refused)).isTrue();
    }

    private Appointment persist(String resourceId, Instant startAt, Instant endAt, AppointmentStatus status) {
        Appointment appointment = Appointment.builder()
                .status(status)
                .locationId(LOCATION)
                .resourceId(resourceId)
                .crmCustomerId(UUID.randomUUID())
                .crmVehicleId(UUID.randomUUID())
                .startAt(startAt)
                .endAt(endAt)
                .build();
        entityManager.persist(appointment);
        return appointment;
    }
}
