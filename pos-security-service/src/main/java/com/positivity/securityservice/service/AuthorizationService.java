package com.positivity.securityservice.service;

import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Service API for principal authorization decisions.
 *
 * Issue: #42
 */
public interface AuthorizationService {

    Decision authorize(@NonNull String principalId, @NonNull String permissionKey);

    /**
     * Authorization decision for the user backing a person, evaluated against that
     * user's directly-assigned roles ({@code User.roles}) and their permissions.
     *
     * <p>Used by manager-approval-by-employee-number flows where the approver is not
     * the authenticated caller, so their authorities are not present in the request
     * security context. Returns {@code DENY} when no user is linked to the person.
     */
    Decision authorizePerson(@NonNull UUID personId, @NonNull String permissionKey);

    enum Decision {
        ALLOW,
        DENY
    }
}
