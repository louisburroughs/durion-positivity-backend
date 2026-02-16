package com.positivity.people.service;

import com.positivity.people.internal.client.SecurityServiceClient;
import com.positivity.people.internal.client.dto.Role;
import com.positivity.people.internal.client.dto.RoleAssignment;
import com.positivity.people.internal.client.dto.RoleAssignmentRequest;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
public class PeopleAccessControlService {

    private final SecurityServiceClient securityServiceClient;
    private final UserPersonTranslationService userPersonTranslationService;

    public PeopleAccessControlService(
            @NonNull SecurityServiceClient securityServiceClient,
            @NonNull UserPersonTranslationService userPersonTranslationService) {
        this.securityServiceClient = securityServiceClient;
        this.userPersonTranslationService = userPersonTranslationService;
    }

    @NonNull
    @Transactional(readOnly = true)
    public List<Role> getRolesForPerson() {
        return securityServiceClient.getAllRoles();
    }

    @NonNull
    @Transactional(readOnly = true)
    public List<RoleAssignment> getAssignmentsForPerson(@NonNull UUID personId, boolean includeHistory) {
        UUID userId = userPersonTranslationService.getUserIdByPersonId(personId);
        return securityServiceClient.getAssignmentsForUser(userId, includeHistory);
    }

    @NonNull
    @Transactional
    public RoleAssignment createAssignmentForPerson(
            @NonNull UUID personId,
            @NonNull RoleAssignmentRequest request) {
        UUID userId = userPersonTranslationService.getUserIdByPersonId(personId);
        request.setUserId(userId);
        return securityServiceClient.createRoleAssignment(request);
    }

    @Transactional
    public void revokeAssignmentForPerson(@NonNull UUID assignmentId, @NonNull LocalDate endDate) {
        securityServiceClient.revokeRoleAssignment(assignmentId, endDate);
    }
}