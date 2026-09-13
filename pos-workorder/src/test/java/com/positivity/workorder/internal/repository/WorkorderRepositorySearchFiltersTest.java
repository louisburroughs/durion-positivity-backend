package com.positivity.workorder.internal.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.workorder.PostgresSliceTestBase;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The workorder finder ({@link WorkorderRepository#searchByQuery}) against the real PostgreSQL
 * baseline: the E12 (#1600) structured filters — status, createdFrom/createdTo, and technicianId
 * (attributed via {@link WorkorderLaborEntry}, not the workorder's assigned technician) — plus the
 * free-text leg and the optional customer, vehicle and id filters.
 *
 * <p>It runs on PostgreSQL rather than the H2 schema it used to boot because what it now also pins
 * is a property of PostgreSQL. The finder is one JPQL string containing four
 * {@code (:param IS NULL OR …)}-shaped clauses, and it is deliberately left that way: PostgreSQL
 * rejects that shape at parse time only when the placeholder's type cannot be inferred, which is the
 * case for a temporal parameter and not for these — {@code customerId}, {@code vehicleId},
 * {@code technicianId} and {@code idQuery} are all UUIDs, which carry a concrete type OID for a
 * value and for {@code setNull} alike (issue #1891). The query works, so it was not rewritten.
 *
 * <p>The one temporal pair it takes, {@code createdFrom}/{@code createdTo}, is already outside that
 * shape: the service widens an absent bound to a sentinel far in the past or future rather than
 * passing null, so the JPQL compares {@code createdAt} directly with no {@code IS NULL} branch. What
 * this test pins is that both of those decisions hold. Reintroducing an optional temporal bound, or
 * adding any other optional temporal filter, would make every call to the workorder finder a 500 —
 * and only a statement issued to PostgreSQL can see it.
 *
 * <p>{@code Workorder.createdAt} is {@code @CreatedDate}-managed, and whether an auditing
 * configuration reaches a slice context is not something a test should depend on, so seeding forces
 * {@code created_at} to the desired value via a direct JDBC update after the JPA save (bypassing any
 * auditing listener entirely) and clears the persistence context so the query under test reads the
 * corrected row rather than a cached in-memory entity.
 */
@DisplayName("Workorder finder on PostgreSQL")
class WorkorderRepositorySearchFiltersTest extends PostgresSliceTestBase {

    private static final UUID CUSTOMER_A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID CUSTOMER_B = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000002");
    private static final UUID TECHNICIAN_A = UUID.fromString("cccccccc-0000-0000-0000-000000000001");
    private static final UUID TECHNICIAN_B = UUID.fromString("cccccccc-0000-0000-0000-000000000002");
    private static final UUID VEHICLE_A = UUID.fromString("dddddddd-0000-0000-0000-000000000001");

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
                true,
                List.of(WorkorderStatus.APPROVED),
                UNBOUNDED_FROM,
                UNBOUNDED_TO,
                null,
                PageRequest.of(0, 25));

        assertThat(page.getContent()).extracting(Workorder::getId).containsExactly(openWorkorderForCustomerA);
    }

    @Test
    @DisplayName("#1676: two statuses in one call match either status server-side")
    void twoStatuses_matchesEitherStatusInOneCall() {
        Page<Workorder> page = workorderRepository.searchByQuery(
                "",
                sentinelCustomerIds(),
                null,
                null,
                null,
                true,
                List.of(WorkorderStatus.APPROVED, WorkorderStatus.WORK_IN_PROGRESS),
                UNBOUNDED_FROM,
                UNBOUNDED_TO,
                null,
                PageRequest.of(0, 25));

        assertThat(page.getContent())
                .extracting(Workorder::getId)
                .containsExactlyInAnyOrder(openWorkorderForCustomerA, openWorkorderForCustomerB);
    }

    @Test
    @DisplayName("statusFilterEnabled=false applies no restriction regardless of statuses (every status matches)")
    void noStatusFilter_matchesEveryStatus() {
        Page<Workorder> page = workorderRepository.searchByQuery(
                "",
                sentinelCustomerIds(),
                null,
                CUSTOMER_A,
                null,
                false,
                List.of(WorkorderStatus.CANCELLED),
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
                false,
                sentinelStatuses(),
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
                false,
                sentinelStatuses(),
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
                false,
                sentinelStatuses(),
                UNBOUNDED_FROM,
                UNBOUNDED_TO,
                UUID.fromString("cccccccc-0000-0000-0000-0000000000ff"),
                PageRequest.of(0, 25));

        assertThat(page.getContent()).isEmpty();
    }

    @Test
    @DisplayName("every optional filter absent: the statement parses and matches every workorder")
    void everyOptionalFilterAbsentMatchesEveryWorkorder() {
        // customerId, vehicleId, idQuery and technicianId are all null here. This is the call shape
        // that a temporal parameter in the same position would have made unparseable (#1891).
        Page<Workorder> page = workorderRepository.searchByQuery(
                "",
                sentinelCustomerIds(),
                null,
                null,
                null,
                false,
                sentinelStatuses(),
                UNBOUNDED_FROM,
                UNBOUNDED_TO,
                null,
                PageRequest.of(0, 25));

        assertThat(page.getContent())
                .extracting(Workorder::getId)
                .containsExactlyInAnyOrder(
                        openWorkorderForCustomerA, completedWorkorderForCustomerA, openWorkorderForCustomerB);
        assertThat(page.getTotalElements()).isEqualTo(3);
    }

    @Test
    @DisplayName("the free-text leg matches the workorder number case-insensitively")
    void freeTextLegMatchesWorkorderNumber() {
        UUID numbered = seedWorkorder(
                CUSTOMER_A,
                WorkorderStatus.APPROVED,
                Instant.parse("2026-06-11T00:00:00Z"),
                "WO-2026-ABC123",
                VEHICLE_A);

        Page<Workorder> page = workorderRepository.searchByQuery(
                "abc123",
                sentinelCustomerIds(),
                null,
                null,
                null,
                false,
                sentinelStatuses(),
                UNBOUNDED_FROM,
                UNBOUNDED_TO,
                null,
                PageRequest.of(0, 25));

        assertThat(page.getContent()).extracting(Workorder::getId).containsExactly(numbered);
    }

    @Test
    @DisplayName("a query that parses as a UUID matches that workorder by id, and a null idQuery matches none by id")
    void idQueryMatchesTheWorkorderById() {
        // The :idQuery IS NOT NULL guard is the mirror image of the IS NULL clauses: the same
        // inference question, and a UUID answers it in both directions.
        Page<Workorder> byId = workorderRepository.searchByQuery(
                "no-such-number",
                sentinelCustomerIds(),
                completedWorkorderForCustomerA,
                null,
                null,
                false,
                sentinelStatuses(),
                UNBOUNDED_FROM,
                UNBOUNDED_TO,
                null,
                PageRequest.of(0, 25));

        assertThat(byId.getContent()).extracting(Workorder::getId).containsExactly(completedWorkorderForCustomerA);
    }

    @Test
    @DisplayName("the vehicle filter narrows the result on its own")
    void vehicleFilterNarrowsTheResult() {
        UUID otherVehicle = UUID.fromString("dddddddd-0000-0000-0000-0000000000ff");
        UUID onOtherVehicle = seedWorkorder(
                CUSTOMER_A,
                WorkorderStatus.APPROVED,
                Instant.parse("2026-06-12T00:00:00Z"),
                "WO-2026-OTHERVEHICLE",
                otherVehicle);

        Page<Workorder> page = workorderRepository.searchByQuery(
                "",
                sentinelCustomerIds(),
                null,
                null,
                otherVehicle,
                false,
                sentinelStatuses(),
                UNBOUNDED_FROM,
                UNBOUNDED_TO,
                null,
                PageRequest.of(0, 25));

        assertThat(page.getContent()).extracting(Workorder::getId).containsExactly(onOtherVehicle);
    }

    private static List<UUID> sentinelCustomerIds() {
        // Mirrors the service layer's convention: JPQL IN requires a non-empty collection, and a
        // sentinel that cannot match a real id keeps the q='' branch's customerId IN clause inert.
        return List.of(new UUID(0, 0));
    }

    private static List<WorkorderStatus> sentinelStatuses() {
        // JPQL IN also requires a non-empty collection for statuses; statusFilterEnabled=false
        // bypasses evaluating it, so the value here is never actually read (#1676).
        return List.of(WorkorderStatus.DRAFT);
    }

    private UUID seedWorkorder(UUID customerId, WorkorderStatus status, Instant createdAt) {
        return seedWorkorder(customerId, status, createdAt, "WO-" + UUID.randomUUID(), VEHICLE_A);
    }

    private UUID seedWorkorder(
            UUID customerId, WorkorderStatus status, Instant createdAt, String number, UUID vehicleId) {
        Workorder workorder = new Workorder();
        workorder.setCustomerId(customerId);
        workorder.setVehicleId(vehicleId);
        workorder.setStatus(status);
        // workorder.workorder_number is NOT NULL in the baseline; the H2 schema this test used to
        // boot was generated from the mapping, which does not say so, and let the column go unset.
        workorder.setWorkorderNumber(number);
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
