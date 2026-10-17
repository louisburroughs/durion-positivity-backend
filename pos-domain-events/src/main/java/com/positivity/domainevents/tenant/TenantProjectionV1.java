package com.positivity.domainevents.tenant;

import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * The tenant projection every lifecycle fact carries (ADR-0062 §7). {@code locale} (BCP 47, CAP:550 S44, #2618) is
 * additive: a fact published before it predates the field, and a consumer that has not learned it treats it as
 * unknown (ADR-0030 §2 falls back to {@code en-US}). Schema version 1 is unchanged (ADR-0044 §3).
 */
public record TenantProjectionV1(
        @NonNull UUID tenantId,
        @NonNull String slug,
        @Nullable String displayName,
        @NonNull String status,
        @Nullable String locale) {

    public static final int SCHEMA_VERSION = 1;

    public TenantProjectionV1 {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId must not be null");
        }
        if (slug == null || slug.isBlank()) {
            throw new IllegalArgumentException("slug must not be blank");
        }
        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException("status must not be blank");
        }
    }
}
