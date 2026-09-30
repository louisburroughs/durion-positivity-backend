package com.positivity.web.common;

import java.time.Duration;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * A request depends on a record that a module populates asynchronously from another domain's
 * events (an {@code ext_*} replica, ADR-0044 §2 R3) and that has not arrived yet.
 *
 * <p>This is not "the record does not exist": the replica lags its source by the time it takes
 * an event to travel, so an absent row can mean either. A caller cannot tell those apart from a
 * 404 (or a 400/409/422 worded as one), and would give up on an id that is about to become valid.
 * Throwing this instead answers {@code 503 Service Unavailable} with a {@code Retry-After}
 * header and a {@code <X>_REPLICATION_PENDING} code, which says "not yet" rather than "no"
 * (issue #1994).
 *
 * <p>Throw it only on the replica-miss branch. A row that is present but in the wrong state
 * keeps the status that describes that state, and a module that already owns a local row proving
 * the entity exists must not gate on the replica at all.
 *
 * <p>The {@link ResponseStatus @ResponseStatus} declaration is what lets
 * {@code BulkIngestFailures.isRetryable} in pos-bulk-ingest-lib classify a bulk-ingest row that
 * hits this as {@code REPLICATION_PENDING}. {@link GlobalApiExceptionHandler} renders the
 * envelope and header; a module advice that catches {@link RuntimeException} or
 * {@link Exception} must declare its own handler for this type and delegate to it.
 */
@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
public class ReplicationPendingException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** How long a caller is told to wait when the throw site has no better estimate. */
    public static final Duration DEFAULT_RETRY_AFTER = Duration.ofSeconds(5);

    private final String code;
    private final Duration retryAfter;
    private final @Nullable UUID referenceId;

    public ReplicationPendingException(@NonNull String code, @NonNull String message) {
        this(code, message, DEFAULT_RETRY_AFTER, null);
    }

    public ReplicationPendingException(@NonNull String code, @NonNull String message, @Nullable UUID referenceId) {
        this(code, message, DEFAULT_RETRY_AFTER, referenceId);
    }

    public ReplicationPendingException(
            @NonNull String code, @NonNull String message, @NonNull Duration retryAfter, @Nullable UUID referenceId) {
        super(message);
        this.code = code;
        this.retryAfter = retryAfter;
        this.referenceId = referenceId;
    }

    /** Machine-readable code, {@code <X>_REPLICATION_PENDING}. */
    public @NonNull String getCode() {
        return code;
    }

    /** How long the caller should wait before retrying. */
    public @NonNull Duration getRetryAfter() {
        return retryAfter;
    }

    /** The awaited entity's id, when the throw site knows it. */
    public @Nullable UUID getReferenceId() {
        return referenceId;
    }

    /**
     * The {@code Retry-After} header value: whole seconds, rounded up, never below one (a zero or
     * negative value would tell the caller to retry at once, into the same lag).
     */
    public long retryAfterSeconds() {
        long seconds = retryAfter.getSeconds() + (retryAfter.getNano() > 0 ? 1 : 0);
        return Math.max(1L, seconds);
    }
}
