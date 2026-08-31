package com.positivity.mcp.internal.domain;

import java.util.Locale;
import org.jspecify.annotations.NonNull;

/**
 * The single place the {@code ROLE_} prefix convention is applied in this service (#1613, D3).
 *
 * <p>The convention was previously restated in three modules with no shared normalization point:
 * {@code pos-security-service} stores role names unprefixed and upper-case, {@code pos-api-gateway}
 * adds the prefix when it emits authorities, and {@code pos-mcp-server} hardcoded the prefixed form
 * in string constants that doubled as {@code system_prompts} keys. Those constants are gone; role
 * names now arrive unprefixed from the persona sync, and every conversion to the authority form
 * that {@code Authentication} produces goes through here.
 */
public final class RoleAuthorities {

    public static final String ROLE_PREFIX = "ROLE_";

    /**
     * Converts a stored role name to the authority form used as a {@code system_prompts} key and
     * compared against {@code Authentication} authorities.
     *
     * <p>Already-prefixed input is accepted and upper-cased rather than double-prefixed: nothing
     * stops a role being created through the admin API as {@code ROLE_MANAGER}, and
     * {@code ROLE_ROLE_MANAGER} would match no caller and be invisible rather than loudly wrong.
     */
    public static @NonNull String toAuthority(@NonNull String roleName) {
        String normalized = roleName.strip().toUpperCase(Locale.ROOT);
        return normalized.startsWith(ROLE_PREFIX) ? normalized : ROLE_PREFIX + normalized;
    }

    /** Inverse of {@link #toAuthority}: the canonical storage form in {@code pos-security-service}. */
    public static @NonNull String toRoleName(@NonNull String authority) {
        String normalized = authority.strip().toUpperCase(Locale.ROOT);
        return normalized.startsWith(ROLE_PREFIX) ? normalized.substring(ROLE_PREFIX.length()) : normalized;
    }

    private RoleAuthorities() {}
}
