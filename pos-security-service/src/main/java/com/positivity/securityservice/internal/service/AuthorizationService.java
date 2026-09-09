package com.positivity.securityservice.internal.service;

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
     * Authorization decision for the user backing a person, evaluated against the user's
     * currently effective roles (effective-dated assignments, honouring the window), the same
     * set token issuance uses — resolved through {@link EffectiveGrantResolver} (ADR-0061
     * amendment, 2026-09-09, #1914).
     *
     * <p>Used by manager-approval-by-employee-number flows where the approver is not
     * the authenticated caller, so their authorities are not present in the request
     * security context. Returns {@code DENY} when no user is linked to the person.
     *
     * <p><b>Model note:</b> this intentionally resolves via {@link EffectiveGrantResolver} rather
     * than {@link #authorize(String, String)}'s {@code principal_roles}. {@code personId}
     * maps cleanly to a {@code User} (FK), whereas {@code principalId} is a free-form
     * string with no guaranteed mapping from {@code personId}, so routing through the
     * principal model is not safely possible here. Operationally, the roles that carry
     * the queried permission must therefore be assigned to the user through an effective-dated
     * {@code role_assignments} row — the only store of a user's roles since ADR-0061 amendment
     * phase 2 (#1914).
     */
    Decision authorizePerson(@NonNull UUID personId, @NonNull String permissionKey);

    enum Decision {
        ALLOW,
        DENY
    }
}
