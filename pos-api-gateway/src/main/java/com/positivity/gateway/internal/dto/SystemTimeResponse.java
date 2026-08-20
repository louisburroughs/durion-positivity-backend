package com.positivity.gateway.internal.dto;

import java.time.Instant;

/**
 * Read-only view of the accelerated clock, served by {@code GET /system/time}.
 *
 * <p>An accelerated run uses this to confirm, before seeding starts, that every JVM shares the same
 * anchors, and to detect when virtual time has caught up with wall time.
 *
 * @param virtualTime the clock's current instant; equals wall time once converged
 * @param scale virtual seconds elapsed per real second
 * @param zone the clock's zone id
 * @param accelerated always true — the endpoint exists only under the accelerated profile
 * @param converged whether virtual time has reached wall time and stopped accelerating
 * @param realStart the wall-clock instant the run was launched
 * @param virtualStart the virtual instant the run started from
 */
public record SystemTimeResponse(
        Instant virtualTime,
        double scale,
        String zone,
        boolean accelerated,
        boolean converged,
        Instant realStart,
        Instant virtualStart) {}
