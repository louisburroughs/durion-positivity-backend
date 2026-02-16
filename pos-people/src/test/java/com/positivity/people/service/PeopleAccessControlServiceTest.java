package com.positivity.people.service;

import com.positivity.people.internal.client.SecurityServiceClient;
import com.positivity.people.internal.client.dto.Role;
import com.positivity.people.internal.client.dto.RoleAssignment;
import com.positivity.people.internal.client.dto.RoleAssignmentRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        peopleAccessControlService = new PeopleAccessControlService(securityServiceClient,
                userPersonTranslationService);
    }

    @Test
    void getRolesForPerson_returnsAllRoles() {
        Role role = new Role(UUID.randomUUID(), "MANAGER", "Manager role");
        when(securityServiceClient.getAllRoles()).thenReturn(List.of(role));

        List<Role> result = peopleAccessControlService.getRolesForPerson();

        assertEquals(1, result.size());
        assertEquals("MANAGER", result.get(0).getName());
    }

    @Test
    void getAssignmentsForPerson_translatesPersonAndFetchesAssignments() {
        UUID personId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        RoleAssignment assignment = new RoleAssignment();
        assignment.setId(UUID.randomUUID());
        when(userPersonTranslationService.getUserIdByPersonId(personId)).thenReturn(userId);
        when(securityServiceClient.getAssignmentsForUser(userId, true)).thenReturn(List.of(assignment));

        List<RoleAssignment> result = peopleAccessControlService.getAssignmentsForPerson(personId, true);

        assertEquals(1, result.size());
        verify(userPersonTranslationService).getUserIdByPersonId(personId);
        verify(securityServiceClient).getAssignmentsForUser(userId, true);
    }

    @Test
    void createAssignmentForPerson_translatesPersonAndSetsUserId() {
        UUID personId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        RoleAssignmentRequest request = new RoleAssignmentRequest();
        request.setRoleId(UUID.randomUUID());
        RoleAssignment created = new RoleAssignment();
        created.setId(UUID.randomUUID());

        when(userPersonTranslationService.getUserIdByPersonId(personId)).thenReturn(userId);
        when(securityServiceClient.createRoleAssignment(any(RoleAssignmentRequest.class))).thenReturn(created);

        RoleAssignment result = peopleAccessControlService.createAssignmentForPerson(personId, request);

        assertEquals(created.getId(), result.getId());
        ArgumentCaptor<RoleAssignmentRequest> requestCaptor = ArgumentCaptor.forClass(RoleAssignmentRequest.class);
        verify(securityServiceClient).createRoleAssignment(requestCaptor.capture());
        assertEquals(userId, requestCaptor.getValue().getUserId());
        assertEquals(request.getRoleId(), requestCaptor.getValue().getRoleId());
    }

    @Test
    void revokeAssignmentForPerson_callsSecurityClient() {
        UUID assignmentId = UUID.randomUUID();
        LocalDate endDate = LocalDate.now();

        peopleAccessControlService.revokeAssignmentForPerson(assignmentId, endDate);

        verify(securityServiceClient).revokeRoleAssignment(assignmentId, endDate);
    }
}