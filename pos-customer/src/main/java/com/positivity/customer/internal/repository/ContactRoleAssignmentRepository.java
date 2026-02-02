package com.positivity.customer.internal.repository;

import com.positivity.customer.internal.entity.ContactRole;
import com.positivity.customer.internal.entity.ContactRoleAssignment;
import com.positivity.customer.internal.entity.ContactRoleAssignment.ContactRoleAssignmentId;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for managing contact role assignments.
 * 
 * <p>
 * Provides queries for finding role assignments by account, contact, and role
 * type,
 * as well as for finding primary contacts for specific roles.
 * </p>
 * 
 * @see <a href=
 *      "https://github.com/louisburroughs/durion-positivity-backend/issues/108">Backend
 *      Issue #108</a>
 */
@Repository
public interface ContactRoleAssignmentRepository extends JpaRepository<ContactRoleAssignment, ContactRoleAssignmentId> {

    /**
     * Find all role assignments for a customer account.
     * 
     * @param customerAccountId the customer account UUID
     * @return list of role assignments for the account
     */
    @NonNull
    List<ContactRoleAssignment> findByCustomerAccountId(@NonNull UUID customerAccountId);

    /**
     * Find all role assignments for a specific contact within an account.
     * 
     * @param contactId         the contact (person) UUID
     * @param customerAccountId the customer account UUID
     * @return list of role assignments for the contact in the account
     */
    @NonNull
    List<ContactRoleAssignment> findByContactIdAndCustomerAccountId(
            @NonNull UUID contactId,
            @NonNull UUID customerAccountId);

    /**
     * Find the primary contact for a specific role within an account.
     * 
     * @param customerAccountId the customer account UUID
     * @param roleName          the role to find the primary contact for
     * @return optional containing the primary assignment if one exists
     */
    @NonNull
    Optional<ContactRoleAssignment> findByCustomerAccountIdAndRoleNameAndPrimaryTrue(
            @NonNull UUID customerAccountId,
            @NonNull ContactRole roleName);

    /**
     * Check if a billing contact exists for an account.
     * 
     * @param customerAccountId the customer account UUID
     * @return true if at least one billing contact exists
     */
    @Query("SELECT CASE WHEN COUNT(c) > 0 THEN true ELSE false END " +
            "FROM ContactRoleAssignment c " +
            "WHERE c.customerAccountId = :accountId " +
            "AND c.roleName = 'BILLING'")
    boolean existsBillingContactForAccount(@Param("accountId") @NonNull UUID customerAccountId);

    /**
     * Count the number of billing contacts for an account.
     * 
     * @param customerAccountId the customer account UUID
     * @return count of billing contacts
     */
    @Query("SELECT COUNT(c) " +
            "FROM ContactRoleAssignment c " +
            "WHERE c.customerAccountId = :accountId " +
            "AND c.roleName = 'BILLING'")
    long countBillingContactsForAccount(@Param("accountId") @NonNull UUID customerAccountId);

    /**
     * Delete all role assignments for a specific contact within an account.
     * 
     * @param contactId         the contact (person) UUID
     * @param customerAccountId the customer account UUID
     */
    void deleteByContactIdAndCustomerAccountId(
            @NonNull UUID contactId,
            @NonNull UUID customerAccountId);
}
