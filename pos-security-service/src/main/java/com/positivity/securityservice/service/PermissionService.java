package com.positivity.securityservice.service;

import com.positivity.securityservice.internal.dto.PermissionDto;
import com.positivity.securityservice.internal.dto.PermissionRegistrationRequest;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Service API for RBAC permission registration and query operations.
 *
 * Issue: #42
 */
public interface PermissionService {

    List<PermissionDto> registerPermissions(@NonNull PermissionRegistrationRequest request);

    PermissionDto getPermission(@NonNull UUID id);

    List<PermissionDto> getByDomain(@NonNull String domain);
}
