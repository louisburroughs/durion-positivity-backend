package com.positivity.people.internal.service;

import com.positivity.people.internal.client.SecurityServiceClient;
import com.positivity.people.internal.client.dto.RoleDto;
import com.positivity.people.internal.client.dto.UserRoleAssignmentRequest;
import com.positivity.people.internal.client.dto.UserRoleDto;
import com.positivity.people.service.PeopleAccessControlService;
import com.positivity.people.service.UserPersonTranslationService;
import jakarta.persistence.EntityNotFoundException;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class PeopleAccessControlServiceImpl implements PeopleAccessControlService {

    private static final String LOCATION_SCOPE = "LOCATION";
    private static final String GLOBAL_SCOPE = "GLOBAL";

    private final UserPersonTranslationService userPersonTranslationService;
    private final SecurityServiceClient securityServiceClient;

    public PeopleAccessControlServiceImpl(
            @NonNull UserPersonTranslationService userPersonTranslationService,
            @NonNull SecurityServiceClient securityServiceClient) {
        this.userPersonTranslationService = userPersonTranslationService;
        this.securityServiceClient = securityServiceClient;
    }

    @Override
    @NonNull
    @Transactional(readOnly = true)
    public List<RoleDto> getAvailableRolesForPeople() {
        List<RoleDto> allRoles = new ArrayList<>();
        allRoles.addAll(securityServiceClient.getAvailableRoles(LOCATION_SCOPE));
        allRoles.addAll(securityServiceClient.getAvailableRoles(GLOBAL_SCOPE));
        return allRoles;
    }

    @Override
    @NonNull
    @Transactional(readOnly = true)
    public List<UserRoleDto> getPersonRoleAssignments(@NonNull UUID personUuid, Boolean includeHistory,
            LocalDateTime endDate) {
        String userId = resolveUserId(personUuid);
        return securityServiceClient.getUserRoleAssignments(userId, includeHistory, endDate);
    }

    @Override
    @NonNull
    public UserRoleDto assignRoleToPerson(
            @NonNull UUID personUuid,
            @NonNull String roleCode,
            UUID locationId,
            LocalDateTime startDate,
            LocalDateTime endDate) {
        String userId = resolveUserId(personUuid);
        UserRoleAssignmentRequest request = UserRoleAssignmentRequest.builder()
                .userId(userId)
                .roleCode(roleCode)
                .locationId(locationId)
                .startDate(startDate)
                .endDate(endDate)
                .build();
        return securityServiceClient.assignRole(request);
    }

    @Override
    public void revokeRoleFromPerson(@NonNull UUID personUuid, @NonNull String roleCode, LocalDateTime endDate) {
        String userId = resolveUserId(personUuid);
        securityServiceClient.revokeRole(userId, roleCode, endDate);
    }

    @NonNull
    private String resolveUserId(@NonNull UUID personUuid) {
        return userPersonTranslationService.getUserIdForPerson(personUuid)
                .orElseThrow(() -> new EntityNotFoundException("No user link found for personUuid: " + personUuid));
    }
}