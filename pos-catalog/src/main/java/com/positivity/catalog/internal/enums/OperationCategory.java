package com.positivity.catalog.internal.enums;

/**
 * Coarse class of a service operation (#1569, sourcing plan §4.2).
 *
 * <p>More than display taxonomy: the labor-time source-precedence policy keys off it (tire
 * operations prefer manufacturer install times over aggregator flat-rate), and {@link
 * #DIAGNOSTIC} operations are their own workorder line sold in time blocks, never folded into a
 * repair operation's book time.
 */
public enum OperationCategory {
    REPAIR,
    DIAGNOSTIC,
    MAINTENANCE,
    TIRE_SERVICE
}
