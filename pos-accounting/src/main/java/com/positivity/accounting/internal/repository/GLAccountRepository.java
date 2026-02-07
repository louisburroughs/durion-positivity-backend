package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.accounting.internal.enums.AccountType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for GL Account entity.
 * Supports CRUD operations and queries for account lookup by code, type,
 * and activation status.
 * 
 * Note: Entity does not have organizationId field. Multi-tenancy not supported
 * at entity level.
 */
@Repository
public interface GLAccountRepository extends JpaRepository<GLAccount, UUID> {

    /**
     * Find a GL account by account code.
     */
    Optional<GLAccount> findByAccountCode(String accountCode);

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
    @Query("SELECT g FROM GLAccount g WHERE g.activationDate <= :transactionDate " +
            "AND (g.deactivationDate IS NULL OR g.deactivationDate > :transactionDate) " +
            "ORDER BY g.accountCode")
    List<GLAccount> findActiveAccountsOn(LocalDateTime transactionDate);

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
