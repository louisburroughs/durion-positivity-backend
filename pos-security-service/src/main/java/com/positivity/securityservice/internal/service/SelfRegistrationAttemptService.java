package com.positivity.securityservice.internal.service;

import com.positivity.securityservice.internal.dto.SelfRegistrationAttemptSnapshot;
import com.positivity.securityservice.internal.dto.SelfRegistrationResponse;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public interface SelfRegistrationAttemptService {

    @NonNull
    Optional<SelfRegistrationAttemptSnapshot> findByIdempotencyKey(@NonNull String idempotencyKey);

    void recordSuccess(
            @NonNull String idempotencyKey,
            @NonNull String requestFingerprint,
            @NonNull String email,
            @Nullable String username,
            @NonNull SelfRegistrationResponse response);

    void recordConflict(
            @NonNull String idempotencyKey,
            @NonNull String requestFingerprint,
            @NonNull String email,
            @Nullable String username,
            @NonNull String conflictCode,
            @NonNull String conflictMessage,
            @Nullable UUID referenceId);
}
