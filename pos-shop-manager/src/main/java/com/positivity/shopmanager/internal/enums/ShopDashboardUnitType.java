package com.positivity.shopmanager.internal.enums;

/**
 * Discriminator for the repair units a shop dashboard row can describe (#1658).
 *
 * <p>A "unit" is the union of pos-location's two repair-resource aggregates — service bays and
 * mobile service units. Shop Management deliberately does <strong>not</strong> persist a unified
 * Unit entity: the two aggregates have separate owners, separate identity and separate lifecycles,
 * and a persisted union would be a third copy of facts this module does not own. The union is
 * synthesized per request and tagged with this discriminator so the caller can tell a bay from a
 * van without inferring anything from the identifier.
 */
public enum ShopDashboardUnitType {
    /** A fixed service bay at the location. */
    BAY,

    /** A mobile service unit based at, and dispatched from, the location. */
    MOBILE_UNIT
}
