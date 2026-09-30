package com.positivity.people.internal.repository;

import com.positivity.people.internal.entity.EmployeeOffboardingRetry;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmployeeOffboardingRetryRepository extends JpaRepository<EmployeeOffboardingRetry, UUID> {

    /**
     * Queue rows whose next attempt has come and that still have attempts left, oldest first. A
     * row at {@code maxAttempts} is left in the table for an operator and never returned here.
     */
    @NonNull
    List<EmployeeOffboardingRetry> findByNextAttemptAtLessThanEqualAndAttemptsLessThanOrderByNextAttemptAtAsc(
            @NonNull Instant now, int maxAttempts);
}
