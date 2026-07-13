package com.positivity.people.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.positivity.people.internal.entity.Employee;
import com.positivity.people.internal.entity.EmployeeLocationAssignment;
import com.positivity.people.internal.repository.EmployeeLocationAssignmentRepository;
import com.positivity.people.internal.repository.ExtPersonReplicaRepository;
import com.positivity.people.internal.service.PeopleAvailabilityServiceImpl;
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
 * primary, and 404 (EntityNotFoundException) when none is flagged.
 */
@ExtendWith(MockitoExtension.class)
class PeopleAvailabilityServiceTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-01-15T10:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate TODAY = LocalDate.now(FIXED_CLOCK);
    private static final String USERNAME = "test.user";
    private static final UUID PERSON_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID PRIMARY_LOCATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final UUID SECONDARY_LOCATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000020");

    @Mock
    private EmployeeLocationAssignmentRepository assignmentRepository;

    @Mock
    private ExtPersonReplicaRepository extPersonReplicaRepository;

    @Mock
    private UserPersonTranslationService userPersonTranslationService;

    private PeopleAvailabilityServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PeopleAvailabilityServiceImpl(
                assignmentRepository, extPersonReplicaRepository, userPersonTranslationService, FIXED_CLOCK);
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
    void resolveCurrentUserPrimaryLocationId_returnsPrimaryAssignmentLocation() {
        try (MockedStatic<SecurityContextHelper> helperMock = Mockito.mockStatic(SecurityContextHelper.class)) {
            helperMock.when(SecurityContextHelper::getCurrentUsername).thenReturn(Optional.of(USERNAME));
            when(userPersonTranslationService.getPersonUuidForUser(USERNAME)).thenReturn(PERSON_ID);
            when(assignmentRepository.findActiveByPersonIdAndDate(PERSON_ID, TODAY))
                    .thenReturn(
                            List.of(assignment(PRIMARY_LOCATION_ID, true), assignment(SECONDARY_LOCATION_ID, false)));

            assertThat(service.resolveCurrentUserPrimaryLocationId()).isEqualTo(PRIMARY_LOCATION_ID);
        }
    }

    @Test
    void resolveCurrentUserPrimaryLocationId_throwsWhenNoAssignmentIsPrimary() {
        try (MockedStatic<SecurityContextHelper> helperMock = Mockito.mockStatic(SecurityContextHelper.class)) {
            helperMock.when(SecurityContextHelper::getCurrentUsername).thenReturn(Optional.of(USERNAME));
            when(userPersonTranslationService.getPersonUuidForUser(USERNAME)).thenReturn(PERSON_ID);
            when(assignmentRepository.findActiveByPersonIdAndDate(PERSON_ID, TODAY))
                    .thenReturn(List.of(assignment(SECONDARY_LOCATION_ID, false)));

            assertThatThrownBy(() -> service.resolveCurrentUserPrimaryLocationId())
                    .isInstanceOf(EntityNotFoundException.class)
                    .hasMessageContaining("No primary location assignment");
        }
    }

    @Test
    void resolveCurrentUserPrimaryLocationId_throwsWhenNoActiveAssignments() {
        try (MockedStatic<SecurityContextHelper> helperMock = Mockito.mockStatic(SecurityContextHelper.class)) {
            helperMock.when(SecurityContextHelper::getCurrentUsername).thenReturn(Optional.of(USERNAME));
            when(userPersonTranslationService.getPersonUuidForUser(USERNAME)).thenReturn(PERSON_ID);
            when(assignmentRepository.findActiveByPersonIdAndDate(PERSON_ID, TODAY))
                    .thenReturn(List.of());

            assertThatThrownBy(() -> service.resolveCurrentUserPrimaryLocationId())
                    .isInstanceOf(EntityNotFoundException.class)
                    .hasMessageContaining("No primary location assignment");
        }
    }

    @Test
    void resolveCurrentUserPrimaryLocationId_throwsWhenUserContextMissing() {
        try (MockedStatic<SecurityContextHelper> helperMock = Mockito.mockStatic(SecurityContextHelper.class)) {
            helperMock.when(SecurityContextHelper::getCurrentUsername).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.resolveCurrentUserPrimaryLocationId())
                    .isInstanceOf(EntityNotFoundException.class)
                    .hasMessageContaining("user context is missing");
        }
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
