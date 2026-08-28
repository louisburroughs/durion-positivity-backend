package com.positivity.supplier.internal.service.model;

/**
 * Authoritative configuration source of a vendor profile (ADR-0050 §6).
 *
 * <p>{@code YAML}-managed profiles are reconciled from deployment configuration on every
 * startup and reject admin create/update/delete; {@code ADMIN}-managed profiles are owned by
 * the admin API and untouched by YAML reconciliation.
 */
public enum ProfileSourceOfTruth {
    /** The deployment YAML is authoritative; admin mutations are rejected. */
    YAML,
    /** The admin API is authoritative; YAML reconciliation never touches the profile. */
    ADMIN
}
