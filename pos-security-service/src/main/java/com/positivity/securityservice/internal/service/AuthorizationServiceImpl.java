package com.positivity.securityservice.internal.service;

import com.positivity.securityservice.internal.repository.UserRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Authorization decision service based on a user's effective role-permission grants.
 *
 * Issue: #42
 */
@Service
@RequiredArgsConstructor
public class AuthorizationServiceImpl implements AuthorizationService {

    private final UserRepository userRepository;
    private final EffectiveGrantResolver effectiveGrantResolver;

    @Override
    @Transactional(readOnly = true)
    public Decision authorizePerson(@NonNull UUID personId, @NonNull String permissionKey) {
        return userRepository
                        .findByPersonId(personId)
                        .map(effectiveGrantResolver::resolve)
                        .map(EffectiveGrantResolver.EffectiveGrants::permissionNames)
                        .map(permissionNames -> permissionNames.contains(permissionKey))
                        .orElse(false)
                ? Decision.ALLOW
                : Decision.DENY;
    }
}
