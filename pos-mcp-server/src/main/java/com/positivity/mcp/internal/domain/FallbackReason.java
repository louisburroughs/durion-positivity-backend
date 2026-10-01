package com.positivity.mcp.internal.domain;

import java.util.Locale;
import org.jspecify.annotations.NonNull;

/**
 * ADR-0068 §2 / spec §2.5: why a turn (or, in {@code enforce}, one tag) took the heuristic value
 * instead of the decision model's. The lower-case name is the value recorded on the trace, the
 * telemetry event and the {@code mcp.tagging.fallback{reason}} meter.
 */
public enum FallbackReason {
    /** The provider did not answer inside {@code mcp.tagging.provider.timeout}. */
    TIMEOUT,
    /** HTTP 429 or 529: the provider refused the request for load (an external provider). */
    RATE_LIMITED,
    /** Any other non-2xx status, a connection failure, or an Ollama {@code {"error": …}} body. */
    ERROR,
    /** A body that parsed but did not answer the questions asked, or answered them with invalid values. */
    MALFORMED,
    /** ADR-0068 §6: the model answered below the tag's threshold, so the tag took the heuristic value. */
    LOW_CONFIDENCE,
    /**
     * Spec §2.5, the T0 skip: the heuristic hit an exact {@code SimpleChatRuleCatalog} rule, so the
     * provider was not called for the turn at all; every acting answer is the heuristic one.
     */
    HEURISTIC_CERTAIN;

    /** The wire / metric spelling: {@code timeout}, {@code rate_limited}, ... */
    public @NonNull String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
