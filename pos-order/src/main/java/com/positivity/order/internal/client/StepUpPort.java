package com.positivity.order.internal.client;

import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * pos-security-service's step-up credential check (CAP:550 S16, #2512; AW31): verifies a person's own
 * credentials once, in the caller's tenant, under the sign-in lockout policy, without issuing a token
 * or opening a session.
 */
public interface StepUpPort {

    /**
     * Verifies {@code username} / {@code password} and asks whether that person holds {@code permission}.
     *
     * @return the verified person and whether they hold the permission
     * @throws com.positivity.order.internal.exception.CashMovementRefusedException with {@code
     *     APPROVAL_DENIED} when the check refuses, for any reason
     * @throws com.positivity.order.internal.exception.StepUpUnavailableException when the check could
     *     not be made
     */
    @NonNull
    StepUpResult verify(@NonNull String username, @NonNull String password, @NonNull String permission);

    /** The verified person and whether they hold the permission asked about. */
    record StepUpResult(@NonNull UUID userId, boolean holdsPermission) {}
}
