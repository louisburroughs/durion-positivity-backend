package com.positivity.supplier.internal.enums;

/**
 * Persistence mirror of {@code service.model.ProfileSourceOfTruth} (ADR-0050 §6): the
 * authoritative configuration source of a vendor profile.
 */
public enum ProfileSourceOfTruth {
    /** The deployment YAML is authoritative; admin mutations are rejected. */
    YAML,
    /** The admin API is authoritative; YAML reconciliation never touches the profile. */
    ADMIN
}
