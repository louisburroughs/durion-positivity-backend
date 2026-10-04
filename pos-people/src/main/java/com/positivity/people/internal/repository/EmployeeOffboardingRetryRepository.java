package com.positivity.people.internal.repository;

import com.positivity.people.internal.entity.EmployeeOffboardingRetry;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmployeeOffboardingRetryRepository extends JpaRepository<EmployeeOffboardingRetry, UUID> {

    /**
     * Reads a queue row and holds its row lock until the transaction ends. Whoever applies a row
     * (the after-commit handler or the retry worker, on any instance) claims it this way first, so
     * a second reader waits and then finds the row gone, or changed, rather than applying the same
     * policy alongside the first and publishing the assignment updates twice (#2360).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM EmployeeOffboardingRetry r WHERE r.id = :id")
    Optional<EmployeeOffboardingRetry> findByIdForUpdate(@Param("id") @NonNull UUID id);

    /**
     * Queue rows whose next attempt has come and that still have attempts left, oldest first. A
     * row at {@code maxAttempts} is left in the table for an operator and never returned here.
     */
    @NonNull
    List<EmployeeOffboardingRetry> findByNextAttemptAtLessThanEqualAndAttemptsLessThanOrderByNextAttemptAtAsc(
            @NonNull Instant now, int maxAttempts);

    /**
     * Rows that have used up their attempts and wait for an operator; drives the
     * {@code people.offboarding.retry.exhausted} gauge.
     */
    /** Drops every pending row of the employee, so a new offboarding supersedes earlier ones (#2418). */
    void deleteByEmployeeId(@NonNull UUID employeeId);

    long countByAttemptsGreaterThanEqual(int maxAttempts);
}
