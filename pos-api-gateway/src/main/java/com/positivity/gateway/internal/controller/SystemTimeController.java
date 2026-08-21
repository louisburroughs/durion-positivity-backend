package com.positivity.gateway.internal.controller;

import com.positivity.gateway.internal.dto.SystemTimeResponse;
import com.positivity.time.ScaledClock;
import java.time.Clock;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Diagnostics endpoint reporting the accelerated clock.
 *
 * <p>Deliberately unauthenticated and read-only. An accelerated run polls it before any user or
 * token exists, to confirm every JVM shares the same anchors and to detect convergence. It exposes
 * no mutation: the clock is set once from deployment configuration and can only be changed by
 * redeploying.
 *
 * <p>The endpoint exists only under the {@code accelerated} profile, so a normal alpha or
 * production stack serves 404 for it.
 */
@RestController
@Profile("accelerated")
public class SystemTimeController {

    private final ScaledClock clock;

    /**
     * @param clock the application clock, which under this profile must be the shared
     *     {@link ScaledClock} supplied by {@code pos-events}
     * @throws IllegalStateException if a competing module clock has replaced it, which would mean
     *     this JVM is not actually running on the accelerated clock
     */
    public SystemTimeController(Clock clock) {
        if (!(clock instanceof ScaledClock scaledClock)) {
            throw new IllegalStateException("the accelerated profile requires a ScaledClock, but the Clock bean is "
                    + clock.getClass().getName());
        }
        this.clock = scaledClock;
    }

    @GetMapping("/system/time")
    public SystemTimeResponse systemTime() {
        return new SystemTimeResponse(
                clock.instant(),
                clock.getScale(),
                clock.getZone().getId(),
                true,
                clock.isConverged(),
                clock.getRealStart(),
                clock.getVirtualStart());
    }
}
