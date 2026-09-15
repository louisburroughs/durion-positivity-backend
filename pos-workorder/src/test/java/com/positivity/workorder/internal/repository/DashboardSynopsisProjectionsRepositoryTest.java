package com.positivity.workorder.internal.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.workorder.PostgresSliceTestBase;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.entity.WorkorderLaborEntry;
import com.positivity.workorder.internal.entity.WorkorderServiceLine;
import com.positivity.workorder.internal.enums.WorkorderItemStatus;
import com.positivity.workorder.internal.enums.WorkorderStatus;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The two batched projections the dispatch dashboard summarises a roster from (#2025), against the real
 * PostgreSQL baseline.
 *
 * <p>{@code DashboardServiceTest} stubs both interfaces, so it cannot see a wrong JPQL path, a projection
 * alias that does not bind to its getter, an ordering that does not hold, or an aggregate that groups on
 * the wrong key. Only a statement issued to PostgreSQL settles those.
 */
@DisplayName("Dispatch dashboard synopsis projections on PostgreSQL")
class DashboardSynopsisProjectionsRepositoryTest extends PostgresSliceTestBase {

    private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");

    private static final UUID TECHNICIAN = UUID.fromString("00000000-0000-0000-0000-00000000b001");

    @Autowired
    private WorkorderRepository workorderRepository;

    @Autowired
    private WorkorderServiceRepository workorderServiceRepository;

    @Autowired
    private WorkorderLaborEntryRepository workorderLaborEntryRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("Service-line glances project every column, in creation order, for the listed workorders only")
    void findGlancesByWorkorderIds_projectsListedWorkordersInCreationOrder() {
        Workorder listed = workorder("WO-2026-GLANCE-1");
        Workorder unlisted = workorder("WO-2026-GLANCE-2");
        // Saved out of creation order, so an ORDER BY on insertion or on id alone would not pass.
        line(listed, "Brake Pads", WorkorderItemStatus.IN_PROGRESS, false, 20);
        line(listed, "Oil Change", WorkorderItemStatus.COMPLETED, false, 10);
        line(listed, "Alignment", WorkorderItemStatus.OPEN, true, 30);
        line(unlisted, "Wipers", WorkorderItemStatus.OPEN, false, 5);

        List<WorkorderServiceRepository.ServiceLineGlance> glances =
                workorderServiceRepository.findGlancesByWorkorderIds(Set.of(listed.getId()));

        assertThat(glances)
                .extracting(WorkorderServiceRepository.ServiceLineGlance::getDescription)
                .containsExactly("Oil Change", "Brake Pads", "Alignment");
        assertThat(glances)
                .extracting(WorkorderServiceRepository.ServiceLineGlance::getWorkorderId)
                .containsOnly(listed.getId());
        assertThat(glances)
                .extracting(WorkorderServiceRepository.ServiceLineGlance::getStatus)
                .containsExactly(
                        WorkorderItemStatus.COMPLETED, WorkorderItemStatus.IN_PROGRESS, WorkorderItemStatus.OPEN);
        assertThat(glances)
                .extracting(WorkorderServiceRepository.ServiceLineGlance::getDeclined)
                .containsExactly(false, false, true);
    }

    @Test
    @DisplayName("Labor hours sum across a workorder's service lines, one row per workorder with entries")
    void sumHoursByWorkorderIds_sumsAcrossLinesPerWorkorder() {
        Workorder busy = workorder("WO-2026-HOURS-1");
        Workorder zero = workorder("WO-2026-HOURS-2");
        Workorder idle = workorder("WO-2026-HOURS-3");
        Workorder unlisted = workorder("WO-2026-HOURS-4");
        WorkorderServiceLine busyFirst = line(busy, "Oil Change", WorkorderItemStatus.COMPLETED, false, 10);
        WorkorderServiceLine busySecond = line(busy, "Brake Pads", WorkorderItemStatus.CANCELLED, false, 20);
        WorkorderServiceLine zeroLine = line(zero, "Inspection", WorkorderItemStatus.OPEN, false, 10);
        line(idle, "Air Filter", WorkorderItemStatus.OPEN, false, 10);
        WorkorderServiceLine unlistedLine = line(unlisted, "Wipers", WorkorderItemStatus.OPEN, false, 10);
        labor(busy, busyFirst, "1.50", 8);
        labor(busy, busyFirst, "0.25", 10);
        // A cancelled line's time was still worked, and the detail view totals it.
        labor(busy, busySecond, "2.00", 11);
        labor(zero, zeroLine, "0.00", 8);
        labor(unlisted, unlistedLine, "4.00", 8);

        Map<UUID, BigDecimal> hours =
                workorderLaborEntryRepository
                        .sumHoursByWorkorderIds(Set.of(busy.getId(), zero.getId(), idle.getId()))
                        .stream()
                        .collect(Collectors.toMap(
                                WorkorderLaborEntryRepository.WorkorderLaborHours::getWorkorderId,
                                WorkorderLaborEntryRepository.WorkorderLaborHours::getHours));

        assertThat(hours).containsOnlyKeys(busy.getId(), zero.getId());
        assertThat(hours.get(busy.getId())).isEqualByComparingTo("3.75");
        assertThat(hours.get(zero.getId())).isEqualByComparingTo("0");
    }

    private Workorder workorder(String number) {
        Workorder workorder = new Workorder();
        workorder.setStatus(WorkorderStatus.WORK_IN_PROGRESS);
        // workorder_number, created_at and updated_at are NOT NULL in the baseline; auditing is not on
        // this slice, so the fixture sets them itself.
        workorder.setWorkorderNumber(number);
        workorder.setCreatedAt(BASE);
        workorder.setUpdatedAt(BASE);
        return workorderRepository.save(workorder);
    }

    private WorkorderServiceLine line(
            Workorder workorder,
            String description,
            WorkorderItemStatus status,
            boolean declined,
            int secondsAfterBase) {
        Instant createdAt = BASE.plusSeconds(secondsAfterBase);
        WorkorderServiceLine saved = workorderServiceRepository.save(WorkorderServiceLine.builder()
                .workOrder(workorder)
                .description(description)
                .status(status)
                .declined(declined)
                .createdAt(createdAt)
                .updatedAt(createdAt)
                .build());
        // created_at is @CreatedDate and not updatable through JPA, so the value the builder carried is
        // replaced on persist. Pin it with SQL, or creation order would just be insertion order and the
        // ordering assertion could not tell ORDER BY created_at from insertion.
        entityManager.flush();
        int pinned = entityManager
                .createNativeQuery("UPDATE workorder_service SET created_at = :createdAt WHERE id = :id")
                .setParameter("createdAt", createdAt)
                .setParameter("id", saved.getId())
                .executeUpdate();
        assertThat(pinned).as("created_at pinned for %s", description).isEqualTo(1);
        return saved;
    }

    private void labor(Workorder workorder, WorkorderServiceLine line, String hoursWorked, int startHour) {
        LocalDateTime start = LocalDateTime.of(2026, 1, 1, startHour, 0);
        workorderLaborEntryRepository.save(WorkorderLaborEntry.builder()
                .workorder(workorder)
                .workorderService(line)
                .technicianId(TECHNICIAN)
                .startTime(start)
                .endTime(start.plusMinutes(30))
                .hoursWorked(new BigDecimal(hoursWorked))
                .createdBy("tech-1")
                .createdAt(BASE)
                .updatedAt(BASE)
                .build());
    }
}
