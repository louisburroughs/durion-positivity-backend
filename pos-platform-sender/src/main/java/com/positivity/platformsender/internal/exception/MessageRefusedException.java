package com.positivity.platformsender.internal.exception;

import lombok.Getter;
import org.jspecify.annotations.NonNull;

/**
 * A permanent refusal (FI-2 §1, {@code 4xx}): the request can never succeed as sent, so the caller
 * marks the send failed instead of retrying. Answered as {@code 422} with {@link #getCode()}.
 */
@Getter
public class MessageRefusedException extends RuntimeException {

    private final String code;

    public MessageRefusedException(@NonNull String code, @NonNull String message) {
        super(message);
        this.code = code;
    }
}
