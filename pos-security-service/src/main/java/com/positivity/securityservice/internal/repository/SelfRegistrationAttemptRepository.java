package com.positivity.securityservice.internal.repository;

import com.positivity.securityservice.internal.entity.SelfRegistrationAttempt;
import java.util.Optional;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SelfRegistrationAttemptRepository extends JpaRepository<SelfRegistrationAttempt, java.util.UUID> {

    @NonNull
    Optional<SelfRegistrationAttempt> findByIdempotencyKey(@NonNull String idempotencyKey);
}
