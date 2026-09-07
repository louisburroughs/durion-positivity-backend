package com.positivity.price.internal.enums;

/**
 * Coarse class of a service operation, as pos-catalog defines it (ADR-0059 §3).
 *
 * <p>Declared here rather than imported: modules never share types across the wall (ADR-0026),
 * and a labor rate is scoped by category on the price side whether or not pos-catalog is
 * reachable. The vocabulary is pos-catalog's and must not drift — the V4 CHECK constraint pins
 * the same four values, and a fifth category appearing upstream is a deliberate change here,
 * not an automatic one.
 */
public enum ServiceOperationCategory {
    REPAIR,
    DIAGNOSTIC,
    MAINTENANCE,
    TIRE_SERVICE
}
