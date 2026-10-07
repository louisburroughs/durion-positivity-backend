package com.positivity.securityservice.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A step-up credential check (CAP:550 S16, #2512; AW31): the credentials a person enters once at a
 * shared register, the permission the caller needs them to hold, and the location the action is at.
 * The tenant is never part of the body; it is the one the calling service binds on the request
 * (ADR-0062).
 *
 * <p>{@link #toString()} never prints the password.
 *
 * @param username the person's sign-in name
 * @param password the person's password; checked once and never stored or logged
 * @param permission the permission the caller asks about, e.g. {@code order:session:approve_cash_movement}
 * @param locationId the location the approved action is at, for the audit log; the caller decides the
 *     person's reach there from the scope this check answers
 */
@Schema(description = "Credentials to check once, the permission they must hold and where")
public record StepUpRequest(
        @NotBlank String username,
        @NotBlank String password,
        @NotBlank String permission,
        @Nullable UUID locationId) {

    @Override
    public String toString() {
        return "StepUpRequest[username=" + username + ", permission=" + permission + ", locationId=" + locationId + "]";
    }
}
