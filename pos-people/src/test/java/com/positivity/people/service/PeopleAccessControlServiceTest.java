package com.positivity.people.service;

import com.positivity.people.internal.client.SecurityServiceClient;
import com.positivity.people.internal.client.dto.RoleDto;
import com.positivity.people.internal.client.dto.UserRoleDto;
import com.positivity.people.internal.service.PeopleAccessControlServiceImpl;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PeopleAccessControlServiceTest {

    private SecurityServiceClient securityServiceClient;
    private UserPersonTranslationService userPersonTranslationService;
    private PeopleAccessControlService peopleAccessControlService;

    @BeforeEach
    void setUp() {
        securityServiceClient = mock(SecurityServiceClient.class);
        userPersonTranslationService = mock(UserPersonTranslationService.class);
        peopleAccessControlService = new PeopleAccessControlServiceImpl(userPersonTranslationService,
                securityServiceClient);
    }

    @Test
    void getAvailableRolesForPeople_combinesLocationAndGlobalRoles() {
        RoleDto locationRole = RoleDto.builder().code("MANAGER").scopeType("LOCATION").build();
        RoleDto globalRole = RoleDto.builder().code("ADMIN").scopeType("GLOBAL").build();
        when(securityServiceClient.getAvailableRoles("LOCATION")).thenReturn(List.of(locationRole));
        when(securityServiceClient.getAvailableRoles("GLOBAL")).thenReturn(List.of(globalRole));

        List<RoleDto> result = peopleAccessControlService.getAvailableRolesForPeople();

        assertEquals(2, result.size());
    }

    @Test
    void getPersonRoleAssignments_translatesPersonAndFetchesAssignments() {
        UUID personUuid = UUID.randomUUID();
        String userId = "user-123";
        UserRoleDto assignment = UserRoleDto.builder().roleCode("MANAGER").build();
        when(userPersonTranslationService.getUserIdForPerson(personUuid)).thenReturn(Optional.of(userId));
        when(securityServiceClient.getUserRoleAssignments(userId, true, null)).thenReturn(List.of(assignment));

        List<UserRoleDto> result = peopleAccessControlService.getPersonRoleAssignments(personUuid, true, null);

        assertEquals(1, result.size());
        verify(userPersonTranslationService).getUserIdForPerson(personUuid);
        verify(securityServiceClient).getUserRoleAssignments(userId, true, null);
    }

    @Test
    void assignRoleToPerson_translatesPersonAndCreatesAssignment() {
        UUID personUuid = UUID.randomUUID();
        String userId = "user-123";
        UUID locationId = UUID.randomUUID();
        UserRoleDto created = UserRoleDto.builder().roleCode("MANAGER").build();

        when(userPersonTranslationService.getUserIdForPerson(personUuid)).thenReturn(Optional.of(userId));
        when(securityServiceClient.assignRole(any())).thenReturn(created);

        UserRoleDto result = peopleAccessControlService.assignRoleToPerson(
                personUuid,
                "MANAGER",
                locationId,
                LocalDateTime.parse("2026-02-16T10:00:00"),
                null);

        assertEquals("MANAGER", result.getRoleCode());
        verify(securityServiceClient).assignRole(any());
    }

    @Test
    void revokeRoleFromPerson_callsSecurityClient() {
        UUID personUuid = UUID.randomUUID();
        String userId = "user-123";
        LocalDateTime endDate = LocalDateTime.parse("2026-02-16T11:00:00");
        when(userPersonTranslationService.getUserIdForPerson(personUuid)).thenReturn(Optional.of(userId));

        peopleAccessControlService.revokeRoleFromPerson(personUuid, "MANAGER", endDate);

        verify(securityServiceClient).revokeRole(userId, "MANAGER", endDate);
    }

    @Test
    void personMethods_throwWhenNoUserLinkExists() {
        UUID personUuid = UUID.randomUUID();
        when(userPersonTranslationService.getUserIdForPerson(personUuid)).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class,
                () -> peopleAccessControlService.getPersonRoleAssignments(personUuid, false, null));
    }
}