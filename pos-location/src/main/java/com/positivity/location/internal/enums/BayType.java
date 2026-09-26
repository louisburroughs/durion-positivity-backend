package com.positivity.location.internal.enums;

/**
 * Supported bay classifications.
 *
 * <p>{@code acceptsGeneralWork} (DECISION-LOCATION-025, CAP-325 D14) says whether a bay of this
 * type is offered for ordinary, non-specialty operations by default. It is {@code false} only for
 * {@code WASH_DETAIL}: that type is a specialty bay whose own specialty set is currently empty
 * (the catalog seeds no wash services), and without this flag the D14 default would wrongly let it
 * absorb general mechanical work. Every other type accepts general work, specialty bays included —
 * D14 requires a specialty bay to remain eligible for general work, just ranked last, so the rack
 * stays free without lying about capacity.
 *
 * Issue: CAP-136 #77
 */
public enum BayType {
    GENERAL_SERVICE(true),
    ALIGNMENT(true),
    TIRE_SERVICE(true),
    HEAVY_DUTY(true),
    INSPECTION(true),
    WASH_DETAIL(false);

    private final boolean acceptsGeneralWork;

    BayType(boolean acceptsGeneralWork) {
        this.acceptsGeneralWork = acceptsGeneralWork;
    }

    /**
     * Whether a bay of this type is eligible for general (non-specialty) work by default
     * (DECISION-LOCATION-025). {@code false} only for {@link #WASH_DETAIL}.
     */
    public boolean acceptsGeneralWork() {
        return acceptsGeneralWork;
    }
}
