package com.positivity.people.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.location.LocationAncestry.Dimension;
import com.positivity.people.internal.entity.ExtJobTimeReplica;
import com.positivity.people.internal.entity.TimeEntry;
import com.positivity.people.internal.exception.RequestValidationException;
import com.positivity.people.internal.repository.ExtJobTimeReplicaRepository;
import com.positivity.people.internal.repository.ExtPersonReplicaRepository;
import com.positivity.people.internal.repository.TimeEntryRepository;
import com.positivity.people.internal.security.PeoplePermissions;
import com.positivity.security.common.LocationAncestorResolver;
import com.positivity.security.common.LocationScope;
import com.positivity.security.common.LocationScopeDeniedException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Location scope on the people reports (ADR-0061 §3, #1872): the approved-time export gates every
 * named location; the discrepancy report gates a named location and narrows an absent one to the
 * caller's reach. Ancestry comes from an in-memory resolver so the decision is pinned without a
 * database — the replica walk itself is covered by {@code TimeEntryLocationScopeTest}.
 *
 * <pre>
 *   DISTRICT ─── SHOP_A   (OTHER edge)
 *   SHOP_C               (unrelated)
 * </pre>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PeopleReportsServiceImpl — location scope")
class PeopleReportsLocationScopeTest {

    private static final UUID DISTRICT = UUID.fromString("018f0000-0000-7000-8000-0000000000d1");
    private static final UUID SHOP_A = UUID.fromString("018f0000-0000-7000-8000-0000000000a1");
    private static final UUID SHOP_C = UUID.fromString("018f0000-0000-7000-8000-0000000000c1");
    private static final UUID TECH = UUID.fromString("018f0000-0000-7000-8000-0000000000aa");
    private static final LocalDate DAY = LocalDate.of(2026, 3, 2);
    private static final String EXPORT = PeoplePermissions.ACCOUNTING_TIME_EXPORT;
    private static final String ACTOR = "svc-reports";

    private static final LocationAncestorResolver RESOLVER = LocationScopeFixtures.resolverOf(Map.of(
            DISTRICT, LocationScopeFixtures.selfOnly(DISTRICT),
            SHOP_A, LocationScopeFixtures.underOther(SHOP_A, DISTRICT),
            SHOP_C, LocationScopeFixtures.selfOnly(SHOP_C)));

    @Mock
    private TimeEntryRepository timeEntryRepository;

    @Mock
    private ExtPersonReplicaRepository extPersonReplicaRepository;

    @Mock
    private ExtJobTimeReplicaRepository extJobTimeReplicaRepository;

    @Mock
    private LocationReferenceService locationReferenceService;

    @Mock
    private TimekeepingThresholdCache timekeepingThresholdCache;

    @Mock
    private TimekeepingThresholdCache.ThresholdResolverContext thresholdContext;

    @Mock
    private LocationHierarchyService locationHierarchyService;

    private PeopleReportsServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PeopleReportsServiceImpl(
                timeEntryRepository,
                extPersonReplicaRepository,
                extJobTimeReplicaRepository,
                locationReferenceService,
                timekeepingThresholdCache,
                locationHierarchyService,
                Clock.fixed(Instant.parse("2026-03-02T20:00:00Z"), ZoneOffset.UTC));
        when(timekeepingThresholdCache.createContext(any(), any())).thenReturn(thresholdContext);
        when(thresholdContext.resolveThresholdMinutes(any(), any())).thenReturn(30);
        when(locationReferenceService.isLocationActive(any())).thenReturn(true);
        when(locationReferenceService.getLocationName(any())).thenReturn("Loc");
        when(locationHierarchyService.descendantsOf(DISTRICT, Dimension.OTHER)).thenReturn(Set.of(DISTRICT, SHOP_A));
        when(locationHierarchyService.descendantsOf(SHOP_C, Dimension.OTHER)).thenReturn(Set.of(SHOP_C));
    }

    @AfterEach
    void clearCaller() {
        LocationScopeFixtures.clearCaller();
    }

    private static LocationScope otherScopedAt(UUID... nodes) {
        return LocationScopeFixtures.scopedOn(EXPORT, Set.of(Dimension.OTHER), RESOLVER, nodes);
    }

    private static TimeEntry attendance(UUID locationId) {
        TimeEntry entry = new TimeEntry();
        entry.setTimeEntryId(UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4dff"));
        entry.setPersonId(TECH);
        entry.setLocationId(locationId);
        entry.setAttendanceStartAt(Instant.parse("2026-03-02T08:00:00Z"));
        entry.setAttendanceEndAt(Instant.parse("2026-03-02T16:00:00Z"));
        return entry;
    }

    private static ExtJobTimeReplica jobTime(UUID locationId) {
        ExtJobTimeReplica row = new ExtJobTimeReplica();
        row.setLaborEntryId(UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4dfe"));
        row.setTechnicianId(TECH);
        row.setLocationId(locationId);
        row.setEndAtUtc(Instant.parse("2026-03-02T15:00:00Z"));
        row.setMinutes(60);
        return row;
    }

    @Nested
    @DisplayName("getAttendanceDiscrepancyReport — locationId given: gate")
    class DiscrepancyGate {

        @Test
        @DisplayName("a location inside the caller's reach is queried as before")
        void inReach() {
            LocationScopeFixtures.callerWith(ACTOR, otherScopedAt(DISTRICT));
            when(timeEntryRepository.findAttendanceOverlappingWindow(any(), any(), eq(SHOP_A), anyList(), eq(true)))
                    .thenReturn(List.of(attendance(SHOP_A)));
            when(extJobTimeReplicaRepository.findForReportWindow(any(), any(), eq(SHOP_A), anyList(), eq(true)))
                    .thenReturn(List.of(jobTime(SHOP_A)));

            var rows = service.getAttendanceDiscrepancyReport(DAY, DAY, "UTC", SHOP_A, List.of(), false, ACTOR, null);

            assertThat(rows).singleElement().satisfies(row -> {
                assertThat(row.getLocationId()).isEqualTo(SHOP_A.toString());
                assertThat(row.getTotalAttendanceHours()).isEqualTo(8.0d);
                assertThat(row.getTotalJobHours()).isEqualTo(1.0d);
            });
            verify(timeEntryRepository, never())
                    .findAttendanceOverlappingWindowWithinLocations(any(), any(), any(), anyList(), anyBoolean());
        }

        @Test
        @DisplayName("a location outside the caller's reach is refused, not silently emptied")
        void outOfReach() {
            LocationScopeFixtures.callerWith(ACTOR, otherScopedAt(DISTRICT));

            assertThatThrownBy(() -> service.getAttendanceDiscrepancyReport(
                            DAY, DAY, "UTC", SHOP_C, List.of(), false, ACTOR, null))
                    .isInstanceOf(LocationScopeDeniedException.class)
                    .satisfies(ex -> assertThat(((LocationScopeDeniedException) ex).permission())
                            .isEqualTo(EXPORT));
            verifyNoInteractions(timeEntryRepository, extJobTimeReplicaRepository);
        }

        @Test
        @DisplayName("a pre-rollout token is not gated at all")
        void preRolloutTokenIsNotGated() {
            LocationScopeFixtures.preRolloutCaller(ACTOR);
            when(timeEntryRepository.findAttendanceOverlappingWindow(any(), any(), eq(SHOP_C), anyList(), eq(true)))
                    .thenReturn(List.of(attendance(SHOP_C)));
            when(extJobTimeReplicaRepository.findForReportWindow(any(), any(), eq(SHOP_C), anyList(), eq(true)))
                    .thenReturn(List.of());

            var rows = service.getAttendanceDiscrepancyReport(DAY, DAY, "UTC", SHOP_C, List.of(), false, ACTOR, null);

            assertThat(rows)
                    .singleElement()
                    .satisfies(row -> assertThat(row.getLocationId()).isEqualTo(SHOP_C.toString()));
        }
    }

    @Nested
    @DisplayName("getAttendanceDiscrepancyReport — locationId absent: narrow")
    class DiscrepancyNarrow {

        @Test
        @DisplayName("a scoped caller sees their reach and nothing else, through the IN-set queries")
        void scopedCallerIsNarrowedToReach() {
            LocationScopeFixtures.callerWith(ACTOR, otherScopedAt(DISTRICT));
            Set<UUID> reach = Set.of(DISTRICT, SHOP_A);
            when(timeEntryRepository.findAttendanceOverlappingWindowWithinLocations(
                            any(), any(), eq(reach), anyList(), eq(true)))
                    .thenReturn(List.of(attendance(SHOP_A)));
            when(extJobTimeReplicaRepository.findForReportWindowWithinLocations(
                            any(), any(), eq(reach), anyList(), eq(true)))
                    .thenReturn(List.of(jobTime(DISTRICT)));

            var rows = service.getAttendanceDiscrepancyReport(DAY, DAY, "UTC", null, List.of(), false, ACTOR, null);

            assertThat(rows).extracting(r -> UUID.fromString(r.getLocationId())).containsExactly(SHOP_A, DISTRICT);
            verify(timeEntryRepository, never())
                    .findAttendanceOverlappingWindow(any(), any(), any(), anyList(), anyBoolean());
            verify(extJobTimeReplicaRepository, never())
                    .findForReportWindow(any(), any(), any(), anyList(), anyBoolean());
        }

        @Test
        @DisplayName("several assigned nodes are unioned into one reach")
        void multipleNodesUnion() {
            LocationScopeFixtures.callerWith(ACTOR, otherScopedAt(DISTRICT, SHOP_C));
            when(timeEntryRepository.findAttendanceOverlappingWindowWithinLocations(
                            any(), any(), any(), anyList(), anyBoolean()))
                    .thenReturn(List.of());
            when(extJobTimeReplicaRepository.findForReportWindowWithinLocations(
                            any(), any(), any(), anyList(), anyBoolean()))
                    .thenReturn(List.of());

            service.getAttendanceDiscrepancyReport(DAY, DAY, "UTC", null, List.of(), false, ACTOR, null);

            verify(timeEntryRepository)
                    .findAttendanceOverlappingWindowWithinLocations(
                            any(), any(), eq(Set.of(DISTRICT, SHOP_A, SHOP_C)), anyList(), eq(true));
        }

        @Test
        @DisplayName("an empty reach is an empty report and no query, never an unrestricted one")
        void emptyReachIsEmptyReport() {
            LocationScopeFixtures.callerWith(
                    ACTOR, LocationScope.of(Set.of(), Set.of(EXPORT), Optional.empty(), true, RESOLVER));

            var rows = service.getAttendanceDiscrepancyReport(DAY, DAY, "UTC", null, List.of(), false, ACTOR, null);

            assertThat(rows).isEmpty();
            verifyNoInteractions(timeEntryRepository, extJobTimeReplicaRepository);
        }

        @Test
        @DisplayName("a node the replica does not hold reaches nothing — fail closed")
        void unreplicatedNodeIsEmptyReport() {
            UUID unreplicated = UUID.fromString("018f0000-0000-7000-8000-000000000099");
            when(locationHierarchyService.descendantsOf(unreplicated, Dimension.OTHER))
                    .thenReturn(Set.of());
            LocationScopeFixtures.callerWith(ACTOR, otherScopedAt(unreplicated));

            var rows = service.getAttendanceDiscrepancyReport(DAY, DAY, "UTC", null, List.of(), false, ACTOR, null);

            assertThat(rows).isEmpty();
            verifyNoInteractions(timeEntryRepository, extJobTimeReplicaRepository);
        }

        @Test
        @DisplayName("an unscoped caller (claims present, permission global) is unchanged")
        void globalCallerUnchanged() {
            LocationScopeFixtures.callerWith(ACTOR, LocationScopeFixtures.globalWithClaims(RESOLVER));
            when(timeEntryRepository.findAttendanceOverlappingWindow(any(), any(), isNull(), anyList(), eq(true)))
                    .thenReturn(List.of(attendance(SHOP_C)));
            when(extJobTimeReplicaRepository.findForReportWindow(any(), any(), isNull(), anyList(), eq(true)))
                    .thenReturn(List.of());

            var rows = service.getAttendanceDiscrepancyReport(DAY, DAY, "UTC", null, List.of(), false, ACTOR, null);

            assertThat(rows).hasSize(1);
            verify(timeEntryRepository, never())
                    .findAttendanceOverlappingWindowWithinLocations(any(), any(), any(), anyList(), anyBoolean());
        }

        @Test
        @DisplayName("a pre-rollout token is unchanged")
        void preRolloutTokenUnchanged() {
            LocationScopeFixtures.preRolloutCaller(ACTOR);
            when(timeEntryRepository.findAttendanceOverlappingWindow(any(), any(), isNull(), anyList(), eq(true)))
                    .thenReturn(List.of(attendance(SHOP_C)));
            when(extJobTimeReplicaRepository.findForReportWindow(any(), any(), isNull(), anyList(), eq(true)))
                    .thenReturn(List.of());

            var rows = service.getAttendanceDiscrepancyReport(DAY, DAY, "UTC", null, List.of(), false, ACTOR, null);

            assertThat(rows).hasSize(1);
            verifyNoInteractions(locationHierarchyService);
        }
    }

    @Nested
    @DisplayName("getApprovedTimeForExport — every locationId: gate")
    class ExportGate {

        @Test
        @DisplayName("locations all inside the caller's reach pass through to the query")
        void allInReach() {
            LocationScopeFixtures.callerWith(ACTOR, otherScopedAt(DISTRICT));
            when(timeEntryRepository.findApprovedForExport(any(), any(), any(), eq(List.of(DISTRICT, SHOP_A))))
                    .thenReturn(List.of());

            var rows = service.getApprovedTimeForExport(DAY, DAY, List.of(DISTRICT, SHOP_A), ACTOR, null);

            assertThat(rows).isEmpty();
            verify(timeEntryRepository).findApprovedForExport(any(), any(), any(), eq(List.of(DISTRICT, SHOP_A)));
        }

        @Test
        @DisplayName("one location outside the caller's reach refuses the whole request")
        void anyOutOfReachIsDenied() {
            LocationScopeFixtures.callerWith(ACTOR, otherScopedAt(DISTRICT));
            List<UUID> locations = List.of(SHOP_A, SHOP_C);

            assertThatThrownBy(() -> service.getApprovedTimeForExport(DAY, DAY, locations, ACTOR, null))
                    .isInstanceOf(LocationScopeDeniedException.class);
            verifyNoInteractions(timeEntryRepository);
        }

        @Test
        @DisplayName("an unknown location is a 400 for a scoped caller too — validation precedes the gate")
        void unknownLocationIs400BeforeTheGate() {
            LocationScopeFixtures.callerWith(ACTOR, otherScopedAt(DISTRICT));
            when(locationReferenceService.isLocationActive(SHOP_C)).thenReturn(false);
            List<UUID> locations = List.of(SHOP_C);

            assertThatThrownBy(() -> service.getApprovedTimeForExport(DAY, DAY, locations, ACTOR, null))
                    .isInstanceOf(RequestValidationException.class)
                    .hasMessageContaining("Unknown locationId");
        }

        @Test
        @DisplayName("a pre-rollout token is not gated at all")
        void preRolloutTokenIsNotGated() {
            LocationScopeFixtures.preRolloutCaller(ACTOR);
            when(timeEntryRepository.findApprovedForExport(any(), any(), any(), any()))
                    .thenReturn(List.of());

            assertThat(service.getApprovedTimeForExport(DAY, DAY, List.of(SHOP_C), ACTOR, null))
                    .isEmpty();
        }
    }
}
