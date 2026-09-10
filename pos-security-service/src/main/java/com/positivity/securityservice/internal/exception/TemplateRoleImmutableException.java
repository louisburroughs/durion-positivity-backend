package com.positivity.securityservice.internal.exception;

/**
 * A role provisioned from the platform role template cannot be deleted (ADR-0062 §6): its
 * canonical name is immutable for the life of the tenant. Answered as 409
 * {@code ROLE_TEMPLATE_IMMUTABLE}.
 */
public class TemplateRoleImmutableException extends RuntimeException {

    public TemplateRoleImmutableException(String roleName, String templateKey) {
        super("Role " + roleName + " was provisioned from the platform template (" + templateKey
                + ") and cannot be deleted; change its grants instead");
    }
}
