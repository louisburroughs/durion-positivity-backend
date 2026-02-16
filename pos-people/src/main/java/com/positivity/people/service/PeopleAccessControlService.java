package com.positivity.people.service;

import com.positivity.people.internal.client.dto.RoleDto;
import com.positivity.people.internal.client.dto.UserRoleDto;
import org.jspecify.annotations.NonNull;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface PeopleAccessControlService {

    @NonNull
    List<RoleDto> getAvailableRolesForPerson(@NonNull UUID personUuid);

    @NonNull
    List<UserRoleDto> getPersonRoleAssignments(@NonNull UUID personUuid, boolean includeHistory, LocalDateTime endDate);

    @NonNull
    UserRoleDto assignRoleToPerson(
            @NonNull UUID personUuid,
            @NonNull String roleCode,
            UUID locationId,
            LocalDateTime startDate,
            LocalDateTime endDate);

    void revokeRoleFromPerson(@NonNull UUID personUuid, @NonNull String roleCode, LocalDateTime endDate);
}