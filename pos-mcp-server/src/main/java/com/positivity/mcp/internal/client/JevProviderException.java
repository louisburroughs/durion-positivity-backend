package com.positivity.mcp.internal.client;

import com.positivity.mcp.internal.domain.FallbackReason;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * ADR-0068 §2 / spec §2.2: a provider failure. Every way a System One call can go wrong maps to one
 * {@link FallbackReason}, and the caller takes the heuristic record for the whole turn.
 *
 * <p>The message names the failure class, the HTTP status and the latency only: it never carries the
 * request state, an answer string or the provider's own error text (ADR-0068 §4).
 */
public final class JevProviderException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final FallbackReason reason;
    private final @Nullable Integer httpStatus;

    public JevProviderException(@NonNull FallbackReason reason, @NonNull String message) {
        this(reason, message, null, null);
    }

    public JevProviderException(
            @NonNull FallbackReason reason,
            @NonNull String message,
            @Nullable Integer httpStatus,
            @Nullable Throwable cause) {
        super(message, cause);
        this.reason = reason;
        this.httpStatus = httpStatus;
    }

    public @NonNull FallbackReason reason() {
        return reason;
    }

    public @Nullable Integer httpStatus() {
        return httpStatus;
    }
}
