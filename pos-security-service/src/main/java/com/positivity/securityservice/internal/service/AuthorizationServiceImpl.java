package com.positivity.securityservice.internal.service;

import com.positivity.securityservice.internal.entity.Permission;
import com.positivity.securityservice.internal.entity.PrincipalRole;
import com.positivity.securityservice.internal.entity.Role;
import com.positivity.securityservice.internal.repository.PrincipalRoleRepository;
import com.positivity.securityservice.service.AuthorizationService;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Authorization decision service based on principal-role-permission
 * relationships.
 *
 * Issue: #42
 */
@Service
@RequiredArgsConstructor
public class AuthorizationServiceImpl implements AuthorizationService {

    private final PrincipalRoleRepository principalRoleRepository;

    @Override
    @Transactional(readOnly = true)
    public Decision authorize(@NonNull String principalId, @NonNull String permissionKey) {
        return principalRoleRepository.findByPrincipalId(principalId).stream()
                        .map(PrincipalRole::getRole)
                        .map(Role::getPermissions)
                        .flatMap(Set::stream)
                        .map(Permission::getName)
                        .anyMatch(permissionKey::equals)
                ? Decision.ALLOW
                : Decision.DENY;
    }
}
