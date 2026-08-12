package com.positivity.supplier.internal.client;

import com.positivity.supplier.internal.spi.ExchangeOutcome;
import java.time.Duration;
import java.util.Objects;
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
 */
public record SupplierHttpResponse(
        @NonNull ExchangeOutcome outcome,
        @Nullable Integer httpStatus,
        @Nullable String body,
        @NonNull String correlationId,
        int attempts,
        @NonNull Duration totalDuration,
        @Nullable String failureDetail) {

    public SupplierHttpResponse {
        Objects.requireNonNull(outcome, "outcome must not be null");
        Objects.requireNonNull(correlationId, "correlationId must not be null");
        Objects.requireNonNull(totalDuration, "totalDuration must not be null");
        if (attempts < 1) {
            throw new IllegalArgumentException("attempts must be >= 1");
        }
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
