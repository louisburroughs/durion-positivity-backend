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
     * user's directly-assigned roles ({@code User.roles} / {@code user_roles}) and
     * their permissions.
     *
     * <p>Used by manager-approval-by-employee-number flows where the approver is not
     * the authenticated caller, so their authorities are not present in the request
     * security context. Returns {@code DENY} when no user is linked to the person.
     *
     * <p><b>Model note:</b> this intentionally resolves via {@code User.roles} rather
     * than {@link #authorize(String, String)}'s {@code principal_roles}. {@code personId}
     * maps cleanly to a {@code User} (FK), whereas {@code principalId} is a free-form
     * string with no guaranteed mapping from {@code personId}, so routing through the
     * principal model is not safely possible here. Operationally, the roles that carry
     * the queried permission must therefore be assigned to the user via the user-role
     * admin API (populating {@code user_roles}).
     */
    Decision authorizePerson(@NonNull UUID personId, @NonNull String permissionKey);

    enum Decision {
        ALLOW,
        DENY
    }
}
