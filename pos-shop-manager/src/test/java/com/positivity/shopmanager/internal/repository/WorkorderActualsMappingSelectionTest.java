package com.positivity.shopmanager.internal.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.shopmanager.internal.entity.Appointment;
import com.positivity.shopmanager.internal.entity.ExtWorkorderReplica;
import com.positivity.shopmanager.internal.entity.WorkOrderAppointmentMapping;
import com.positivity.shopmanager.internal.enums.AppointmentStatus;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pins the mapping-selection behaviour of {@link
 * WorkOrderAppointmentMappingRepository#findActualsByAppointmentIds} when a reopened work order's
 * replica has not landed yet (issue #2089, option 1).
 *
 * <p>The behaviour used to be emergent — a side effect of the inner join against {@code
 * ExtWorkorderReplica} — and was therefore free to change under anyone who touched the query. It
 * is now a recorded decision on {@link WorkorderActuals#mostCurrent}, so it is asserted directly
 * rather than inferred from a capacity number: during replication lag the superseded run's actuals
 * are resolved, and once the newer replica arrives the newer mapping takes over.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("WorkOrderAppointmentMappingRepository - mapping selection under replication lag (#2089)")
class WorkorderActualsMappingSelectionTest {

    private static final UUID CUSTOMER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID VEHICLE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID LOCATION_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    // UUIDv7 ordering is what mostCurrent reads as "newest": SUPERSEDED is the first run, CURRENT
    // the reopened one. They are fixed rather than generated so the ordering under test is not
    // itself a coin flip.
    private static final UUID SUPERSEDED_WORKORDER_ID = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final UUID CURRENT_WORKORDER_ID = UUID.fromString("00000000-0000-7000-8000-000000000002");

    private static final Instant PLANNED_START = Instant.parse("2026-10-05T10:00:00Z");
    private static final Instant PLANNED_END = Instant.parse("2026-10-05T12:00:00Z");
    private static final Instant FIRST_RUN_STARTED = Instant.parse("2026-10-05T10:00:00Z");
    private static final Instant FIRST_RUN_COMPLETED = Instant.parse("2026-10-05T10:30:00Z");
    private static final Instant SECOND_RUN_STARTED = Instant.parse("2026-10-05T14:00:00Z");

    @Autowired
    private WorkOrderAppointmentMappingRepository repository;

    @Autowired
    private EntityManager em;

    @Test
    @DisplayName("#2089 AC2 - a reopened work order whose replica has not landed resolves the "
            + "superseded run's actuals, not no actuals at all")
    void unreplicatedNewerMappingFallsBackToTheNewestReplicatedMapping() {
        Appointment appointment = persistAppointment();
        persistMapping(SUPERSEDED_WORKORDER_ID, appointment);
        persistWorkorderReplica(SUPERSEDED_WORKORDER_ID, FIRST_RUN_STARTED, FIRST_RUN_COMPLETED);
        // The reopen: a second mapping row, no replica for it yet — workorder.events.v1 in flight.
        persistMapping(CURRENT_WORKORDER_ID, appointment);
        flushAndClear();

        List<WorkorderActuals> rows = repository.findActualsByAppointmentIds(List.of(appointment.getAppointmentId()));

        // The inner join drops the unreplicated mapping entirely: the reduction cannot see that a
        // newer mapping exists, and the appointment is indistinguishable from one whose only work
        // order is the first run.
        assertThat(rows)
                .as("only the replicated mapping is a candidate")
                .extracting(WorkorderActuals::workOrderId)
                .containsExactly(SUPERSEDED_WORKORDER_ID);

        Optional<WorkorderActuals> resolved = rows.stream().reduce(WorkorderActuals::mostCurrent);

        assertThat(resolved).isPresent();
        assertThat(resolved.get().workOrderId()).isEqualTo(SUPERSEDED_WORKORDER_ID);
        assertThat(resolved.get().workStartedAt()).isEqualTo(FIRST_RUN_STARTED);
        assertThat(resolved.get().completedAt()).isEqualTo(FIRST_RUN_COMPLETED);
    }

    @Test
    @DisplayName("#2089 AC2 - once the reopened work order replicates, the newer mapping wins, so "
            + "the fallback lasts only as long as the lag")
    void replicatedNewerMappingSupersedesTheEarlierRun() {
        Appointment appointment = persistAppointment();
        persistMapping(SUPERSEDED_WORKORDER_ID, appointment);
        persistWorkorderReplica(SUPERSEDED_WORKORDER_ID, FIRST_RUN_STARTED, FIRST_RUN_COMPLETED);
        persistMapping(CURRENT_WORKORDER_ID, appointment);
        persistWorkorderReplica(CURRENT_WORKORDER_ID, SECOND_RUN_STARTED, null);
        flushAndClear();

        Optional<WorkorderActuals> resolved =
                repository.findActualsByAppointmentIds(List.of(appointment.getAppointmentId())).stream()
                        .reduce(WorkorderActuals::mostCurrent);

        assertThat(resolved).isPresent();
        assertThat(resolved.get().workOrderId()).isEqualTo(CURRENT_WORKORDER_ID);
        assertThat(resolved.get().workStartedAt()).isEqualTo(SECOND_RUN_STARTED);
        assertThat(resolved.get().completedAt()).isNull();
    }

    private Appointment persistAppointment() {
        Appointment appointment = Appointment.builder()
                .status(AppointmentStatus.SCHEDULED)
                .locationId(LOCATION_ID)
                .crmCustomerId(CUSTOMER_ID)
                .crmVehicleId(VEHICLE_ID)
                .startAt(PLANNED_START)
                .endAt(PLANNED_END)
                .build();
        em.persist(appointment);
        return appointment;
    }

    private void persistMapping(UUID workOrderId, Appointment appointment) {
        em.persist(WorkOrderAppointmentMapping.builder()
                .workOrderId(workOrderId)
                .appointment(appointment)
                .build());
    }

    private void persistWorkorderReplica(
            UUID workorderId, @Nullable Instant workStartedAt, @Nullable Instant completedAt) {
        em.persist(ExtWorkorderReplica.builder()
                .workorderId(workorderId)
                .aggregateVersion(1)
                .updatedAt(Instant.now())
                .workStartedAt(workStartedAt)
                .completedAt(completedAt)
                .build());
    }

    private void flushAndClear() {
        em.flush();
        em.clear();
    }
}
