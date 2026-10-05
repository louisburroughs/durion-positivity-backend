package com.positivity.workorder.internal.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.positivity.workorder.internal.entity.Estimate;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.enums.EstimateStatus;
import com.positivity.workorder.internal.enums.WorkorderStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

/**
 * {@link WorkorderRepository#findSourceAppointments} against a real database (#2531).
 *
 * <p>The fact publisher takes a workorder's source appointment from this query and nothing else, so
 * what a mock cannot check is what matters: that the join reaches {@code Estimate.appointmentId}
 * through the foreign key, and that the two kinds of workorder with no source appointment — one
 * with no estimate at all, one whose estimate did not come from an appointment — produce no row
 * rather than a row carrying null.
 */
@DataJpaTest(properties = {"spring.flyway.enabled=false"})
class WorkorderSourceAppointmentQueryTest {

    private static final Instant SEEDED_AT = Instant.parse("2026-03-01T00:00:00Z");

    @Autowired
    private WorkorderRepository workorderRepository;

    @Autowired
    private EstimateRepository estimateRepository;

    @Test
    @DisplayName("#2531: each workorder promoted from an appointment's estimate is paired with that appointment")
    void pairsAWorkorderWithItsEstimatesAppointment() {
        UUID appointmentA = UUID.randomUUID();
        UUID appointmentB = UUID.randomUUID();
        UUID workorderA = seedWorkorder(seedEstimate(appointmentA));
        UUID workorderB = seedWorkorder(seedEstimate(appointmentB));

        assertThat(workorderRepository.findSourceAppointments(List.of(workorderA, workorderB)))
                .extracting(
                        WorkorderRepository.SourceAppointment::getWorkorderId,
                        WorkorderRepository.SourceAppointment::getAppointmentId)
                .containsExactlyInAnyOrder(tuple(workorderA, appointmentA), tuple(workorderB, appointmentB));
    }

    @Test
    @DisplayName("#2531: two workorders from one estimate both name its appointment")
    void everyWorkorderOfAnEstimateNamesItsAppointment() {
        UUID appointment = UUID.randomUUID();
        Estimate estimate = seedEstimate(appointment);
        UUID first = seedWorkorder(estimate);
        UUID second = seedWorkorder(estimate);

        assertThat(workorderRepository.findSourceAppointments(List.of(first, second)))
                .extracting(WorkorderRepository.SourceAppointment::getAppointmentId)
                .containsExactly(appointment, appointment);
    }

    @Test
    @DisplayName("#2531: a walk-in, an estimate with no appointment, and an id not asked for produce no row")
    void workordersWithoutASourceAppointmentAreAbsent() {
        UUID walkIn = seedWorkorder(null);
        UUID fromPlainEstimate = seedWorkorder(seedEstimate(null));
        seedWorkorder(seedEstimate(UUID.randomUUID())); // linked, but not in the requested set

        assertThat(workorderRepository.findSourceAppointments(List.of(walkIn, fromPlainEstimate)))
                .isEmpty();
    }

    private Estimate seedEstimate(UUID appointmentId) {
        Estimate estimate = new Estimate();
        estimate.setStatus(EstimateStatus.APPROVED);
        estimate.setCreatedById("seed");
        // @CreatedDate/@LastModifiedDate are not reliably applied on this slice's context.
        estimate.setCreatedAt(SEEDED_AT);
        estimate.setUpdatedAt(SEEDED_AT);
        estimate.setAppointmentId(appointmentId);
        return estimateRepository.saveAndFlush(estimate);
    }

    private UUID seedWorkorder(Estimate estimate) {
        Workorder workorder = new Workorder();
        workorder.setCreatedAt(SEEDED_AT);
        workorder.setUpdatedAt(SEEDED_AT);
        workorder.setStatus(WorkorderStatus.APPROVED);
        workorder.setEstimate(estimate);
        return workorderRepository.saveAndFlush(workorder).getId();
    }
}
