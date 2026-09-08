package com.positivity.securityservice.internal.enums;

/**
 * Whether a role's grants reach every location or only the holder's assigned nodes
 * (ADR-0061 §1, #1868). A property of the <em>role</em>: two roles may hold identical grants
 * and differ only here (the INVENTORY_MANAGER / INVENTORY_CONTROLLER pair, #1373).
 *
 * <ul>
 *   <li>{@link #ALL} — grants apply everywhere; today's behaviour, and the default for a role
 *       created through {@code POST /v1/roles}.</li>
 *   <li>{@link #LOCATION} — grants apply only at the nodes pos-people assigns the holder to, and
 *       every descendant of those nodes along the role's {@link LocationHierarchy}.</li>
 * </ul>
 */
public enum LocationScope {
    ALL,
    LOCATION
}
