package com.positivity.platformsender.internal.exception;

import lombok.Getter;
import org.jspecify.annotations.NonNull;

/**
 * A transient failure (FI-2 §1, {@code 5xx}): nothing was delivered and the idempotency key is
 * free (or still held by an attempt in flight), so the caller retries with backoff. Answered as
 * {@code 503} with {@link #getCode()}.
 */
@Getter
public class SenderUnavailableException extends RuntimeException {

    private final String code;

    public SenderUnavailableException(@NonNull String code, @NonNull String message) {
        super(message);
        this.code = code;
    }
}
