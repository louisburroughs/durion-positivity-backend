package com.positivity.securityservice.service;

import org.jspecify.annotations.NonNull;

/**
 * Service API for principal authorization decisions.
 *
 * Issue: #42
 */
public interface AuthorizationService {

    Decision authorize(@NonNull String principalId, @NonNull String permissionKey);

    enum Decision {
        ALLOW,
        DENY
    }
}
