package com.positivity.securityservice.internal.service;

import com.positivity.securityservice.internal.dto.StepUpResponse;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Checks a person's credentials once, under the sign-in lockout policy, without issuing a token or
 * opening a session (CAP:550 S16, #2512; AW31). The caller uses it to elevate one action at a shared
 * register: pos-order's cash-movement approvals.
 */
public interface StepUpService {

    /**
     * Verifies {@code username} / {@code password} in the tenant bound to the request.
     *
     * @param locationId the location the caller's action is at, for the audit log, or null
     * @return the person's user id, whether their effective roles grant {@code permission}, and that
     *     grant's location scope for them
     * @throws com.positivity.securityservice.internal.exception.StepUpDeniedException for wrong
     *     credentials or an unknown, disabled, expired or locked account
     */
    @NonNull
    StepUpResponse verify(
            @NonNull String username, @NonNull String password, @NonNull String permission, @Nullable UUID locationId);
}
