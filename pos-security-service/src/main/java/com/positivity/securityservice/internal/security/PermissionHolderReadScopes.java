package com.positivity.securityservice.internal.security;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Which permission codes a caller may ask the permission-holders read about when it does not hold
 * {@link SecurityPermissions#ROLE_VIEW} (#2669, decision D2 of the Security &amp; Authorization
 * Domain).
 *
 * <p>The rule: a person who manages a policy may see which roles hold the permissions that policy
 * governs, and nothing else. A policy manager cannot use the read to map the rest of the tenant's
 * grants, for example which role holds {@code security:role:edit}.
 *
 * <p>The map holds one entry, the AP approval policy. Adding an entry is a security-domain decision
 * and needs a story reviewed by that domain; this map is deliberately code, not configuration.
 *
 * <p>The codes are cross-domain references (pos-accounting owns them). They are named here, rather
 * than imported, because pos-security-service does not depend on pos-accounting; the permission
 * tooling resolves the qualified constant in {@code @PreAuthorize} to the catalog entry that
 * already exists, so no bit and no {@code CATALOG_VERSION} change.
 */
public final class PermissionHolderReadScopes {

    /** Approve an AP bill within the approver's limit. */
    public static final String AP_APPROVE = "accounting:ap:approve";

    /** Approve an AP bill above the approver's limit. */
    public static final String AP_APPROVE_OVER_LIMIT = "accounting:ap:approve_over_limit";

    /** Reject an AP bill. */
    public static final String AP_REJECT = "accounting:ap:reject";

    /** Pay an approved AP bill. */
    public static final String AP_PAY = "accounting:ap:pay";

    /** Manage the AP approval policy; the governing permission of the one scope entry. */
    public static final String AP_APPROVAL_POLICY_MANAGE = "accounting:ap_approval_policy:manage";

    private static final Map<String, Set<String>> SCOPES = Map.of(
            AP_APPROVAL_POLICY_MANAGE,
            Set.of(AP_APPROVE, AP_APPROVE_OVER_LIMIT, AP_REJECT, AP_PAY, AP_APPROVAL_POLICY_MANAGE));

    private PermissionHolderReadScopes() {
        // Utility class - prevent instantiation
    }

    /**
     * The scope map: governing permission to the codes a holder of it may ask about.
     *
     * @return an immutable view of the map
     */
    public static Map<String, Set<String>> scopes() {
        return SCOPES;
    }

    /**
     * The codes a caller holding {@code authorities} may ask about through a scope entry: the union
     * of the entries whose governing permission the caller holds. Empty when it holds none, so a
     * caller with no scope is refused every code (deny by default).
     *
     * @param authorities the caller's authority codes
     * @return the readable codes; never {@code null}
     */
    public static Set<String> readableCodes(Collection<String> authorities) {
        Set<String> readable = new HashSet<>();
        SCOPES.forEach((governing, codes) -> {
            if (authorities.contains(governing)) {
                readable.addAll(codes);
            }
        });
        return Set.copyOf(readable);
    }
}
