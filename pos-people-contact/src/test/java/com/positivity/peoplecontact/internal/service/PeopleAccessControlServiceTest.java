package com.positivity.peoplecontact.internal.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.peoplecontact.internal.client.SecurityServiceClient;
import com.positivity.peoplecontact.internal.client.dto.RoleDto;
import com.positivity.peoplecontact.internal.client.dto.User;
import com.positivity.peoplecontact.internal.client.dto.UserRoleAssignmentRequest;
import com.positivity.peoplecontact.internal.client.dto.UserRoleDto;
import com.positivity.peoplecontact.internal.exception.PeopleContactValidationException;
import com.positivity.peoplecontact.internal.exception.PersonNotFoundException;
import com.positivity.peoplecontact.internal.repository.PersonRepository;
import jakarta.persistence.EntityNotFoundException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PeopleAccessControlServiceTest {

    private SecurityServiceClient securityServiceClient;

    private UserPersonTranslationService userPersonTranslationService;

    private PersonRepository personRepository;

    private PeopleAccessControlService peopleAccessControlService;

    // Fixed test values for deterministic tests
    private UUID testPersonId;

    private UUID testUserId;

    private String testUsername;

    @BeforeEach
    void setUp() {
        securityServiceClient = mock(SecurityServiceClient.class);
        userPersonTranslationService = mock(UserPersonTranslationService.class);
        personRepository = mock(PersonRepository.class);
        peopleAccessControlService = new PeopleAccessControlServiceImpl(
                userPersonTranslationService, securityServiceClient, personRepository);

        // Initialize fixed test values
        testPersonId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        testUserId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        testUsername = "some.user";
    }

    private User userWithId() {
        User user = new User();
        user.setId(testUserId);
        user.setUsername(testUsername);
        return user;
    }

    /**
     * {@code GET /v1/roles} takes no scope filter, so the old LOCATION-then-GLOBAL pair fetched
     * the same unfiltered catalog twice and concatenated it: a role picker showed every role
     * twice, and picking either copy assigned the same role.
     */
    @Test
    void getAvailableRolesForPerson_listsTheCatalogOnceFromASingleCall() {
        RoleDto manager = RoleDto.builder().name("MANAGER").build();
        RoleDto admin = RoleDto.builder().name("ADMIN").build();

        when(personRepository.existsById(testPersonId)).thenReturn(true);
        when(securityServiceClient.getAvailableRoles()).thenReturn(List.of(manager, admin));

        List<RoleDto> result = peopleAccessControlService.getAvailableRolesForPerson(testPersonId);

        assertEquals(
                List.of("MANAGER", "ADMIN"),
                result.stream().map(RoleDto::getCode).toList());
        verify(securityServiceClient, times(1)).getAvailableRoles();
        verify(personRepository).existsById(testPersonId);
    }

    @Test
    void getAvailableRolesForPerson_throwsWhenPersonNotFound() {
        when(personRepository.existsById(testPersonId)).thenReturn(false);

        assertThrows(
                PersonNotFoundException.class,
                () -> peopleAccessControlService.getAvailableRolesForPerson(testPersonId));
        verify(securityServiceClient, never()).getAvailableRoles();
    }

    @Test
    void getPersonRoleAssignments_translatesPersonAndFetchesAssignments() {
        UserRoleDto assignment = UserRoleDto.builder().roleCode("MANAGER").build();
        when(userPersonTranslationService.getUsernameForPerson(testPersonId)).thenReturn(Optional.of(testUsername));
        when(securityServiceClient.getUserByUsername(testUsername)).thenReturn(Optional.of(userWithId()));
        when(securityServiceClient.getUserRoleAssignments(testUserId, true)).thenReturn(List.of(assignment));

        List<UserRoleDto> result = peopleAccessControlService.getPersonRoleAssignments(testPersonId, true);

        assertEquals(1, result.size());
        verify(userPersonTranslationService).getUsernameForPerson(testPersonId);
        verify(securityServiceClient).getUserRoleAssignments(testUserId, true);
    }

    @Test
    void assignRoleToPerson_translatesPersonAndForwardsAnUnscopedEffectiveWindow() {
        UserRoleDto created = UserRoleDto.builder().roleCode("MANAGER").build();
        LocalDateTime startDate = LocalDateTime.parse("2026-02-16T10:00:00");

        when(userPersonTranslationService.getUsernameForPerson(testPersonId)).thenReturn(Optional.of(testUsername));
        when(securityServiceClient.getUserByUsername(testUsername)).thenReturn(Optional.of(userWithId()));
        when(securityServiceClient.assignRole(any())).thenReturn(created);

        UserRoleDto result = peopleAccessControlService.assignRoleToPerson(testPersonId, "MANAGER", startDate, null);

        assertEquals("MANAGER", result.getRoleCode());
        ArgumentCaptor<UserRoleAssignmentRequest> captor = ArgumentCaptor.forClass(UserRoleAssignmentRequest.class);
        verify(securityServiceClient).assignRole(captor.capture());
        UserRoleAssignmentRequest forwarded = captor.getValue();
        assertEquals(testUserId, forwarded.getUserId());
        assertEquals("MANAGER", forwarded.getRoleCode());
        // The person is translated to a security user and given an effective window; ADR-0061
        // leaves nothing else on an assignment, and the time of day survives untruncated.
        assertEquals(startDate, forwarded.getStartDate());
        assertNull(forwarded.getEndDate());
    }

    @Test
    void revokeRoleFromPerson_callsSecurityClient() {
        LocalDateTime endDate = LocalDateTime.parse("2026-02-16T11:00:00");
        when(userPersonTranslationService.getUsernameForPerson(testPersonId)).thenReturn(Optional.of(testUsername));
        when(securityServiceClient.getUserByUsername(testUsername)).thenReturn(Optional.of(userWithId()));

        peopleAccessControlService.revokeRoleFromPerson(testPersonId, "MANAGER", endDate);

        verify(securityServiceClient).revokeRole(testUserId, "MANAGER", endDate);
    }

    @Test
    void personMethods_throwWhenNoUserLinkExists() {
        when(userPersonTranslationService.getUsernameForPerson(testPersonId)).thenReturn(Optional.empty());

        assertThrows(
                EntityNotFoundException.class,
                () -> peopleAccessControlService.getPersonRoleAssignments(testPersonId, false));
    }

    @Test
    void assignRoleToPerson_throwsWhenEndDateBeforeStartDate() {
        LocalDateTime startDate = LocalDateTime.parse("2026-02-20T10:00:00");
        LocalDateTime endDate = LocalDateTime.parse("2026-02-15T10:00:00"); // before
        // startDate

        PeopleContactValidationException exception = assertThrows(
                PeopleContactValidationException.class,
                () -> peopleAccessControlService.assignRoleToPerson(testPersonId, "MANAGER", startDate, endDate));

        assertEquals("endDate must be greater than or equal to startDate", exception.getMessage());
    }
}
