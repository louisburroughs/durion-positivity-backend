package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.AccountingConfiguration;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

/**
 * Repository for the org-level accounting configuration key/value store
 * (story B2, issue #944).
 */
public interface AccountingConfigurationRepository extends JpaRepository<AccountingConfiguration, UUID> {

    Optional<AccountingConfiguration> findByConfigKey(@NonNull String configKey);

    /**
     * The rows of several keys in one query — one snapshot of a setting group, such as the five bank
     * reconciliation policy keys a PUT replaces together (SPEC-manual-bank-reconciliation §5.2; story S6, #2305).
     */
    @NonNull
    List<AccountingConfiguration> findByConfigKeyIn(@NonNull Collection<String> configKeys);

    /**
     * Locked variant ({@code SELECT ... FOR UPDATE}) for the hard-lock-date
     * <em>setter</em> only: serializes concurrent
     * {@code setHardLockDate} calls so the monotonic-forward check cannot be
     * bypassed by a read–check–save race (last-writer-wins regression).
     * Reads ({@code getHardLockDate}, the period gate) stay on the unlocked
     * {@link #findByConfigKey} finder. Must run inside an active transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<AccountingConfiguration> findWithLockByConfigKey(@NonNull String configKey);
}
