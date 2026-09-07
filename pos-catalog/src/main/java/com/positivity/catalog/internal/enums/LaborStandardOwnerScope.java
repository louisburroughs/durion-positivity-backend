package com.positivity.catalog.internal.enums;

/**
 * Who owns a stored labor time, and therefore who resolves it (#1575 Tier 0,
 * {@code docs/SPEC-tier-0-durion-owned-service-data.md} T0-2 / D1).
 *
 * <p>Ownership is deliberately a property of the <em>time</em>, not of the operation: the
 * taxonomy stays global because it is the shared vocabulary vendor codes map onto (ADR-0059
 * §3), while the hours a shop has decided for itself are its own. A shop that has priced its
 * own work is never overruled by a published guide, which is why {@link #SHOP} outranks {@link
 * #PLATFORM} ahead of every other ordering dimension in resolution.
 */
public enum LaborStandardOwnerScope {

    /** Every location resolves this row. Imported guide rows are always platform-owned. */
    PLATFORM,

    /** One location's own number; invisible to every other location. */
    SHOP
}
