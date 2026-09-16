package com.positivity.vehicle.internal.enums;

/**
 * Where a vehicle's current {@code gvwr_class} came from (CAP-327, spec D13).
 *
 * <p>An operator-set value always wins: a decode is a default, never the truth (a GVWR derate, an
 * upfit or a re-registration all make a decode wrong). Per D13.1 no decode ships yet, so every
 * stored value today is {@link #OPERATOR_SET}; {@link #DECODED} is reserved for it.
 */
public enum GvwrClassSource {
    OPERATOR_SET,
    DECODED
}
