package com.positivity.catalog.internal.spi.model;

import org.jspecify.annotations.NonNull;

/**
 * Identity and capability declaration of one labor-time source (sourcing plan §3.3). Drives
 * resolution precedence defaults and — decisively — the licensing mode: STORE sources feed the
 * chunked import into {@code service_labor_standard}; QUERY_ONLY sources are consulted live with
 * a bounded cache and never persisted (ADR-0058 §4).
 *
 * @param sourceCode stable provenance code, e.g. {@code MOCKGUIDE}
 * @param displayName human-readable source name for curation screens
 * @param licenseMode what the source's license permits
 * @param defaultPrecedence resolution tie-break when no {@code labor_time_source_policy} row
 *     exists for a (timeType, source) pair; lower wins
 */
public record LaborTimeProviderDescriptor(
        @NonNull String sourceCode,
        @NonNull String displayName,
        @NonNull LicenseMode licenseMode,
        int defaultPrecedence) {

    /** License-shaped ingestion mode (sourcing plan §5.1). */
    public enum LicenseMode {
        /** Feed may be persisted: rows enter {@code service_labor_standard} via chunked import. */
        STORE,
        /** Persistence forbidden: live lookups only, TTL cache, never written to the DB. */
        QUERY_ONLY
    }
}
