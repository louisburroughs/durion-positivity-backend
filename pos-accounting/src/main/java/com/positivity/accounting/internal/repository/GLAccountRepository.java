package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.accounting.internal.enums.AccountSubtype;
import com.positivity.accounting.internal.enums.AccountType;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository for GL Account entity.
 * Supports CRUD operations and queries for account lookup by code, type,
 * and activation status.
 *
 * Note: Entity does not have organizationId field. Multi-tenancy not supported
 * at entity level.
 */
public interface GLAccountRepository extends JpaRepository<GLAccount, UUID> {

    /**
     * Find a GL account by account code.
     */
    Optional<GLAccount> findByAccountCode(String accountCode);

    /**
     * The account, row-locked to the end of the transaction: two bank opening balance commands on one account
     * serialize here (#2572), so the second sees the first's standing opening.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT g FROM GLAccount g WHERE g.glAccountId = :glAccountId")
    @NonNull
    Optional<GLAccount> lockById(@Param("glAccountId") @NonNull UUID glAccountId);

    /**
     * Find all GL accounts ordered by account code.
     */
    @Query("SELECT g FROM GLAccount g ORDER BY g.accountCode")
    List<GLAccount> findAllOrderedByCode();

    /**
     * Find all active GL accounts on a given date.
     * Active: activationDate <= date AND (deactivationDate IS NULL OR
     * deactivationDate > date)
     */
    @Query("SELECT g FROM GLAccount g WHERE g.activationDate <= :transactionDate "
            + "AND (g.deactivationDate IS NULL OR g.deactivationDate > :transactionDate) "
            + "ORDER BY g.accountCode")
    List<GLAccount> findActiveAccountsOn(LocalDateTime transactionDate);

    /**
     * Reconcilable accounts of one subtype that are active at {@code at} — a null activation date
     * counts as active from the start, as the seeded chart has none (bank reconciliation D5, #2301).
     */
    @Query("SELECT g FROM GLAccount g WHERE g.reconcilable = true AND g.accountSubtype = :subtype "
            + "AND (g.activationDate IS NULL OR g.activationDate <= :at) "
            + "AND (g.deactivationDate IS NULL OR g.deactivationDate > :at) "
            + "ORDER BY g.accountCode")
    @NonNull
    List<GLAccount> findReconcilableActiveOn(
            @Param("subtype") @NonNull AccountSubtype subtype, @Param("at") @NonNull LocalDateTime at);

    /**
     * Accounts of one subtype active at {@code at}, reconcilable or not, by the rule a posting checks ({@code
     * GLAccountService.validateAccountForPosting}): activated at or before {@code at} (a null activation date counts as
     * active from the start) and not deactivated by it. The AP pay command counts its eligible {@code BANK_CASH}
     * accounts with it at the start of the execution day, the instant the payment's entry posts at (CAP:550 S42, #2603).
     */
    @Query("SELECT g FROM GLAccount g WHERE g.accountSubtype = :subtype "
            + "AND (g.activationDate IS NULL OR g.activationDate <= :at) "
            + "AND (g.deactivationDate IS NULL OR g.deactivationDate > :at) "
            + "ORDER BY g.accountCode")
    @NonNull
    List<GLAccount> findBySubtypeActiveAt(
            @Param("subtype") @NonNull AccountSubtype subtype, @Param("at") @NonNull LocalDateTime at);

    /**
     * Every reconcilable account active at {@code at}, whatever its subtype — close readiness under {@code
     * BANK_REC_CLOSE_SCOPE = ALL_RECONCILABLE} (bank reconciliation §5.2, D5; story S6, #2305).
     */
    @Query("SELECT g FROM GLAccount g WHERE g.reconcilable = true "
            + "AND (g.activationDate IS NULL OR g.activationDate <= :at) "
            + "AND (g.deactivationDate IS NULL OR g.deactivationDate > :at) "
            + "ORDER BY g.accountCode")
    @NonNull
    List<GLAccount> findAllReconcilableActiveOn(@Param("at") @NonNull LocalDateTime at);

    /** One page of {@link #findReconcilableActiveOn(AccountSubtype, LocalDateTime)}, cut in the database. */
    @Query(
            value = "SELECT g FROM GLAccount g WHERE g.reconcilable = true AND g.accountSubtype = :subtype "
                    + "AND (g.activationDate IS NULL OR g.activationDate <= :at) "
                    + "AND (g.deactivationDate IS NULL OR g.deactivationDate > :at) "
                    + "ORDER BY g.accountCode",
            countQuery = "SELECT COUNT(g) FROM GLAccount g WHERE g.reconcilable = true AND g.accountSubtype = :subtype "
                    + "AND (g.activationDate IS NULL OR g.activationDate <= :at) "
                    + "AND (g.deactivationDate IS NULL OR g.deactivationDate > :at)")
    @NonNull
    Page<GLAccount> findReconcilableActiveOn(
            @Param("subtype") @NonNull AccountSubtype subtype,
            @Param("at") @NonNull LocalDateTime at,
            @NonNull Pageable pageable);

    /**
     * Check if an account code already exists.
     */
    boolean existsByAccountCode(String accountCode);

    /**
     * Find accounts by type (ASSET, LIABILITY, EXPENSE, etc.).
     */
    List<GLAccount> findByAccountType(AccountType accountType);

    /**
     * Find accounts by name (case-insensitive partial match).
     */
    List<GLAccount> findByAccountNameContainingIgnoreCase(String accountName);
}
