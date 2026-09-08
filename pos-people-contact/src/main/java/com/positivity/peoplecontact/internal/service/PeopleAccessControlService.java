package com.positivity.peoplecontact.internal.service;

import com.positivity.peoplecontact.internal.client.dto.RoleDto;
import com.positivity.peoplecontact.internal.client.dto.UserRoleDto;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public interface PeopleAccessControlService {

    @NonNull
    List<RoleDto> getAvailableRolesForPerson(@NonNull UUID personUuid);

    @NonNull
    List<UserRoleDto> getPersonRoleAssignments(
            @NonNull UUID personUuid, boolean includeHistory, @Nullable LocalDateTime endDate);

    @NonNull
    UserRoleDto assignRoleToPerson(
            @NonNull UUID personUuid,
            @NonNull String roleCode,
            @Nullable LocalDateTime startDate,
            @Nullable LocalDateTime endDate);

    void revokeRoleFromPerson(@NonNull UUID personUuid, @NonNull String roleCode, @Nullable LocalDateTime endDate);
}
