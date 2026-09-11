package com.positivity.securityservice.internal.domain;

import com.positivity.securityservice.internal.enums.PermissionCode;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * The read-only ceiling of an impersonation token (ADR-0062 §7, plan WS2b-4).
 *
 * <p>The {@code SUPPORT} template role is seeded read-only, but a template role's grants stay
 * editable through the role-permission admin API (only its deletion is refused), so the role a
 * tenant holds is not a boundary the platform can rely on: an administrator could grant
 * {@code security:user:delete} to {@code SUPPORT} and the next impersonation token would carry
 * it. This ceiling is applied at mint time instead: {@code perm_bits} is the intersection of the
 * tenant's {@code SUPPORT} grants with what this class {@link #admits admits}, and whatever was
 * dropped is logged and audited. A widened role can therefore never yield a write-capable token.
 *
 * <p>The rule is the one the seed follows ({@code R__seed_role_permissions.sql}, SUPPORT bullet,
 * pinned by {@code RolePermissionBaselineTest.supportIsReadOnly}): a permission is admitted when
 * its action is {@code view} or {@code read} (or it is {@code location:read}, which has no resource
 * segment), unless it is a {@code platform:*} permission or one of the {@link #EXCLUDED explicit
 * exclusions}. Rule-based rather than a pinned list on purpose: a read permission added to a floor
 * role later is admitted without a code change here, while a write can never be.
 */
public final class SupportReadOnlyCeiling {

    /**
     * Reads the ceiling refuses even though their action is a read: employee PII (home addresses,
     * emergency contacts), the synthetic principal's non-existent "self", other principals' NLTI
     * request history, and the MCP administration and evaluation surfaces.
     */
    public static final Set<String> EXCLUDED = Set.of(
            "people:employee_pii:view",
            "people:self:view",
            "nlti:audit:read",
            "nlti:request:read",
            "mcp:eval_trace:view",
            "mcp:llm_api:view",
            "mcp:system_prompt:view",
            "mcp:tool:view");

    /**
     * One segment of a permission code, exactly as the catalog defines it (see {@code
     * PermissionRegistryServiceImpl.PERMISSION_PATTERN}): a letter of either case followed by
     * mixed-case alphanumerics, underscores and hyphens. Spelling this the catalog's way is
     * load-bearing — an all-lowercase segment silently refused the camelCase reads the catalog
     * really contains ({@code people:timeAdjustment:view}, {@code people:timeEntry:view}, {@code
     * people:timeException:view}, {@code people-contact:userLink:view}, {@code
     * bulkImport:status:read}), dropping them from every impersonation token.
     */
    public static final String SEGMENT = "[A-Za-z][A-Za-z0-9_-]*";

    private static final Pattern READ_ACTION = Pattern.compile(SEGMENT + ":" + SEGMENT + ":(view|read)");

    private SupportReadOnlyCeiling() {}

    /** Whether {@code code} may travel on an impersonation token. */
    public static boolean admits(@Nullable String code) {
        if (code == null || code.startsWith("platform:") || EXCLUDED.contains(code)) {
            return false;
        }
        return "location:read".equals(code) || READ_ACTION.matcher(code).matches();
    }

    /**
     * The permissions of a role that pass the ceiling, and the codes of those that did not.
     *
     * @param admitted the permissions to encode into {@code perm_bits}
     * @param dropped  the codes refused, sorted, for the log and the audit event; empty when the
     *                 role is exactly as seeded
     */
    public record Result(
            @NonNull Set<PermissionCode> admitted, @NonNull Set<String> dropped) {}

    /**
     * Partitions {@code granted} into what an impersonation token may carry and what it may not.
     *
     * @param granted the role's resolved permissions, as held in the tenant
     * @return the admitted permissions and the dropped codes
     */
    public static @NonNull Result apply(@NonNull Set<PermissionCode> granted) {
        Set<PermissionCode> admitted = new TreeSet<>();
        Set<String> dropped = new TreeSet<>();
        for (PermissionCode permission : granted) {
            if (admits(permission.code())) {
                admitted.add(permission);
            } else {
                dropped.add(permission.code());
            }
        }
        // Unmodifiable views of the sorted sets: the order is part of the contract (log lines and
        // audit context read the same way every time).
        return new Result(Collections.unmodifiableSet(admitted), Collections.unmodifiableSet(dropped));
    }
}
