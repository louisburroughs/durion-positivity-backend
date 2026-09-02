package com.positivity.referencemock.internal.service;

import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Service;

/**
 * Chaos knobs for degradation testing (plan §10): every mock endpoint accepts optional
 * {@code ?delayMs=} and {@code ?failRate=} parameters so pos-catalog adapter and pipeline tests
 * can exercise slow-vendor and vendor-down behavior ("kill the mock, flow never 500s").
 */
@Service
public class ChaosService {

    /** Hard cap on the injected delay so a typo cannot wedge a test run. */
    static final long MAX_DELAY_MS = 10_000L;

    /**
     * Clamps the requested delay to {@code [0, 10000]} milliseconds.
     *
     * @param delayMs requested delay, or null for none
     * @return the effective delay in milliseconds
     */
    public long effectiveDelayMs(Long delayMs) {
        if (delayMs == null || delayMs <= 0) {
            return 0L;
        }
        return Math.min(delayMs, MAX_DELAY_MS);
    }

    /**
     * Sleeps for the clamped delay, preserving the interrupt flag if interrupted.
     *
     * @param delayMs requested delay, or null for none
     */
    public void delay(Long delayMs) {
        long effective = effectiveDelayMs(delayMs);
        if (effective == 0L) {
            return;
        }
        try {
            Thread.sleep(effective);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Decides whether this request should fail with 503. A rate of 1.0 (or above) always fails
     * and 0.0 (or below, or null) never fails, so tests are deterministic at the extremes.
     *
     * @param failRate probability in [0.0, 1.0], or null for never
     * @return true when the request should be answered with 503 and no body
     */
    public boolean shouldFail(Double failRate) {
        if (failRate == null || failRate <= 0.0) {
            return false;
        }
        if (failRate >= 1.0) {
            return true;
        }
        return ThreadLocalRandom.current().nextDouble() < failRate;
    }
}
