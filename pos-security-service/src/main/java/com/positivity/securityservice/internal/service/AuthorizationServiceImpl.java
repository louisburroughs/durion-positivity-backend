package com.positivity.securityservice.internal.service;

import com.positivity.securityservice.internal.entity.Permission;
import com.positivity.securityservice.internal.entity.PrincipalRole;
import com.positivity.securityservice.internal.entity.Role;
import com.positivity.securityservice.internal.repository.PrincipalRoleRepository;
import com.positivity.securityservice.internal.repository.UserRepository;
import com.positivity.securityservice.service.AuthorizationService;
import java.util.Set;
import java.util.UUID;
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
    private final UserRepository userRepository;

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

    @Override
    @Transactional(readOnly = true)
    public Decision authorizePerson(@NonNull UUID personId, @NonNull String permissionKey) {
        return userRepository.findByPersonId(personId).stream()
                        .map(user -> user.getRoles())
                        .flatMap(Set::stream)
                        .map(Role::getPermissions)
                        .flatMap(Set::stream)
                        .map(Permission::getName)
                        .anyMatch(permissionKey::equals)
                ? Decision.ALLOW
                : Decision.DENY;
    }
}
