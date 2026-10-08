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
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * The rows of several keys, share-locked ({@code SELECT ... FOR SHARE}): a decision reads the AP approval policy
     * this way (CAP:550 S13, #2510), so a policy PUT, which locks each row {@code FOR UPDATE}, waits for the decisions
     * in flight and a decision waits for a PUT in flight. The rows are locked in key order, the order the PUT locks
     * them in, so the two never deadlock. Must run inside an active transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("SELECT c FROM AccountingConfiguration c WHERE c.configKey IN :configKeys ORDER BY c.configKey")
    @NonNull
    List<AccountingConfiguration> findWithShareLockByConfigKeyIn(
            @Param("configKeys") @NonNull Collection<String> configKeys);
}
