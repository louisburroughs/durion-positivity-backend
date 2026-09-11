package com.positivity.securityservice.internal.exception;

import java.util.UUID;

/**
 * An activation token was requested for a user that is not awaiting activation (ADR-0062 §7,
 * WS2b-3): only the credential-expired, never-signed-in account provisioning creates may be
 * activated this way, so a platform operator cannot mint a token that would overwrite a live
 * user's password. Mapped to 409 {@code USER_NOT_AWAITING_ACTIVATION} by
 * {@code GlobalExceptionHandler}.
 */
public class UserNotAwaitingActivationException extends RuntimeException {

    public UserNotAwaitingActivationException(UUID userId) {
        super("User " + userId + " is not awaiting activation; only a never-activated, credential-expired"
                + " account can be activated with a token");
    }
}
