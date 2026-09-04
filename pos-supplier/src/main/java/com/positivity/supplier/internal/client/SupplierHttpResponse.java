package com.positivity.supplier.internal.client;

import com.positivity.supplier.internal.spi.ExchangeOutcome;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * The transport-level result of one logical call, after any retries.
 *
 * <p>Transport failures are <strong>returned, not thrown</strong>: a vendor being unavailable or
 * rejecting a document is an expected operational state the orchestrator must branch on, and
 * exception control flow would invite a blanket {@code catch} that silently retries an ambiguous
 * outcome. The one exception is {@link ExchangeOutcome#CONFIGURATION_ERROR}, which the base client
 * raises as {@code SupplierConfigurationException}: a deployment defect must surface loudly rather
 * than be absorbed into a fallback path.
 *
 * @param outcome classification of the final attempt (ADR-0052 §5)
 * @param httpStatus status of the final attempt; {@code null} when no response was received
 * @param body response body when one was read; {@code null} otherwise
 * @param correlationId the id stamped on the exchange as {@code X-Correlation-Id}
 * @param attempts how many attempts were made, {@code >= 1}
 * @param totalDuration wall-clock duration across all attempts
 * @param failureDetail operator-facing failure summary; {@code null} on success. Never a credential
 * @param responseHeaders control-flow response headers, restricted to
 *     {@link #CARRIED_RESPONSE_HEADERS}; empty when none were present
 */
public record SupplierHttpResponse(
        @NonNull ExchangeOutcome outcome,
        @Nullable Integer httpStatus,
        @Nullable String body,
        @NonNull String correlationId,
        int attempts,
        @NonNull Duration totalDuration,
        @Nullable String failureDetail,
        @NonNull Map<String, String> responseHeaders) {

    /**
     * The only response headers carried out of the transport, matched case-insensitively.
     *
     * <p>An allowlist rather than the whole header map, because these three carry protocol control
     * flow and nothing else does. {@code Location} is where an asynchronously accepted request says
     * its result will appear — without it a 202 is indistinguishable from a request that vanished.
     * {@code API-Version} lets a caller notice it is talking to a major version it was not built
     * for. {@code Retry-After} is a vendor stating its own backoff.
     *
     * <p>Carrying every header instead would put arbitrary vendor-controlled strings — set-cookie,
     * bearer challenges, tracing identifiers — into an object that is logged and flows toward the
     * exchange audit. That is a credential-leak shape for no gain: no caller has ever wanted them.
     */
    public static final Set<String> CARRIED_RESPONSE_HEADERS = Set.of("location", "api-version", "retry-after");

    // Left as IllegalArgumentException (#1694): built exclusively by internal transport code
    // after each outbound vendor call attempt, never from client-supplied HTTP input. A violation
    // here is this module's own defect and belongs on the platform 500 fallback, not a client 4xx.
    public SupplierHttpResponse {
        Objects.requireNonNull(outcome, "outcome must not be null");
        Objects.requireNonNull(correlationId, "correlationId must not be null");
        Objects.requireNonNull(totalDuration, "totalDuration must not be null");
        Objects.requireNonNull(responseHeaders, "responseHeaders must not be null");
        if (attempts < 1) {
            throw new IllegalArgumentException("attempts must be >= 1");
        }
        // Lower-cased keys so a caller need not guess a vendor's capitalisation: HTTP header names
        // are case-insensitive and vendors do vary.
        Map<String, String> normalised = new LinkedHashMap<>();
        responseHeaders.forEach((name, value) -> {
            if (name != null && value != null) {
                normalised.put(name.toLowerCase(Locale.ROOT), value);
            }
        });
        responseHeaders = Collections.unmodifiableMap(normalised);
    }

    /**
     * Builds a response that carried no headers.
     *
     * <p>Kept so the many call sites that never had headers to report — every EDIWheel capability,
     * and every failure path that received no response at all — say that by omission rather than by
     * threading an empty map through.
     */
    public SupplierHttpResponse(
            @NonNull ExchangeOutcome outcome,
            @Nullable Integer httpStatus,
            @Nullable String body,
            @NonNull String correlationId,
            int attempts,
            @NonNull Duration totalDuration,
            @Nullable String failureDetail) {
        this(outcome, httpStatus, body, correlationId, attempts, totalDuration, failureDetail, Map.of());
    }

    /**
     * The named response header, or {@code null} when the vendor did not send it.
     *
     * @param name header name; matched case-insensitively
     */
    @Nullable
    public String header(@NonNull String name) {
        return responseHeaders.get(name.toLowerCase(Locale.ROOT));
    }

    /** Whether the vendor accepted and answered. */
    public boolean isSuccess() {
        return outcome.isSuccess();
    }

    /**
     * Whether this call may be safely re-dispatched later (e.g. from an outbox) without vendor-side
     * reconciliation. Note this is about a <em>later</em> re-dispatch, not an in-call retry: the
     * base client never spins on an open breaker.
     */
    public boolean isSafeToRedispatch() {
        return outcome.isPreSendRetryable();
    }
}
