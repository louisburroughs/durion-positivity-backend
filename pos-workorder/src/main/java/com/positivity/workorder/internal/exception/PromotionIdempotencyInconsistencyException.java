package com.positivity.workorder.internal.exception;

import java.util.UUID;
import lombok.Getter;
import org.jspecify.annotations.NonNull;

/**
 * A recorded idempotency key points at a workorder that no longer resolves (issue #1477).
 *
 * <p>A server-side data inconsistency, not anything the caller did: it stays a {@code 500}, but an
 * enveloped and correlated one, so the response carries the same id as the log line that holds the
 * detail. The message names only identifiers the caller already supplied or owns.
 */
@Getter
public class PromotionIdempotencyInconsistencyException extends RuntimeException {

    public static final String ERROR_CODE = "PROMOTION_IDEMPOTENCY_INCONSISTENT";

    public static final String SUPPORT_ACTION =
            "Contact support with the correlation ID; the recorded idempotency key points at a workorder that "
                    + "cannot be loaded.";

    private final @NonNull UUID workorderId;

    public PromotionIdempotencyInconsistencyException(@NonNull UUID workorderId) {
        super("Idempotency key resolves to workorder " + workorderId + ", which cannot be loaded");
        this.workorderId = workorderId;
    }
}
