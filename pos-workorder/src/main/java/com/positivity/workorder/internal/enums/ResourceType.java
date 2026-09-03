package com.positivity.workorder.internal.enums;

/**
 * Discriminator for the physical resource a workorder is assigned to (#1656).
 *
 * <p>pos-location owns two genuinely distinct resource aggregates — {@code BayEntity}
 * (a fixed service bay at a site) and {@code MobileUnitEntity} (a field-service vehicle based at a
 * site). They have separate identity and lifecycle and share no table, so a bare
 * {@code Workorder.resourceId} UUID cannot say which one it points at. This enum is that missing
 * type tag; it rides the assignment chain
 * ({@code AssignmentUpdatePayload} → {@code AssignmentUpdatedEvent} → {@code Workorder}) and is
 * what the dispatch dashboard branches on when it resolves resource identity from the
 * {@code ext_bay} / {@code ext_mobile_unit} replicas.
 */
public enum ResourceType {
    /** A fixed service bay owned by pos-location ({@code GET /v1/locations/{locationId}/bays}). */
    BAY,

    /** A mobile service unit owned by pos-location ({@code GET /v1/mobile-units}). */
    MOBILE_UNIT;

    /**
     * The fallback applied when an inbound assignment carries a resource id but no
     * {@code resourceType} (#1656).
     *
     * <p>The upstream pos-shop-manager assignment publisher does not yet emit {@code resourceType};
     * until it does, every assignment that reaches this module untyped is a bay, because the whole
     * assignment chain was bay-only before this change. Defaulting to {@link #BAY} therefore
     * preserves today's behaviour exactly rather than silently reclassifying existing assignments.
     * This is the single place that decision is made — resolve through here, never by
     * re-implementing the null check at a call site.
     *
     * @param resourceType the inbound type, possibly {@code null}
     * @return {@code resourceType} when present, otherwise {@link #BAY}
     */
    public static ResourceType orDefault(ResourceType resourceType) {
        return resourceType != null ? resourceType : BAY;
    }
}
