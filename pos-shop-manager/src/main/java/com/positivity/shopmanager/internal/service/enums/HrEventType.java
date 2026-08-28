package com.positivity.shopmanager.internal.service.enums;

/**
 * HR event types that drive mechanic synchronization.
 * Public contract type — part of the MechanicSyncService API boundary (ADR-0026).
 */
public enum HrEventType {
    MECHANIC_UPSERTED,
    MECHANIC_DEACTIVATED,
    MECHANIC_SKILLS_UPDATED
}
