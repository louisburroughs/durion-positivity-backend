package com.positivity.securityservice.internal.enums;

/**
 * Which pos-location parent dimension a {@link LocationScope#LOCATION}-scoped role's reach is
 * evaluated along (ADR-0061 §2, #1868). Recorded on the role; traversed at check time by the
 * owning service, never expanded at token issuance.
 *
 * <ul>
 *   <li>{@link #FINANCIAL} — the {@code FINANCIAL} parent chain: who owns the numbers for a site.
 *       The accounting and general-manager roles.</li>
 *   <li>{@link #OTHER} — the union of the seven non-financial parent types: who runs a site.
 *       Every other role, including {@code INVENTORY_CONTROLLER}, which is an inventory role
 *       and must not be swept into {@code FINANCIAL} by a name match on "CONTROLLER".</li>
 * </ul>
 */
public enum LocationHierarchy {
    FINANCIAL,
    OTHER
}
