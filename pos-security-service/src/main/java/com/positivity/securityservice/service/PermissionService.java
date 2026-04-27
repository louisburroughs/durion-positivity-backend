package com.positivity.securityservice.service;

import com.positivity.securityservice.internal.dto.PermissionDto;
import com.positivity.securityservice.internal.dto.PermissionRegistrationRequest;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Service API for RBAC permission registration and query operations.
 *
 * Issue: #42
 */
public interface PermissionService {

    List<PermissionDto> registerPermissions(@NonNull PermissionRegistrationRequest request);

    PermissionDto getPermission(@NonNull UUID id);

    List<PermissionDto> getByDomain(@NonNull String domain);

    /**
     * Returns a paged list of registered permissions, optionally filtered by
     * domain.
     *
     * @param domain   optional domain filter; null returns all permissions
     * @param pageable pagination and sort specification
     * @return page of matching permissions
     */
    @NonNull
    Page<PermissionDto> listPermissions(@Nullable String domain, @NonNull Pageable pageable);
}
