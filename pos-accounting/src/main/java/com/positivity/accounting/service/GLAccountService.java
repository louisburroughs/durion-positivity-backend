package com.positivity.accounting.service;

import java.time.LocalDateTime;
import java.util.UUID;

import com.positivity.accounting.internal.dto.GLAccountBalanceResponse;
import com.positivity.accounting.internal.dto.GLAccountCreateRequest;
import com.positivity.accounting.internal.dto.GLAccountListResponse;
import com.positivity.accounting.internal.dto.GLAccountResponse;
import com.positivity.accounting.internal.dto.GLAccountUpdateRequest;
import com.positivity.accounting.internal.exception.AccountNotInactiveException;
import com.positivity.accounting.internal.exception.AccountNotZeroBalanceException;
import com.positivity.accounting.internal.exception.DuplicateAccountCodeException;
import com.positivity.accounting.internal.exception.GLAccountNotFoundException;

public interface GLAccountService {

    /**
     * Creates a new GL account.
     * Account must have valid account code, name, and type.
     * Validates account code uniqueness.
     *
     * @param request account creation details
     * @return created GL account response
     * @throws DuplicateAccountCodeException if account code already exists
     */
    GLAccountResponse createGLAccount(GLAccountCreateRequest request);

    /**
     * Retrieves a GL account by ID with current status (derived from dates).
     *
     * @param glAccountId account identifier
     * @return GL account response with derived status
     * @throws GLAccountNotFoundException if account not found
     */
    GLAccountResponse getGLAccount(UUID glAccountId);

    /**
     * Updates GL account properties (name, description).
     * Account type and code are immutable after creation.
     *
     * @param glAccountId account identifier
     * @param request     update details
     * @return updated GL account response
     * @throws GLAccountNotFoundException if account not found
     */
    GLAccountResponse updateGLAccount(UUID glAccountId, GLAccountUpdateRequest request);

    /**
     * Activates a GL account, making it available for posting.
     * Sets activation date to now if not already set.
     *
     * @param glAccountId account identifier
     * @return activated GL account response
     * @throws GLAccountNotFoundException if account not found
     */
    GLAccountResponse activateGLAccount(UUID glAccountId);

    /**
     * Activates a GL account with a specific effective date.
     *
     * @param glAccountId   account identifier
     * @param effectiveDate date when account becomes active
     * @return activated GL account response
     * @throws GLAccountNotFoundException if account not found
     */
    GLAccountResponse activateGLAccount(UUID glAccountId, LocalDateTime effectiveDate);

    /**
     * Deactivates a GL account, preventing future postings.
     * Requires account balance to be zero.
     * Sets deactivation date to now.
     *
     * @param glAccountId account identifier
     * @return deactivated GL account response
     * @throws GLAccountNotFoundException     if account not found
     * @throws AccountNotZeroBalanceException if account has non-zero balance
     */
    GLAccountResponse deactivateGLAccount(UUID glAccountId);

    /**
     * Archives a deactivated GL account, removing from active charts.
     * Can only archive accounts with INACTIVE status.
     * Note: Archival is a soft delete - the account record remains in database.
     *
     * @param glAccountId account identifier
     * @return archived GL account response
     * @throws GLAccountNotFoundException  if account not found
     * @throws AccountNotInactiveException if account is not INACTIVE
     */
    GLAccountResponse archiveGLAccount(UUID glAccountId);

    /**
     * Retrieves current balance (sum of posted debit/credit lines) for account.
     *
     * @param glAccountId account identifier
     * @return account balance response
     * @throws GLAccountNotFoundException if account not found
     */
    GLAccountBalanceResponse getAccountBalance(UUID glAccountId);

    /**
     * Lists GL accounts with pagination and filtering by status, type, etc.
     *
     * @param page   page number (0-based)
     * @param size   page size
     * @param sort   sort field (default: accountCode)
     * @param status filter by status (ACTIVE, INACTIVE, NOT_YET_ACTIVE) - optional
     * @return paginated list of GL accounts
     */
    GLAccountListResponse listGLAccounts(int page, int size, String sort, String status);

    /**
     * Validates GL account properties and constraints.
     * Checks account code format, type validity, and parent account existence.
     *
     * @param request account creation request
     * @throws IllegalArgumentException if validation fails
     */
    void validateGLAccount(GLAccountCreateRequest request);

    /**
     * Validates that an account is active and can accept postings on a given date.
     *
     * @param glAccountId     account identifier
     * @param transactionDate date of the transaction
     * @throws GLAccountNotFoundException if account not found
     * @throws IllegalArgumentException   if account is not active on transaction
     *                                    date
     */
    void validateAccountForPosting(UUID glAccountId, LocalDateTime transactionDate);

}