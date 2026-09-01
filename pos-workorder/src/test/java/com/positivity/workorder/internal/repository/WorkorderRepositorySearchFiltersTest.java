package com.positivity.workorder.internal.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.entity.WorkorderLaborEntry;
import com.positivity.workorder.internal.entity.WorkorderServiceLine;
import com.positivity.workorder.internal.enums.WorkorderStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * DataJpaTest for the E12 (#1600) structured search filters on {@link
 * WorkorderRepository#searchByQuery}: status, createdFrom/createdTo, and technicianId (attributed
 * via {@link WorkorderLaborEntry}, not the workorder's assigned technician).
 *
 * <p>{@code Workorder.createdAt} is {@code @CreatedDate}-managed. Whether {@code JpaAuditingConfig}
 * ends up on this slice's context is not reliable across isolated vs. full-suite runs (observed both
 * ways), and when it is active it silently overwrites a pre-save {@code setCreatedAt} with "now",
 * which would put every seeded row outside a fixed 2026 test window. To stay deterministic either
 * way, seeding forces {@code created_at} to the desired value via a direct JDBC update after the
 * JPA save (bypassing the auditing listener entirely) and clears the persistence context so the
 * query under test reads the corrected row rather than a cached in-memory entity.
 */
@DataJpaTest(properties = {"spring.flyway.enabled=false"})
class WorkorderRepositorySearchFiltersTest {

    private static final UUID CUSTOMER_A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID CUSTOMER_B = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000002");
    private static final UUID TECHNICIAN_A = UUID.fromString("cccccccc-0000-0000-0000-000000000001");
    private static final UUID TECHNICIAN_B = UUID.fromString("cccccccc-0000-0000-0000-000000000002");

    // Mirrors WorkorderSearchServiceImpl's UNBOUNDED_CREATED_FROM/TO: the repository query compares
    // createdAt directly (no "IS NULL OR" branch), because a null bound is not a type Postgres/H2 can
    // infer inside a temporal comparison.
    private static final Instant UNBOUNDED_FROM = Instant.parse("0001-01-01T00:00:00Z");
    private static final Instant UNBOUNDED_TO = Instant.parse("9999-12-31T23:59:59Z");

    @Autowired
    private WorkorderRepository workorderRepository;

    @Autowired
    private WorkorderServiceRepository workorderServiceLineRepository;

    @Autowired
    private WorkorderLaborEntryRepository workorderLaborEntryRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    private UUID openWorkorderForCustomerA;
    private UUID completedWorkorderForCustomerA;
    private UUID openWorkorderForCustomerB;

    @BeforeEach
    void setUp() {
        openWorkorderForCustomerA =
                seedWorkorder(CUSTOMER_A, WorkorderStatus.APPROVED, Instant.parse("2026-06-10T00:00:00Z"));
        completedWorkorderForCustomerA =
                seedWorkorder(CUSTOMER_A, WorkorderStatus.COMPLETED, Instant.parse("2026-06-15T00:00:00Z"));
        openWorkorderForCustomerB =
                seedWorkorder(CUSTOMER_B, WorkorderStatus.WORK_IN_PROGRESS, Instant.parse("2026-06-20T00:00:00Z"));

        seedLaborEntry(openWorkorderForCustomerA, TECHNICIAN_A);
        seedLaborEntry(completedWorkorderForCustomerA, TECHNICIAN_B);
    }

    @Test
    @DisplayName("Q5 gate combo: exact open status + customerId narrows to one workorder")
    void statusPlusCustomerId_returnsOnlyThatOpenWorkorderForThatCustomer() {
        Page<Workorder> page = workorderRepository.searchByQuery(
                "",
                sentinelCustomerIds(),
                null,
                CUSTOMER_A,
                null,
                WorkorderStatus.APPROVED,
                UNBOUNDED_FROM,
                UNBOUNDED_TO,
                null,
                PageRequest.of(0, 25));

        assertThat(page.getContent()).extracting(Workorder::getId).containsExactly(openWorkorderForCustomerA);
    }

    @Test
    @DisplayName("Omitting status applies no restriction (every status matches)")
    void noStatusFilter_matchesEveryStatus() {
        Page<Workorder> page = workorderRepository.searchByQuery(
                "",
                sentinelCustomerIds(),
                null,
                CUSTOMER_A,
                null,
                null,
                UNBOUNDED_FROM,
                UNBOUNDED_TO,
                null,
                PageRequest.of(0, 25));

        assertThat(page.getContent())
                .extracting(Workorder::getId)
                .containsExactlyInAnyOrder(openWorkorderForCustomerA, completedWorkorderForCustomerA);
    }

    @Test
    @DisplayName("createdFrom/createdTo restricts to the inclusive calendar-date window")
    void createdDateWindow_restrictsToWindow() {
        Page<Workorder> page = workorderRepository.searchByQuery(
                "",
                sentinelCustomerIds(),
                null,
                null,
                null,
                null,
                Instant.parse("2026-06-14T00:00:00Z"),
                Instant.parse("2026-06-21T00:00:00Z"),
                null,
                PageRequest.of(0, 25));

        assertThat(page.getContent())
                .extracting(Workorder::getId)
                .containsExactlyInAnyOrder(completedWorkorderForCustomerA, openWorkorderForCustomerB);
    }

    @Test
    @DisplayName("technicianId matches the labor-entry technician, not the workorder's status/customer alone")
    void technicianId_matchesWorkorderWithLaborEntryForThatTechnician() {
        Page<Workorder> page = workorderRepository.searchByQuery(
                "",
                sentinelCustomerIds(),
                null,
                null,
                null,
                null,
                UNBOUNDED_FROM,
                UNBOUNDED_TO,
                TECHNICIAN_B,
                PageRequest.of(0, 25));

        assertThat(page.getContent()).extracting(Workorder::getId).containsExactly(completedWorkorderForCustomerA);
    }

    @Test
    @DisplayName("technicianId with no matching labor entry returns an empty page")
    void technicianId_withNoLaborEntry_returnsEmpty() {
        Page<Workorder> page = workorderRepository.searchByQuery(
                "",
                sentinelCustomerIds(),
                null,
                null,
                null,
                null,
                UNBOUNDED_FROM,
                UNBOUNDED_TO,
                UUID.fromString("cccccccc-0000-0000-0000-0000000000ff"),
                PageRequest.of(0, 25));

        assertThat(page.getContent()).isEmpty();
    }

    private static List<UUID> sentinelCustomerIds() {
        // Mirrors the service layer's convention: JPQL IN requires a non-empty collection, and a
        // sentinel that cannot match a real id keeps the q='' branch's customerId IN clause inert.
        return List.of(new UUID(0, 0));
    }

    private UUID seedWorkorder(UUID customerId, WorkorderStatus status, Instant createdAt) {
        Workorder workorder = new Workorder();
        workorder.setCustomerId(customerId);
        workorder.setStatus(status);
        workorder.setCreatedAt(createdAt);
        workorder.setUpdatedAt(createdAt);
        UUID id = workorderRepository.saveAndFlush(workorder).getId();

        // Force created_at/updated_at past whatever @CreatedDate auditing may have written, then
        // drop the persistence context's identity-mapped entity so the next read reflects the DB row.
        jdbcTemplate.update(
                "UPDATE workorder SET created_at = ?, updated_at = ? WHERE id = ?",
                Timestamp.from(createdAt),
                Timestamp.from(createdAt),
                id);
        entityManager.clear();
        return id;
    }

    private void seedLaborEntry(UUID workorderId, UUID technicianId) {
        Workorder managedWorkorder = workorderRepository.findById(workorderId).orElseThrow();
        WorkorderServiceLine serviceLine = workorderServiceLineRepository.save(WorkorderServiceLine.builder()
                .workOrder(managedWorkorder)
                .createdAt(Instant.parse("2026-06-01T00:00:00Z"))
                .updatedAt(Instant.parse("2026-06-01T00:00:00Z"))
                .build());
        LocalDateTime now = LocalDateTime.of(2026, 6, 1, 0, 0);
        workorderLaborEntryRepository.save(WorkorderLaborEntry.builder()
                .workorder(managedWorkorder)
                .workorderService(serviceLine)
                .technicianId(technicianId)
                .startTime(now)
                .endTime(now.plusHours(1))
                .hoursWorked(BigDecimal.ONE)
                .createdBy("test-actor")
                .createdAt(Instant.parse("2026-06-01T00:00:00Z"))
                .updatedAt(Instant.parse("2026-06-01T00:00:00Z"))
                .build());
        entityManager.flush();
        entityManager.clear();
    }
}
