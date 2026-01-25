package com.positivity.accounting.repository;

import com.positivity.accounting.entity.GLAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Repository for GL Account entity.
 * Supports CRUD operations and queries for account lookup by number, status,
 * and effective dates.
 */
@Repository
public interface GLAccountRepository extends JpaRepository<GLAccount, String> {

    /**
     * Find an active GL account by account number and organization.
     */
    @Query("SELECT g FROM GLAccount g WHERE g.accountNumber = :accountNumber AND g.organizationId = :organizationId")
    Optional<GLAccount> findByAccountNumber(String organizationId, String accountNumber);

    /**
     * Find all GL accounts for an organization.
     */
    @Query("SELECT g FROM GLAccount g WHERE g.organizationId = :organizationId ORDER BY g.accountNumber")
    List<GLAccount> findAllByOrganization(String organizationId);

    /**
     * Find all active GL accounts effective on a given date.
     * Active: activatedAt <= date AND (deactivatedAt IS NULL OR deactivatedAt >
     * date) AND archivedAt IS NULL
     */
    @Query("SELECT g FROM GLAccount g WHERE g.organizationId = :organizationId " +
            "AND g.activatedAt <= :transactionDate " +
            "AND (g.deactivatedAt IS NULL OR g.deactivatedAt > :transactionDate) " +
            "AND g.archivedAt IS NULL " +
            "ORDER BY g.accountNumber")
    List<GLAccount> findActiveAccountsOn(String organizationId, LocalDate transactionDate);

    /**
     * Check if an account number already exists for an organization.
     */
    boolean existsByAccountNumberAndOrganizationId(String accountNumber, String organizationId);

    /**
     * Find accounts by type (ASSET, LIABILITY, EXPENSE, etc.).
     */
    @Query("SELECT g FROM GLAccount g WHERE g.organizationId = :organizationId AND g.accountType = :accountType")
    List<GLAccount> findByAccountType(String organizationId, String accountType);
}
