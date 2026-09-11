package com.positivity.securityservice.internal.exception;

/**
 * A role that exists but may never be held by a user was named in a grant, reconcile or import
 * (ADR-0062 §7, WS2b-4) — {@code SUPPORT} today. Answered as 409 {@code ROLE_NOT_USER_ASSIGNABLE}.
 *
 * <p>Which roles those are is {@code ReservedRoles}' business, in {@code internal.domain}; this
 * type only reports the refusal, and deliberately does not reach back into that package (the
 * module's ArchUnit slice rule forbids the {@code exception → domain → exception} cycle).
 *
 * @see com.positivity.securityservice.internal.domain.ReservedRoles
 */
public class RoleNotUserAssignableException extends RuntimeException {

    private final String roleName;

    public RoleNotUserAssignableException(String roleName) {
        super("Role " + roleName + " cannot be assigned to a user: it is carried by a platform impersonation "
                + "token only (ADR-0062 §7). Mint a support token instead of granting the role.");
        this.roleName = roleName;
    }

    public String getRoleName() {
        return roleName;
    }
}
