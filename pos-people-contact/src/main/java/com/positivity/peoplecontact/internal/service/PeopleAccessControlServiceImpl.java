package com.positivity.peoplecontact.internal.service;

import com.positivity.peoplecontact.internal.client.SecurityServiceClient;
import com.positivity.peoplecontact.internal.client.dto.RoleDto;
import com.positivity.peoplecontact.internal.client.dto.UserRoleAssignmentRequest;
import com.positivity.peoplecontact.internal.client.dto.UserRoleDto;
import com.positivity.peoplecontact.internal.exception.PersonNotFoundException;
import com.positivity.peoplecontact.internal.repository.PersonRepository;
import com.positivity.peoplecontact.service.PeopleAccessControlService;
import com.positivity.peoplecontact.service.UserPersonTranslationService;
import jakarta.persistence.EntityNotFoundException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class PeopleAccessControlServiceImpl implements PeopleAccessControlService {

    private static final String LOCATION_SCOPE = "LOCATION";

    private static final String GLOBAL_SCOPE = "GLOBAL";

    private final UserPersonTranslationService userPersonTranslationService;

    private final SecurityServiceClient securityServiceClient;

    private final PersonRepository personRepository;

    public PeopleAccessControlServiceImpl(
            @NonNull UserPersonTranslationService userPersonTranslationService,
            @NonNull SecurityServiceClient securityServiceClient,
            @NonNull PersonRepository personRepository) {
        this.userPersonTranslationService = userPersonTranslationService;
        this.securityServiceClient = securityServiceClient;
        this.personRepository = personRepository;
    }

    @Override
    @NonNull
    @Transactional(readOnly = true)
    public List<RoleDto> getAvailableRolesForPerson(@NonNull UUID personUuid) {
        // Validate that the person exists
        if (!personRepository.existsById(personUuid)) {
            throw new PersonNotFoundException(personUuid);
        }

        List<RoleDto> allRoles = new ArrayList<>();
        allRoles.addAll(securityServiceClient.getAvailableRoles(LOCATION_SCOPE));
        allRoles.addAll(securityServiceClient.getAvailableRoles(GLOBAL_SCOPE));
        return allRoles;
    }

    @Override
    @NonNull
    @Transactional(readOnly = true)
    public List<UserRoleDto> getPersonRoleAssignments(
            @NonNull UUID personUuid, boolean includeHistory, LocalDateTime endDate) {
        UUID userId = resolveUserId(personUuid);
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
        validateDateWindow(startDate, endDate);
        UUID userId = resolveUserId(personUuid);
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
        UUID userId = resolveUserId(personUuid);
        securityServiceClient.revokeRole(userId, roleCode, endDate);
    }

    @NonNull
    private UUID resolveUserId(@NonNull UUID personUuid) {
        String username = userPersonTranslationService
                .getUsernameForPerson(personUuid)
                .orElseThrow(() -> new EntityNotFoundException("No user link found for personUuid: " + personUuid));
        return securityServiceClient
                .getUserByUsername(username)
                .map(com.positivity.peoplecontact.internal.client.dto.User::getId)
                .orElseThrow(() -> new EntityNotFoundException("No security user found for username: " + username));
    }

    private void validateDateWindow(LocalDateTime startDate, LocalDateTime endDate) {
        if (startDate != null && endDate != null && endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("endDate must be greater than or equal to startDate");
        }
    }
}
