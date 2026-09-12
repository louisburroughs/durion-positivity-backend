package com.positivity.inventory.internal.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.inventory.PostgresSliceTestBase;
import com.positivity.inventory.internal.entity.CycleCountSchedule;
import com.positivity.inventory.internal.entity.ExtStorageLocationReplica;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * The cycle-count schedule listing against the dialect that actually serves it.
 *
 * <p>Its reason to exist is the {@code dueOnOrBefore} filter. Written as
 * {@code (:dueOnOrBefore IS NULL OR …)} in one JPQL string, it made every
 * {@code GET /inventory/cycleCountSchedules?due=true} a 500 on PostgreSQL — {@code could not
 * determine data type of parameter $6}, raised at parse time — while the H2-backed tests of the
 * same query stayed green. {@link CycleCountScheduleSearch} explains why. Every case below that
 * passes a date therefore defends the fix directly: revert the specification to the JPQL string and
 * it fails again, on the driver rather than on an assertion.
 */
@DisplayName("Cycle-count schedule search on PostgreSQL")
class CycleCountScheduleSearchTest extends PostgresSliceTestBase {

    private static final LocalDate TODAY = LocalDate.of(2026, 3, 10);

    @Autowired
    private CycleCountScheduleRepository schedules;

    @Autowired
    private ExtStorageLocationReplicaRepository storageLocations;

    private UUID siteA;
    private UUID siteB;
    private UUID binUnderSiteA;
    private UUID activeDueAtSiteA;
    private UUID activeNotDueAtSiteA;
    private UUID inactiveDueAtSiteA;
    private UUID activeDueAtBinUnderSiteA;
    private UUID activeDueAtSiteB;

    @BeforeEach
    void seed() {
        siteA = UUID.randomUUID();
        siteB = UUID.randomUUID();
        binUnderSiteA = UUID.randomUUID();

        storageLocations.save(ExtStorageLocationReplica.builder()
                .storageLocationId(binUnderSiteA)
                .siteId(siteA)
                .aggregateVersion(1L)
                .updatedAt(Instant.parse("2026-03-01T00:00:00Z"))
                .build());

        // createdAt descending is the listing's contract order, so the fixtures are minted a minute
        // apart and declared newest first — the order every assertion below expects them back in.
        activeDueAtSiteA = save(siteA, true, TODAY.minusDays(1), 1);
        activeNotDueAtSiteA = save(siteA, true, TODAY.plusDays(7), 2);
        inactiveDueAtSiteA = save(siteA, false, TODAY.minusDays(3), 3);
        activeDueAtBinUnderSiteA = save(binUnderSiteA, true, TODAY, 4);
        activeDueAtSiteB = save(siteB, true, TODAY.minusDays(2), 5);
    }

    private UUID save(UUID locationId, boolean active, LocalDate nextDueDate, int minutesAgo) {
        Instant createdAt = Instant.parse("2026-03-10T12:00:00Z").minus(minutesAgo, ChronoUnit.MINUTES);
        return schedules
                .saveAndFlush(CycleCountSchedule.builder()
                        .locationId(locationId)
                        .frequencyDays(30)
                        .nextDueDate(nextDueDate)
                        .autoCreatePlan(false)
                        .active(active)
                        .createdBy("test")
                        .createdAt(createdAt)
                        .updatedAt(createdAt)
                        .build())
                .getScheduleId();
    }

    private static List<UUID> ids(Page<CycleCountSchedule> page) {
        return page.getContent().stream().map(CycleCountSchedule::getScheduleId).toList();
    }

    @Nested
    @DisplayName("unnarrowed listing")
    class Unnarrowed {

        @Test
        @DisplayName("a supplied dueOnOrBefore is the clause PostgreSQL rejected: it now returns the due worklist")
        void dueOnOrBeforeReturnsOnlyActiveSchedulesDue() {
            Page<CycleCountSchedule> page = schedules.findByOptionalFilters(null, null, TODAY, PageRequest.of(0, 50));

            assertThat(ids(page))
                    .as("active and due, at any location; the inactive-but-due schedule is not work")
                    .containsExactly(activeDueAtSiteA, activeDueAtBinUnderSiteA, activeDueAtSiteB);
        }

        @Test
        @DisplayName("dueOnOrBefore excludes an inactive schedule even when active=false is asked for")
        void dueOnOrBeforeImpliesActive() {
            Page<CycleCountSchedule> page =
                    schedules.findByOptionalFilters(null, Boolean.FALSE, TODAY, PageRequest.of(0, 50));

            assertThat(ids(page))
                    .as("the two clauses conjoin, so nothing is both inactive and due-for-count")
                    .isEmpty();
        }

        @Test
        @DisplayName("every filter at once still parses and narrows")
        void allThreeFiltersTogether() {
            Page<CycleCountSchedule> page =
                    schedules.findByOptionalFilters(siteA, Boolean.TRUE, TODAY, PageRequest.of(0, 50));

            assertThat(ids(page)).containsExactly(activeDueAtSiteA);
        }

        @Test
        @DisplayName("an omitted filter contributes no predicate, so an unfiltered call pages everything")
        void noFiltersPagesEverything() {
            Page<CycleCountSchedule> page = schedules.findByOptionalFilters(null, null, null, PageRequest.of(0, 50));

            assertThat(ids(page))
                    .containsExactly(
                            activeDueAtSiteA,
                            activeNotDueAtSiteA,
                            inactiveDueAtSiteA,
                            activeDueAtBinUnderSiteA,
                            activeDueAtSiteB);
        }

        @Test
        @DisplayName("active alone filters without the date")
        void activeAlone() {
            Page<CycleCountSchedule> page =
                    schedules.findByOptionalFilters(null, Boolean.FALSE, null, PageRequest.of(0, 50));

            assertThat(ids(page)).containsExactly(inactiveDueAtSiteA);
        }

        @Test
        @DisplayName("the count query is derived from the same specification, so page and total agree")
        void pageAndCountAgree() {
            Page<CycleCountSchedule> firstPage =
                    schedules.findByOptionalFilters(null, null, TODAY, PageRequest.of(0, 2));

            assertThat(firstPage.getTotalElements()).isEqualTo(3);
            assertThat(ids(firstPage)).containsExactly(activeDueAtSiteA, activeDueAtBinUnderSiteA);
        }

        @Test
        @DisplayName("an unpaged request still gets the listing's order")
        void unpagedIsSortedToo() {
            Page<CycleCountSchedule> page = schedules.findByOptionalFilters(null, null, TODAY, Pageable.unpaged());

            assertThat(ids(page)).containsExactly(activeDueAtSiteA, activeDueAtBinUnderSiteA, activeDueAtSiteB);
        }
    }

    @Nested
    @DisplayName("listing narrowed to the caller's reach (ADR-0061 §3)")
    class WithinLocations {

        @Test
        @DisplayName("a supplied dueOnOrBefore narrows the reach to the due worklist")
        void dueOnOrBeforeWithinReach() {
            Page<CycleCountSchedule> page =
                    schedules.findByOptionalFiltersWithinLocations(Set.of(siteA), null, TODAY, PageRequest.of(0, 50));

            assertThat(ids(page))
                    .as("the site's own schedules and those of bins replicated under it, minus siteB")
                    .containsExactly(activeDueAtSiteA, activeDueAtBinUnderSiteA);
        }

        @Test
        @DisplayName("the reach covers storage locations replicated under a reachable site")
        void reachIncludesStorageLocationsUnderTheSite() {
            Page<CycleCountSchedule> page =
                    schedules.findByOptionalFiltersWithinLocations(Set.of(siteA), null, null, PageRequest.of(0, 50));

            assertThat(ids(page))
                    .containsExactly(
                            activeDueAtSiteA, activeNotDueAtSiteA, inactiveDueAtSiteA, activeDueAtBinUnderSiteA);
        }

        @Test
        @DisplayName("a reach that covers nothing returns an empty page rather than everything")
        void unreachableSiteMatchesNothing() {
            Page<CycleCountSchedule> page = schedules.findByOptionalFiltersWithinLocations(
                    Set.of(UUID.randomUUID()), null, TODAY, PageRequest.of(0, 50));

            assertThat(ids(page)).isEmpty();
        }

        @Test
        @DisplayName("every filter at once inside a reach still parses")
        void allFiltersWithinReach() {
            Page<CycleCountSchedule> page = schedules.findByOptionalFiltersWithinLocations(
                    Set.of(siteA, siteB), Boolean.TRUE, TODAY, PageRequest.of(0, 50));

            assertThat(ids(page)).containsExactly(activeDueAtSiteA, activeDueAtBinUnderSiteA, activeDueAtSiteB);
        }
    }
}
