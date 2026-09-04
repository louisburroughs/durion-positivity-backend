package com.positivity.people.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.people.internal.dto.PrimaryLocationResolution;
import com.positivity.people.internal.entity.Employee;
import com.positivity.people.internal.entity.EmployeeLocationAssignment;
import com.positivity.people.internal.entity.ExtLocationReplica;
import com.positivity.people.internal.repository.EmployeeLocationAssignmentRepository;
import com.positivity.people.internal.repository.ExtLocationReplicaRepository;
import com.positivity.people.internal.repository.ExtPersonReplicaRepository;
import com.positivity.security.common.SecurityContextHelper;
import jakarta.persistence.EntityNotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for PeopleAvailabilityServiceImpl, focused on primary-location
 * resolution semantics: the endpoint must return the assignment flagged
 * primary, fall back to the platform's top-level location when none is
 * flagged (#1636), and 404 (EntityNotFoundException) only when neither is
 * available.
 */
@ExtendWith(MockitoExtension.class)
class PeopleAvailabilityServiceTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-01-15T10:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate TODAY = LocalDate.now(FIXED_CLOCK);
    private static final String USERNAME = "test.user";
    private static final UUID PERSON_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID PRIMARY_LOCATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final UUID SECONDARY_LOCATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000020");
    private static final UUID TOP_LEVEL_LOCATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000030");

    @Mock
    private EmployeeLocationAssignmentRepository assignmentRepository;

    @Mock
    private ExtPersonReplicaRepository extPersonReplicaRepository;

    @Mock
    private UserPersonTranslationService userPersonTranslationService;

    @Mock
    private ExtLocationReplicaRepository extLocationReplicaRepository;

    private PeopleAvailabilityServiceImpl service;

    @Mock
    private LocationReferenceService locationReferenceService;

    @BeforeEach
    void setUp() {
        service = new PeopleAvailabilityServiceImpl(
                assignmentRepository,
                extPersonReplicaRepository,
                userPersonTranslationService,
                FIXED_CLOCK,
                extLocationReplicaRepository,
                locationReferenceService);
    }

    private ExtLocationReplica topLevelReplica() {
        return ExtLocationReplica.builder()
                .locationId(TOP_LEVEL_LOCATION_ID)
                .name("HQ")
                .active(true)
                .build();
    }

    private EmployeeLocationAssignment assignment(UUID locationId, boolean primary) {
        return EmployeeLocationAssignment.builder()
                .employee(Employee.builder().personId(PERSON_ID).build())
                .locationId(locationId)
                .role("TECHNICIAN")
                .isPrimary(primary)
                .effectiveFrom(TODAY.minusDays(30))
                .build();
    }

    @Test
    void resolveCurrentUserPrimaryLocation_returnsPrimaryAssignmentLocation() {
        try (MockedStatic<SecurityContextHelper> helperMock = Mockito.mockStatic(SecurityContextHelper.class)) {
            helperMock.when(SecurityContextHelper::getCurrentUsername).thenReturn(Optional.of(USERNAME));
            when(userPersonTranslationService.getPersonUuidForUser(USERNAME)).thenReturn(PERSON_ID);
            when(assignmentRepository.findActiveByPersonIdAndDate(PERSON_ID, TODAY))
                    .thenReturn(
                            List.of(assignment(PRIMARY_LOCATION_ID, true), assignment(SECONDARY_LOCATION_ID, false)));
            when(locationReferenceService.findLocationName(PRIMARY_LOCATION_ID))
                    .thenReturn(Optional.of("Downtown Store"));

            PrimaryLocationResolution resolution = service.resolveCurrentUserPrimaryLocation();

            assertThat(resolution.locationId()).isEqualTo(PRIMARY_LOCATION_ID);
            assertThat(resolution.locationName()).isEqualTo("Downtown Store");
            assertThat(resolution.defaulted()).isFalse();
            verifyNoInteractions(extLocationReplicaRepository);
        }
    }

    @Test
    void resolveCurrentUserPrimaryLocation_returnsNullNameWhenReplicaHasNoMatchingRow() {
        try (MockedStatic<SecurityContextHelper> helperMock = Mockito.mockStatic(SecurityContextHelper.class)) {
            helperMock.when(SecurityContextHelper::getCurrentUsername).thenReturn(Optional.of(USERNAME));
            when(userPersonTranslationService.getPersonUuidForUser(USERNAME)).thenReturn(PERSON_ID);
            when(assignmentRepository.findActiveByPersonIdAndDate(PERSON_ID, TODAY))
                    .thenReturn(List.of(assignment(PRIMARY_LOCATION_ID, true)));
            when(locationReferenceService.findLocationName(PRIMARY_LOCATION_ID)).thenReturn(Optional.empty());

            PrimaryLocationResolution resolution = service.resolveCurrentUserPrimaryLocation();

            assertThat(resolution.locationId()).isEqualTo(PRIMARY_LOCATION_ID);
            assertThat(resolution.locationName()).isNull();
            assertThat(resolution.locationName()).isNotEqualTo(PRIMARY_LOCATION_ID.toString());
        }
    }

    @Test
    void resolveCurrentUserPrimaryLocation_defaultsToTopLevelWhenNoAssignmentIsPrimary() {
        try (MockedStatic<SecurityContextHelper> helperMock = Mockito.mockStatic(SecurityContextHelper.class)) {
            helperMock.when(SecurityContextHelper::getCurrentUsername).thenReturn(Optional.of(USERNAME));
            when(userPersonTranslationService.getPersonUuidForUser(USERNAME)).thenReturn(PERSON_ID);
            when(assignmentRepository.findActiveByPersonIdAndDate(PERSON_ID, TODAY))
                    .thenReturn(List.of(assignment(SECONDARY_LOCATION_ID, false)));
            when(extLocationReplicaRepository.findActiveHierarchyRoots()).thenReturn(List.of(topLevelReplica()));
            when(locationReferenceService.findLocationName(TOP_LEVEL_LOCATION_ID))
                    .thenReturn(Optional.of("HQ"));

            PrimaryLocationResolution resolution = service.resolveCurrentUserPrimaryLocation();

            assertThat(resolution.locationId()).isEqualTo(TOP_LEVEL_LOCATION_ID);
            assertThat(resolution.locationName()).isEqualTo("HQ");
            assertThat(resolution.defaulted()).isTrue();
        }
    }

    @Test
    void resolveCurrentUserPrimaryLocation_defaultsToTopLevelWhenNoActiveAssignments() {
        try (MockedStatic<SecurityContextHelper> helperMock = Mockito.mockStatic(SecurityContextHelper.class)) {
            helperMock.when(SecurityContextHelper::getCurrentUsername).thenReturn(Optional.of(USERNAME));
            when(userPersonTranslationService.getPersonUuidForUser(USERNAME)).thenReturn(PERSON_ID);
            when(assignmentRepository.findActiveByPersonIdAndDate(PERSON_ID, TODAY))
                    .thenReturn(List.of());
            when(extLocationReplicaRepository.findActiveHierarchyRoots()).thenReturn(List.of(topLevelReplica()));

            PrimaryLocationResolution resolution = service.resolveCurrentUserPrimaryLocation();

            assertThat(resolution.locationId()).isEqualTo(TOP_LEVEL_LOCATION_ID);
            assertThat(resolution.defaulted()).isTrue();
        }
    }

    @Test
    void resolveCurrentUserPrimaryLocation_defaultsToTopLevelWhenNoPersonLink() {
        try (MockedStatic<SecurityContextHelper> helperMock = Mockito.mockStatic(SecurityContextHelper.class)) {
            helperMock.when(SecurityContextHelper::getCurrentUsername).thenReturn(Optional.of(USERNAME));
            when(userPersonTranslationService.getPersonUuidForUser(USERNAME))
                    .thenThrow(new EntityNotFoundException("No person link found for username: " + USERNAME));
            when(extLocationReplicaRepository.findActiveHierarchyRoots()).thenReturn(List.of(topLevelReplica()));

            PrimaryLocationResolution resolution = service.resolveCurrentUserPrimaryLocation();

            assertThat(resolution.locationId()).isEqualTo(TOP_LEVEL_LOCATION_ID);
            assertThat(resolution.defaulted()).isTrue();
        }
    }

    @Test
    void resolveCurrentUserPrimaryLocation_throwsWhenNoAssignmentAndNoTopLevelFallback() {
        try (MockedStatic<SecurityContextHelper> helperMock = Mockito.mockStatic(SecurityContextHelper.class)) {
            helperMock.when(SecurityContextHelper::getCurrentUsername).thenReturn(Optional.of(USERNAME));
            when(userPersonTranslationService.getPersonUuidForUser(USERNAME)).thenReturn(PERSON_ID);
            when(assignmentRepository.findActiveByPersonIdAndDate(PERSON_ID, TODAY))
                    .thenReturn(List.of(assignment(SECONDARY_LOCATION_ID, false)));
            when(extLocationReplicaRepository.findActiveHierarchyRoots()).thenReturn(List.of());
            when(extLocationReplicaRepository.findFirstByActiveTrueOrderByLocationIdAsc())
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.resolveCurrentUserPrimaryLocation())
                    .isInstanceOf(EntityNotFoundException.class)
                    .hasMessageContaining("No primary location assignment");
        }
    }

    @Test
    void resolveCurrentUserPrimaryLocation_throwsWhenUserContextMissing() {
        try (MockedStatic<SecurityContextHelper> helperMock = Mockito.mockStatic(SecurityContextHelper.class)) {
            helperMock.when(SecurityContextHelper::getCurrentUsername).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.resolveCurrentUserPrimaryLocation())
                    .isInstanceOf(EntityNotFoundException.class)
                    .hasMessageContaining("user context is missing");
            verifyNoInteractions(extLocationReplicaRepository);
        }
    }

    @Test
    void resolvePrimaryLocationId_returnsPersonsPrimaryLocationWithName() {
        when(assignmentRepository.findActiveByPersonIdAndDate(PERSON_ID, TODAY))
                .thenReturn(List.of(assignment(PRIMARY_LOCATION_ID, true), assignment(SECONDARY_LOCATION_ID, false)));
        when(locationReferenceService.findLocationName(PRIMARY_LOCATION_ID)).thenReturn(Optional.of("Downtown Store"));

        PrimaryLocationResolution resolution = service.resolvePrimaryLocationId(PERSON_ID);

        assertThat(resolution.locationId()).isEqualTo(PRIMARY_LOCATION_ID);
        assertThat(resolution.locationName()).isEqualTo("Downtown Store");
        assertThat(resolution.defaulted()).isFalse();
    }

    @Test
    void resolvePrimaryLocationId_returnsNullNameWhenReplicaHasNoMatchingRow() {
        when(assignmentRepository.findActiveByPersonIdAndDate(PERSON_ID, TODAY))
                .thenReturn(List.of(assignment(PRIMARY_LOCATION_ID, true)));
        when(locationReferenceService.findLocationName(PRIMARY_LOCATION_ID)).thenReturn(Optional.empty());

        PrimaryLocationResolution resolution = service.resolvePrimaryLocationId(PERSON_ID);

        assertThat(resolution.locationId()).isEqualTo(PRIMARY_LOCATION_ID);
        assertThat(resolution.locationName()).isNull();
        assertThat(resolution.locationName()).isNotEqualTo(PRIMARY_LOCATION_ID.toString());
    }

    @Test
    void resolvePrimaryLocationId_throwsWhenNoActiveAssignmentIsPrimary() {
        when(assignmentRepository.findActiveByPersonIdAndDate(PERSON_ID, TODAY))
                .thenReturn(List.of(assignment(SECONDARY_LOCATION_ID, false)));

        assertThatThrownBy(() -> service.resolvePrimaryLocationId(PERSON_ID))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("No primary location assignment");
        verifyNoInteractions(locationReferenceService);
    }

    @Test
    void getPeopleAvailability_withoutLocation_fallsBackToAnyActiveAssignment() {
        try (MockedStatic<SecurityContextHelper> helperMock = Mockito.mockStatic(SecurityContextHelper.class)) {
            helperMock.when(SecurityContextHelper::getCurrentUsername).thenReturn(Optional.of(USERNAME));
            when(userPersonTranslationService.getPersonUuidForUser(USERNAME)).thenReturn(PERSON_ID);
            // Requester has only a non-primary assignment: availability still resolves
            // (unlike primary-location, which requires the primary flag).
            when(assignmentRepository.findActiveByPersonIdAndDate(PERSON_ID, TODAY))
                    .thenReturn(List.of(assignment(SECONDARY_LOCATION_ID, false)));
            when(assignmentRepository.findActiveByDateAndOptionalLocation(TODAY, SECONDARY_LOCATION_ID))
                    .thenReturn(List.of());

            assertThat(service.getPeopleAvailability(null, null)).isEmpty();
        }
    }
}
