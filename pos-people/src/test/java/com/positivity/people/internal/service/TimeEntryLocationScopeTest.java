package com.positivity.people.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.positivity.domainevents.location.LocationAncestry.Dimension;
import com.positivity.people.internal.config.JpaAuditingConfig;
import com.positivity.people.internal.dto.PagedResponse;
import com.positivity.people.internal.dto.TimeEntrySummary;
import com.positivity.people.internal.entity.ExtLocationParentReplica;
import com.positivity.people.internal.entity.ExtLocationReplica;
import com.positivity.people.internal.entity.TimeEntry;
import com.positivity.people.internal.enums.TimeEntryStatus;
import com.positivity.people.internal.repository.ExtLocationParentReplicaRepository;
import com.positivity.people.internal.repository.ExtLocationReplicaRepository;
import com.positivity.people.internal.repository.TimeEntryAuditRepository;
import com.positivity.people.internal.repository.TimeEntryRepository;
import com.positivity.people.internal.security.PeoplePermissions;
import com.positivity.security.common.GatewaySecurityConstants;
import com.positivity.security.common.LocationScope;
import com.positivity.security.common.LocationScopeDeniedException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Location scope on the approvals queue (ADR-0061 §3, #1871), end to end through
 * {@link TimeEntryServiceImpl#listTimeEntries} against a real database: the replica tree,
 * {@link LocationHierarchyService#descendantsOf}, the {@code IN :locationIds} query and the
 * gate/narrow decision are exercised together, because a mocked repository would prove nothing
 * about which rows a scoped caller actually sees.
 *
 * <p>The fixture is deliberately small and two-dimensional:
 *
 * <pre>
 *   OTHER:      DISTRICT ─┬─ SHOP_A   (DISTRICT edge)
 *                         └─ SHOP_B   (PHYSICAL edge)
 *   FINANCIAL:  DISTRICT ─── SHOP_D   (FINANCIAL edge only — must not widen an OTHER reach)
 *   unrelated:  SHOP_C     (replicated, no edges)
 *   LONELY      (replicated, no edges, no entries)
 *   UNREPLICATED (named in a token, never replicated)
 * </pre>
 *
 * One time entry is clocked at each of DISTRICT, SHOP_A, SHOP_B, SHOP_C and SHOP_D.
 */
@DataJpaTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:pos_people_te_scope;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
            "spring.flyway.enabled=false",
            "spring.jpa.hibernate.ddl-auto=create-drop"
        })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, TimeEntryLocationScopeTest.ClockConfig.class})
@DisplayName("TimeEntryServiceImpl.listTimeEntries — location scope: gate a named location, narrow an absent one")
class TimeEntryLocationScopeTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-20T12:00:00Z"), ZoneOffset.UTC);

    private static final UUID DISTRICT = id("d1");
    private static final UUID SHOP_A = id("a1");
    private static final UUID SHOP_B = id("b1");
    private static final UUID SHOP_C = id("c1");
    private static final UUID SHOP_D = id("f1");
    private static final UUID LONELY = id("e1");
    private static final UUID UNREPLICATED = id("99");

    private static final UUID ALICE = id("aa");
    private static final UUID BOB = id("bb");

    private static final String VIEW = PeoplePermissions.TIMEENTRY_VIEW;

    private static UUID id(String suffix) {
        return UUID.fromString("018f0000-0000-7000-8000-0000000000" + suffix);
    }

    @Autowired
    private TimeEntryRepository timeEntryRepository;

    @Autowired
    private ExtLocationReplicaRepository extLocationReplicaRepository;

    @Autowired
    private ExtLocationParentReplicaRepository extLocationParentReplicaRepository;

    @Autowired
    private TestEntityManager entityManager;

    private LocationHierarchyService hierarchy;
    private TimeEntryService service;

    @BeforeEach
    void seed() {
        hierarchy = new LocationHierarchyService(extLocationReplicaRepository, extLocationParentReplicaRepository);
        service = new TimeEntryServiceImpl(CLOCK, timeEntryRepository, mock(TimeEntryAuditRepository.class), hierarchy);

        location(DISTRICT);
        location(SHOP_A, edge(DISTRICT, "DISTRICT"));
        location(SHOP_B, edge(DISTRICT, "PHYSICAL"));
        location(SHOP_D, edge(DISTRICT, "FINANCIAL"));
        location(SHOP_C);
        location(LONELY);
        for (UUID node : List.of(DISTRICT, SHOP_A, SHOP_B, SHOP_C, SHOP_D, LONELY)) {
            hierarchy.recomputeAncestors(node);
        }

        // Submission order is the queue order, so assertions below can use containsExactly.
        entry(ALICE, DISTRICT, "2026-01-15T08:00:00Z");
        entry(ALICE, SHOP_A, "2026-01-15T09:00:00Z");
        entry(BOB, SHOP_A, "2026-01-15T10:00:00Z");
        entry(ALICE, SHOP_B, "2026-01-15T11:00:00Z");
        entry(ALICE, SHOP_C, "2026-01-15T12:00:00Z");
        entry(ALICE, SHOP_D, "2026-01-15T13:00:00Z");
        entityManager.flush();
        entityManager.clear();
    }

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    private record Edge(UUID parent, String type) {}

    private static Edge edge(UUID parent, String type) {
        return new Edge(parent, type);
    }

    private void location(UUID locationId, Edge... edges) {
        extLocationReplicaRepository.save(ExtLocationReplica.builder()
                .locationId(locationId)
                .name("Loc " + locationId)
                .status("ACTIVE")
                .active(true)
                .aggregateVersion(1)
                .build());
        for (Edge edge : edges) {
            extLocationParentReplicaRepository.save(ExtLocationParentReplica.builder()
                    .childId(locationId)
                    .parentType(edge.type())
                    .parentId(edge.parent())
                    .build());
        }
    }

    private void entry(UUID personId, UUID locationId, String submittedAt) {
        TimeEntry entry = new TimeEntry();
        entry.setPersonId(personId);
        entry.setLocationId(locationId);
        entry.setAttendanceStartAt(Instant.parse("2026-01-15T06:00:00Z"));
        entry.setAttendanceEndAt(Instant.parse("2026-01-15T14:00:00Z"));
        entry.setBreakMinutes(30);
        entry.setStatus(TimeEntryStatus.PENDING_APPROVAL);
        entry.setSubmittedAt(Instant.parse(submittedAt));
        timeEntryRepository.save(entry);
    }

    /** Authenticates a caller whose token carries the given scope; the resolver is the real replica. */
    private void callerWith(LocationScope scope) {
        TestingAuthenticationToken authentication = new TestingAuthenticationToken("supervisor", null, "ROLE_USER");
        authentication.setDetails(Map.of(
                GatewaySecurityConstants.DETAIL_USERNAME,
                "supervisor",
                GatewaySecurityConstants.DETAIL_LOCATION_SCOPE,
                scope));
        authentication.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private LocationScope scopedOn(Set<Dimension> dimensions, UUID... nodes) {
        Set<String> financial = dimensions.contains(Dimension.FINANCIAL) ? Set.of(VIEW) : Set.of();
        Set<String> other = dimensions.contains(Dimension.OTHER) ? Set.of(VIEW) : Set.of();
        return LocationScope.of(financial, other, Optional.of(Set.of(nodes)), true, hierarchy);
    }

    private PagedResponse<TimeEntrySummary> list(UUID employeeId, UUID locationId) {
        return service.listTimeEntries(null, null, ZoneOffset.UTC, employeeId, locationId, 0, 20);
    }

    private static List<UUID> locationsOf(PagedResponse<TimeEntrySummary> page) {
        return page.items().stream().map(TimeEntrySummary::locationId).toList();
    }

    @Nested
    @DisplayName("locationId given — gate")
    class Gate {

        @Test
        @DisplayName("a location inside the caller's reach returns only that location's entries")
        void filterInReach() {
            callerWith(scopedOn(Set.of(Dimension.OTHER), DISTRICT));

            PagedResponse<TimeEntrySummary> page = list(null, SHOP_A);

            assertThat(locationsOf(page)).containsExactly(SHOP_A, SHOP_A);
            assertThat(page.totalElements()).isEqualTo(2);
        }

        @Test
        @DisplayName("a location outside the caller's reach is refused, not silently emptied")
        void filterOutOfReach() {
            callerWith(scopedOn(Set.of(Dimension.OTHER), DISTRICT));

            assertThatThrownBy(() -> list(null, SHOP_C))
                    .isInstanceOf(LocationScopeDeniedException.class)
                    .satisfies(ex -> assertThat(((LocationScopeDeniedException) ex).permission())
                            .isEqualTo(VIEW));
        }

        @Test
        @DisplayName("a FINANCIAL-only child is out of reach for an OTHER-scoped caller at its parent")
        void financialEdgeDoesNotWidenOtherGate() {
            callerWith(scopedOn(Set.of(Dimension.OTHER), DISTRICT));

            assertThatThrownBy(() -> list(null, SHOP_D)).isInstanceOf(LocationScopeDeniedException.class);
        }

        @Test
        @DisplayName("a pre-rollout token is not gated at all")
        void preRolloutTokenIsNotGated() {
            callerWith(LocationScope.unscoped());

            assertThat(locationsOf(list(null, SHOP_C))).containsExactly(SHOP_C);
        }
    }

    @Nested
    @DisplayName("locationId absent — narrow")
    class Narrow {

        @Test
        @DisplayName("an OTHER-scoped caller at the district sees the district and its OTHER descendants only")
        void otherScopedCallerSeesOtherSubtree() {
            callerWith(scopedOn(Set.of(Dimension.OTHER), DISTRICT));

            PagedResponse<TimeEntrySummary> page = list(null, null);

            assertThat(locationsOf(page)).containsExactly(DISTRICT, SHOP_A, SHOP_A, SHOP_B);
            assertThat(locationsOf(page)).doesNotContain(SHOP_C, SHOP_D);
            assertThat(page.totalElements()).isEqualTo(4);
        }

        @Test
        @DisplayName("a FINANCIAL-scoped caller at the district sees the district and its FINANCIAL child only")
        void financialScopedCallerSeesFinancialChain() {
            callerWith(scopedOn(Set.of(Dimension.FINANCIAL), DISTRICT));

            assertThat(locationsOf(list(null, null))).containsExactly(DISTRICT, SHOP_D);
        }

        @Test
        @DisplayName("a caller scoped on both dimensions sees the union of both rollups")
        void bothDimensionsUnion() {
            callerWith(scopedOn(Set.of(Dimension.FINANCIAL, Dimension.OTHER), DISTRICT));

            assertThat(locationsOf(list(null, null))).containsExactly(DISTRICT, SHOP_A, SHOP_A, SHOP_B, SHOP_D);
        }

        @Test
        @DisplayName("several assigned nodes are unioned")
        void multipleNodesUnion() {
            callerWith(scopedOn(Set.of(Dimension.OTHER), SHOP_B, SHOP_C));

            assertThat(locationsOf(list(null, null))).containsExactly(SHOP_B, SHOP_C);
        }

        @Test
        @DisplayName("a caller at a replicated node with nothing beneath it and no entries gets an empty page")
        void noReplicatedDescendantsIsEmptyPage() {
            callerWith(scopedOn(Set.of(Dimension.OTHER), LONELY));

            PagedResponse<TimeEntrySummary> page = list(null, null);

            assertThat(page.items()).isEmpty();
            assertThat(page.totalElements()).isZero();
            assertThat(page.totalPages()).isZero();
        }

        @Test
        @DisplayName("a caller at a node the replica does not hold reaches nothing — fail closed")
        void unreplicatedNodeIsEmptyPage() {
            callerWith(scopedOn(Set.of(Dimension.OTHER), UNREPLICATED));

            PagedResponse<TimeEntrySummary> page = list(null, null);

            assertThat(page.items()).isEmpty();
            assertThat(page.totalElements()).isZero();
        }

        @Test
        @DisplayName("scope bits set but no nodes in the token narrows to nothing, never to everything")
        void nodesAbsentIsEmptyPage() {
            callerWith(LocationScope.of(Set.of(), Set.of(VIEW), Optional.empty(), true, hierarchy));

            PagedResponse<TimeEntrySummary> page = list(null, null);

            assertThat(page.items()).isEmpty();
            assertThat(page.totalElements()).isZero();
            assertThat(page.page()).isZero();
            assertThat(page.size()).isEqualTo(20);
        }

        @Test
        @DisplayName("an unscoped caller (claims present, permission global) sees every location, unchanged")
        void unscopedCallerSeesEverything() {
            callerWith(LocationScope.of(Set.of(), Set.of(), Optional.empty(), true, hierarchy));

            assertThat(locationsOf(list(null, null))).containsExactly(DISTRICT, SHOP_A, SHOP_A, SHOP_B, SHOP_C, SHOP_D);
        }

        @Test
        @DisplayName("a pre-rollout token sees every location, unchanged")
        void preRolloutTokenSeesEverything() {
            callerWith(LocationScope.unscoped());

            assertThat(locationsOf(list(null, null))).hasSize(6);
        }

        @Test
        @DisplayName("employeeId and paging still apply inside the narrowed set")
        void employeeAndPagingPreservedWithinReach() {
            callerWith(scopedOn(Set.of(Dimension.OTHER), DISTRICT));

            PagedResponse<TimeEntrySummary> bobOnly = list(BOB, null);
            assertThat(bobOnly.items()).singleElement().satisfies(item -> {
                assertThat(item.employeeId()).isEqualTo(BOB);
                assertThat(item.locationId()).isEqualTo(SHOP_A);
            });

            PagedResponse<TimeEntrySummary> secondPage =
                    service.listTimeEntries(null, null, ZoneOffset.UTC, null, null, 1, 3);
            assertThat(secondPage.page()).isEqualTo(1);
            assertThat(secondPage.size()).isEqualTo(3);
            assertThat(secondPage.totalElements()).isEqualTo(4);
            assertThat(secondPage.totalPages()).isEqualTo(2);
            assertThat(locationsOf(secondPage)).containsExactly(SHOP_B);
        }

        @Test
        @DisplayName("invariant: a location appears in the narrowed list exactly when a filter on it would be allowed")
        void narrowAgreesWithGate() {
            LocationScope scope = scopedOn(Set.of(Dimension.OTHER), DISTRICT);
            callerWith(scope);
            Set<UUID> narrowed = Set.copyOf(locationsOf(list(null, null)));

            for (UUID location : List.of(DISTRICT, SHOP_A, SHOP_B, SHOP_C, SHOP_D)) {
                assertThat(narrowed.contains(location))
                        .as("%s: narrowed membership must equal covers()", location)
                        .isEqualTo(scope.covers(VIEW, location));
            }
        }
    }

    @Nested
    @DisplayName("LocationHierarchyService.descendantsOf")
    class DescendantsOf {

        @Test
        @DisplayName("is inclusive of the node and follows only the dimension's edge types")
        void inclusiveAndDimensionFiltered() {
            assertThat(hierarchy.descendantsOf(DISTRICT, Dimension.OTHER))
                    .containsExactlyInAnyOrder(DISTRICT, SHOP_A, SHOP_B);
            assertThat(hierarchy.descendantsOf(DISTRICT, Dimension.FINANCIAL))
                    .containsExactlyInAnyOrder(DISTRICT, SHOP_D);
        }

        @Test
        @DisplayName("a leaf is its own sole descendant; an unreplicated node reaches nothing")
        void leafAndUnknown() {
            assertThat(hierarchy.descendantsOf(SHOP_A, Dimension.OTHER)).containsExactly(SHOP_A);
            assertThat(hierarchy.descendantsOf(LONELY, Dimension.FINANCIAL)).containsExactly(LONELY);
            assertThat(hierarchy.descendantsOf(UNREPLICATED, Dimension.OTHER)).isEmpty();
        }

        @Test
        @DisplayName(
                "the service is the module's LocationAncestorResolver, so the gate and the narrow share one replica")
        void serviceIsTheResolver() {
            assertThat(hierarchy).isInstanceOf(com.positivity.security.common.LocationAncestorResolver.class);
            assertThat(hierarchy.ancestorsOf(SHOP_A).other()).contains(DISTRICT);
            assertThat(hierarchy.ancestorsOf(SHOP_D).other()).doesNotContain(DISTRICT);
            assertThat(hierarchy.ancestorsOf(SHOP_D).financial()).contains(DISTRICT);
        }
    }

    /** {@link JpaAuditingConfig} needs a clock; the values it stamps are not what this test asserts. */
    @TestConfiguration
    static class ClockConfig {
        @Bean
        Clock clock() {
            return CLOCK;
        }
    }
}
