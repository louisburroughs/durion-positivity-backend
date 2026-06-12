package com.positivity.inventory.internal.exception;

/**
 * Raised when {@code expand=tree} is requested on a parent-location rollup
 * whose descendant site count exceeds the configured cap — prevents unbounded
 * fan-out of topology fetches and grouped queries (CAP-218 #659).
 */
public class RollupExpansionTooLargeException extends RuntimeException {
    public RollupExpansionTooLargeException(int siteCount, int cap) {
        super("expand=tree rejected: " + siteCount + " descendant sites exceed the cap of " + cap
                + "; call the per-site rollup endpoint instead");
    }
}
