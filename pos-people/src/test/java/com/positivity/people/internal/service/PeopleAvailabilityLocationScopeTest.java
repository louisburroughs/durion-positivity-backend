package com.positivity.people.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.location.LocationAncestry.Dimension;
import com.positivity.people.internal.dto.PeopleAvailabilityResponse;
import com.positivity.people.internal.entity.Employee;
import com.positivity.people.internal.entity.EmployeeLocationAssignment;
import com.positivity.people.internal.repository.EmployeeLocationAssignmentRepository;
import com.positivity.people.internal.repository.ExtLocationReplicaRepository;
import com.positivity.people.internal.repository.ExtPersonReplicaRepository;
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
 * Location scope on the availability list (ADR-0061 §3, #1872): a named location is gated; the
 * defaulted (requester's own) location is narrowed — outside a scoped caller's reach the list is
 * empty rather than refused.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PeopleAvailabilityServiceImpl.getPeopleAvailability — location scope")
class PeopleAvailabilityLocationScopeTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-15T10:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate TODAY = LocalDate.now(CLOCK);
    private static final String USERNAME = "supervisor";
    private static final UUID PERSON_ID = UUID.fromString("018f0000-0000-7000-8000-0000000000aa");
    private static final UUID DISTRICT = UUID.fromString("018f0000-0000-7000-8000-0000000000d1");
    private static final UUID SHOP_A = UUID.fromString("018f0000-0000-7000-8000-0000000000a1");
    private static final UUID SHOP_C = UUID.fromString("018f0000-0000-7000-8000-0000000000c1");
    private static final String VIEW = PeoplePermissions.AVAILABILITY_VIEW;

    private static final LocationAncestorResolver RESOLVER = LocationScopeFixtures.resolverOf(Map.of(
            DISTRICT, LocationScopeFixtures.selfOnly(DISTRICT),
            SHOP_A, LocationScopeFixtures.underOther(SHOP_A, DISTRICT),
            SHOP_C, LocationScopeFixtures.selfOnly(SHOP_C)));

    @Mock
    private EmployeeLocationAssignmentRepository assignmentRepository;

    @Mock
    private ExtPersonReplicaRepository extPersonReplicaRepository;

    @Mock
    private UserPersonTranslationService userPersonTranslationService;

    @Mock
    private ExtLocationReplicaRepository extLocationReplicaRepository;

    @Mock
    private LocationReferenceService locationReferenceService;

    private PeopleAvailabilityServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PeopleAvailabilityServiceImpl(
                assignmentRepository,
                extPersonReplicaRepository,
                userPersonTranslationService,
                CLOCK,
                extLocationReplicaRepository,
                locationReferenceService);
        when(userPersonTranslationService.getPersonUuidForUser(USERNAME)).thenReturn(PERSON_ID);
        when(extPersonReplicaRepository.findAllById(any())).thenReturn(List.of());
    }

    @AfterEach
    void clearCaller() {
        LocationScopeFixtures.clearCaller();
    }

    private static LocationScope otherScopedAt(UUID... nodes) {
        return LocationScopeFixtures.scopedOn(VIEW, Set.of(Dimension.OTHER), RESOLVER, nodes);
    }

    private static EmployeeLocationAssignment assignment(UUID locationId) {
        return EmployeeLocationAssignment.builder()
                .employee(Employee.builder().personId(PERSON_ID).build())
                .locationId(locationId)
                .role("TECHNICIAN")
                .isPrimary(true)
                .effectiveFrom(TODAY.minusDays(30))
                .build();
    }

    private void requesterAssignedAt(UUID locationId) {
        when(assignmentRepository.findActiveByPersonIdAndDate(PERSON_ID, TODAY))
                .thenReturn(List.of(assignment(locationId)));
    }

    private void rowsAt(UUID locationId) {
        when(assignmentRepository.findActiveByDateAndOptionalLocation(TODAY, locationId))
                .thenReturn(List.of(assignment(locationId)));
    }

    @Nested
    @DisplayName("locationId given — gate")
    class Gate {

        @Test
        @DisplayName("a location inside the caller's reach returns that location's rows")
        void inReach() {
            LocationScopeFixtures.callerWith(USERNAME, otherScopedAt(DISTRICT));
            rowsAt(SHOP_A);

            List<PeopleAvailabilityResponse> rows = service.getPeopleAvailability(SHOP_A, null);

            assertThat(rows)
                    .singleElement()
                    .satisfies(row -> assertThat(row.getLocationId()).isEqualTo(SHOP_A));
        }

        @Test
        @DisplayName("a location outside the caller's reach is refused, not silently emptied")
        void outOfReach() {
            LocationScopeFixtures.callerWith(USERNAME, otherScopedAt(DISTRICT));

            assertThatThrownBy(() -> service.getPeopleAvailability(SHOP_C, null))
                    .isInstanceOf(LocationScopeDeniedException.class)
                    .satisfies(ex -> assertThat(((LocationScopeDeniedException) ex).permission())
                            .isEqualTo(VIEW));
            verify(assignmentRepository, never()).findActiveByDateAndOptionalLocation(any(), any());
        }

        @Test
        @DisplayName("a pre-rollout token is not gated at all")
        void preRolloutTokenIsNotGated() {
            LocationScopeFixtures.preRolloutCaller(USERNAME);
            rowsAt(SHOP_C);

            assertThat(service.getPeopleAvailability(SHOP_C, null)).hasSize(1);
        }
    }

    @Nested
    @DisplayName("locationId absent — narrow to the requester's own location")
    class Narrow {

        @Test
        @DisplayName("the requester's own location inside the reach is listed")
        void ownLocationInReach() {
            LocationScopeFixtures.callerWith(USERNAME, otherScopedAt(DISTRICT));
            requesterAssignedAt(SHOP_A);
            rowsAt(SHOP_A);

            assertThat(service.getPeopleAvailability(null, null)).hasSize(1);
        }

        @Test
        @DisplayName("the requester's own location outside the reach is an empty list, not a refusal")
        void ownLocationOutOfReachIsEmpty() {
            LocationScopeFixtures.callerWith(USERNAME, otherScopedAt(DISTRICT));
            requesterAssignedAt(SHOP_C);

            assertThat(service.getPeopleAvailability(null, null)).isEmpty();
            verify(assignmentRepository, never()).findActiveByDateAndOptionalLocation(any(), any());
        }

        @Test
        @DisplayName("scope bits set but no nodes in the token narrows to nothing")
        void nodesAbsentIsEmpty() {
            LocationScopeFixtures.callerWith(
                    USERNAME, LocationScope.of(Set.of(), Set.of(VIEW), java.util.Optional.empty(), true, RESOLVER));
            requesterAssignedAt(SHOP_A);

            assertThat(service.getPeopleAvailability(null, null)).isEmpty();
        }

        @Test
        @DisplayName("an unscoped caller (claims present, permission global) is unchanged")
        void globalCallerUnchanged() {
            LocationScopeFixtures.callerWith(USERNAME, LocationScopeFixtures.globalWithClaims(RESOLVER));
            requesterAssignedAt(SHOP_C);
            rowsAt(SHOP_C);

            assertThat(service.getPeopleAvailability(null, null)).hasSize(1);
        }

        @Test
        @DisplayName("a pre-rollout token is unchanged")
        void preRolloutTokenUnchanged() {
            LocationScopeFixtures.preRolloutCaller(USERNAME);
            requesterAssignedAt(SHOP_C);
            rowsAt(SHOP_C);

            assertThat(service.getPeopleAvailability(null, null)).hasSize(1);
        }
    }
}
