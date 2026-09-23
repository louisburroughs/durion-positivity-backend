package com.positivity.people.internal.exception;

/**
 * A job-role code already used by another job role in the calling tenant (durion#2157). The code
 * is unique per tenant, case-insensitively ({@code job_role_tenant_code_ci_key}, V8), not across
 * the platform -- two tenants may each have their own {@code LEAD_TECH}.
 */
public class DuplicateJobRoleCodeException extends SemanticValidationException {
    public DuplicateJobRoleCodeException(String code) {
        super("Job role code '" + code + "' is already in use");
    }
}
