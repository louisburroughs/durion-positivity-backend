package com.positivity.people.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.location.LocationAncestry.Dimension;
import com.positivity.people.internal.config.PeopleEventPublisher;
import com.positivity.people.internal.dto.CreateStaffingAssignmentRequest;
import com.positivity.people.internal.dto.UpdateStaffingAssignmentRequest;
import com.positivity.people.internal.entity.Employee;
import com.positivity.people.internal.entity.EmployeeLocationAssignment;
import com.positivity.people.internal.entity.ExtPersonReplica;
import com.positivity.people.internal.enums.AssignmentStatus;
import com.positivity.people.internal.enums.EmployeeStatus;
import com.positivity.people.internal.repository.EmployeeLocationAssignmentRepository;
import com.positivity.people.internal.repository.EmployeeRepository;
import com.positivity.people.internal.repository.ExtPersonReplicaRepository;
import com.positivity.people.internal.security.PeoplePermissions;
import com.positivity.security.common.LocationAncestorResolver;
import com.positivity.security.common.LocationScope;
import com.positivity.security.common.LocationScopeDeniedException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Location scope on the staffing-assignment mutations (ADR-0061 §3, #1872): an assignment feeds
 * the person's scope claims, so a caller whose {@code people:employee:edit} is location-scoped may
 * only create, reshape or end assignments within their own reach. Every gate runs after the
 * existence checks so a 404 precedes a 403.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("StaffingAssignmentServiceImpl — location scope on create/update/end")
class StaffingAssignmentLocationScopeTest {

    private static final Instant NOW = Instant.parse("2026-03-01T10:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 3, 1);
    private static final UUID PERSON_ID = UUID.fromString("018f0000-0000-7000-8000-0000000000aa");
    private static final UUID ASSIGNMENT_ID = UUID.fromString("018f0000-0000-7000-8000-0000000000f1");
    private static final UUID DISTRICT = UUID.fromString("018f0000-0000-7000-8000-0000000000d1");
    private static final UUID SHOP_A = UUID.fromString("018f0000-0000-7000-8000-0000000000a1");
    private static final UUID SHOP_C = UUID.fromString("018f0000-0000-7000-8000-0000000000c1");
    private static final String EDIT = PeoplePermissions.EMPLOYEE_EDIT;
    private static final String ACTOR = "hr.user";

    private static final LocationAncestorResolver RESOLVER = LocationScopeFixtures.resolverOf(Map.of(
            DISTRICT, LocationScopeFixtures.selfOnly(DISTRICT),
            SHOP_A, LocationScopeFixtures.underOther(SHOP_A, DISTRICT),
            SHOP_C, LocationScopeFixtures.selfOnly(SHOP_C)));

    @Mock
    private EmployeeLocationAssignmentRepository repository;

    @Mock
    private ExtPersonReplicaRepository extPersonReplicaRepository;

    @Mock
    private PeopleEventPublisher peopleEventPublisher;

    @Mock
    private EmployeeRepository employeeRepository;

    @Mock
    private LocationReferenceService locationReferenceService;

    private StaffingAssignmentServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new StaffingAssignmentServiceImpl(
                repository,
                extPersonReplicaRepository,
                peopleEventPublisher,
                employeeRepository,
                locationReferenceService,
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(extPersonReplicaRepository.findById(PERSON_ID))
                .thenReturn(Optional.of(ExtPersonReplica.builder()
                        .personId(PERSON_ID)
                        .aggregateVersion(0)
                        .updatedAt(NOW)
                        .build()));
        when(employeeRepository.findByPersonId(PERSON_ID))
                .thenReturn(Optional.of(Employee.builder()
                        .personId(PERSON_ID)
                        .status(EmployeeStatus.ACTIVE)
                        .build()));
        when(locationReferenceService.isLocationActive(any())).thenReturn(true);
        when(repository.save(any(EmployeeLocationAssignment.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void clearCaller() {
        LocationScopeFixtures.clearCaller();
    }

    private static LocationScope otherScopedAt(UUID... nodes) {
        return LocationScopeFixtures.scopedOn(EDIT, Set.of(Dimension.OTHER), RESOLVER, nodes);
    }

    private static EmployeeLocationAssignment existingAt(UUID locationId) {
        return EmployeeLocationAssignment.builder()
                .id(ASSIGNMENT_ID)
                .employee(Employee.builder().personId(PERSON_ID).build())
                .locationId(locationId)
                .role("TECHNICIAN")
                .isPrimary(true)
                .effectiveFrom(TODAY.minusDays(10))
                .status(AssignmentStatus.ACTIVE)
                .build();
    }

    private static CreateStaffingAssignmentRequest createAt(UUID locationId) {
        return new CreateStaffingAssignmentRequest(PERSON_ID, locationId, "TECHNICIAN", true, TODAY, null);
    }

    private static UpdateStaffingAssignmentRequest updateTo(UUID locationId) {
        return new UpdateStaffingAssignmentRequest(PERSON_ID, locationId, "TECHNICIAN", true, TODAY, null);
    }

    private static void assertScopeDenied(Throwable ex) {
        assertThat(ex).isInstanceOf(LocationScopeDeniedException.class);
        assertThat(((LocationScopeDeniedException) ex).permission()).isEqualTo(EDIT);
    }

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("a location inside the caller's reach is assigned")
        void inReach() {
            LocationScopeFixtures.callerWith(ACTOR, otherScopedAt(DISTRICT));

            assertThat(service.create(createAt(SHOP_A), ACTOR).getLocationId()).isEqualTo(SHOP_A);
        }

        @Test
        @DisplayName("a location outside the caller's reach is refused and nothing is saved")
        void outOfReach() {
            LocationScopeFixtures.callerWith(ACTOR, otherScopedAt(DISTRICT));
            CreateStaffingAssignmentRequest request = createAt(SHOP_C);

            assertThatThrownBy(() -> service.create(request, ACTOR))
                    .satisfies(StaffingAssignmentLocationScopeTest::assertScopeDenied);
            verify(repository, never()).save(any());
            verify(peopleEventPublisher, never()).publishStaffingAssignmentUpdated(any());
        }

        @Test
        @DisplayName("an unknown location is a 404 for a scoped caller too — resolution precedes the gate")
        void unknownLocationIs404BeforeTheGate() {
            LocationScopeFixtures.callerWith(ACTOR, otherScopedAt(DISTRICT));
            when(locationReferenceService.isLocationActive(SHOP_C)).thenReturn(false);
            CreateStaffingAssignmentRequest request = createAt(SHOP_C);

            assertThatThrownBy(() -> service.create(request, ACTOR))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                            .isEqualTo(HttpStatus.NOT_FOUND));
        }

        @Test
        @DisplayName("a pre-rollout token is not gated at all")
        void preRolloutTokenIsNotGated() {
            LocationScopeFixtures.preRolloutCaller(ACTOR);

            assertThat(service.create(createAt(SHOP_C), ACTOR).getLocationId()).isEqualTo(SHOP_C);
        }
    }

    @Nested
    @DisplayName("update")
    class Update {

        @Test
        @DisplayName("moving between two locations inside the reach succeeds")
        void bothEndsInReach() {
            LocationScopeFixtures.callerWith(ACTOR, otherScopedAt(DISTRICT));
            when(repository.findById(ASSIGNMENT_ID)).thenReturn(Optional.of(existingAt(SHOP_A)));

            Optional<?> updated = service.update(ASSIGNMENT_ID, updateTo(DISTRICT), ACTOR);

            assertThat(updated).isPresent();
        }

        @Test
        @DisplayName("an assignment currently outside the reach cannot be moved in, even to a covered location")
        void existingLocationOutOfReach() {
            LocationScopeFixtures.callerWith(ACTOR, otherScopedAt(DISTRICT));
            when(repository.findById(ASSIGNMENT_ID)).thenReturn(Optional.of(existingAt(SHOP_C)));
            UpdateStaffingAssignmentRequest request = updateTo(SHOP_A);

            assertThatThrownBy(() -> service.update(ASSIGNMENT_ID, request, ACTOR))
                    .satisfies(StaffingAssignmentLocationScopeTest::assertScopeDenied);
            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("an assignment inside the reach cannot be moved out to an uncovered location")
        void requestedLocationOutOfReach() {
            LocationScopeFixtures.callerWith(ACTOR, otherScopedAt(DISTRICT));
            when(repository.findById(ASSIGNMENT_ID)).thenReturn(Optional.of(existingAt(SHOP_A)));
            UpdateStaffingAssignmentRequest request = updateTo(SHOP_C);

            assertThatThrownBy(() -> service.update(ASSIGNMENT_ID, request, ACTOR))
                    .satisfies(StaffingAssignmentLocationScopeTest::assertScopeDenied);
            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("an unknown assignment is a 404 (empty) before any scope decision — ids cannot be probed")
        void unknownAssignmentIs404BeforeTheGate() {
            LocationScopeFixtures.callerWith(ACTOR, otherScopedAt(DISTRICT));
            when(repository.findById(ASSIGNMENT_ID)).thenReturn(Optional.empty());

            assertThat(service.update(ASSIGNMENT_ID, updateTo(SHOP_C), ACTOR)).isEmpty();
        }

        @Test
        @DisplayName("a pre-rollout token is not gated at all")
        void preRolloutTokenIsNotGated() {
            LocationScopeFixtures.preRolloutCaller(ACTOR);
            when(repository.findById(ASSIGNMENT_ID)).thenReturn(Optional.of(existingAt(SHOP_C)));

            assertThat(service.update(ASSIGNMENT_ID, updateTo(SHOP_A), ACTOR)).isPresent();
        }
    }

    @Nested
    @DisplayName("end")
    class End {

        @Test
        @DisplayName("an assignment inside the reach is ended")
        void inReach() {
            LocationScopeFixtures.callerWith(ACTOR, otherScopedAt(DISTRICT));
            EmployeeLocationAssignment existing = existingAt(SHOP_A);
            when(repository.findById(ASSIGNMENT_ID)).thenReturn(Optional.of(existing));

            service.end(ASSIGNMENT_ID);

            assertThat(existing.getStatus()).isEqualTo(AssignmentStatus.ENDED);
        }

        @Test
        @DisplayName("an assignment outside the reach is refused and left untouched")
        void outOfReach() {
            LocationScopeFixtures.callerWith(ACTOR, otherScopedAt(DISTRICT));
            EmployeeLocationAssignment existing = existingAt(SHOP_C);
            when(repository.findById(ASSIGNMENT_ID)).thenReturn(Optional.of(existing));

            assertThatThrownBy(() -> service.end(ASSIGNMENT_ID))
                    .satisfies(StaffingAssignmentLocationScopeTest::assertScopeDenied);
            assertThat(existing.getStatus()).isEqualTo(AssignmentStatus.ACTIVE);
            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("an unknown assignment is a 404 before any scope decision")
        void unknownAssignmentIs404BeforeTheGate() {
            LocationScopeFixtures.callerWith(ACTOR, otherScopedAt(DISTRICT));
            when(repository.findById(ASSIGNMENT_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.end(ASSIGNMENT_ID))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                            .isEqualTo(HttpStatus.NOT_FOUND));
        }
    }
}
