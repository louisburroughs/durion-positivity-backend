package com.positivity.accounting.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.positivity.accounting.internal.dto.GLAccountBalanceResponse;
import com.positivity.accounting.internal.dto.GLAccountCreateRequest;
import com.positivity.accounting.internal.dto.GLAccountListResponse;
import com.positivity.accounting.internal.dto.GLAccountResponse;
import com.positivity.accounting.internal.dto.GLAccountUpdateRequest;
import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.accounting.internal.enums.GLAccountStatus;
import com.positivity.accounting.internal.exception.AccountNotInactiveException;
import com.positivity.accounting.internal.exception.AccountNotZeroBalanceException;
import com.positivity.accounting.internal.exception.DuplicateAccountCodeException;
import com.positivity.accounting.internal.exception.GLAccountNotFoundException;
import com.positivity.accounting.internal.repository.GLAccountRepository;
import com.positivity.accounting.internal.repository.JournalEntryLineRepository;

import lombok.RequiredArgsConstructor;

/**
 * Service for GL Account (Chart of Accounts) management.
 * Handles CRUD operations, activation/deactivation, effective dating, and
 * archival.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class GLAccountService {

    private static final Logger log = LoggerFactory.getLogger(GLAccountService.class);

    private final GLAccountRepository glAccountRepository;
    private final JournalEntryLineRepository journalEntryLineRepository;

    /**
     * Creates a new GL account.
     * Account must have valid account code, name, and type.
     * Validates account code uniqueness.
     *
     * @param request account creation details
     * @return created GL account response
     * @throws DuplicateAccountCodeException if account code already exists
     */
    public GLAccountResponse createGLAccount(@NonNull GLAccountCreateRequest request) {
        log.info("Creating GL account: code={}, name={}, type={}",
                request.getAccountCode(), request.getAccountName(), request.getAccountType());

        // Validate account code uniqueness
        if (glAccountRepository.existsByAccountCode(request.getAccountCode())) {
            String msg = "Account code '" + request.getAccountCode() + "' already exists";
            log.warn(msg);
            throw new DuplicateAccountCodeException(msg);
        }

        // Create new account entity
        GLAccount account = new GLAccount();
        account.setAccountCode(request.getAccountCode());
        account.setAccountName(request.getAccountName());
        account.setAccountType(request.getAccountType());
        account.setDescription(request.getDescription());
        account.setParentAccountId(request.getParentAccountId());

        // Set activation date (default to now if not provided)
        if (request.getActivationDate() != null) {
            account.setActivationDate(request.getActivationDate());
        } else {
            account.setActivationDate(LocalDateTime.now());
        }

        // Set audit fields
        account.setCreatedBy("SYSTEM"); // TODO: Get from security context
        account.setModifiedBy("SYSTEM");

        account = glAccountRepository.save(account);

        log.info("Created GL account: id={}, code={}", account.getGlAccountId(), account.getAccountCode());

        return toResponse(account);
    }

    /**
     * Retrieves a GL account by ID with current status (derived from dates).
     *
     * @param glAccountId account identifier
     * @return GL account response with derived status
     * @throws GLAccountNotFoundException if account not found
     */
    public GLAccountResponse getGLAccount(@NonNull UUID glAccountId) {
        log.debug("Retrieving GL account: id={}", glAccountId);

        GLAccount account = glAccountRepository.findById(glAccountId)
                .orElseThrow(() -> new GLAccountNotFoundException("GL account not found: " + glAccountId));

        return toResponse(account);
    }

    /**
     * Updates GL account properties (name, description).
     * Account type and code are immutable after creation.
     *
     * @param glAccountId account identifier
     * @param request     update details
     * @return updated GL account response
     * @throws GLAccountNotFoundException if account not found
     */
    public GLAccountResponse updateGLAccount(@NonNull UUID glAccountId, @NonNull GLAccountUpdateRequest request) {
        log.info("Updating GL account: id={}", glAccountId);

        GLAccount account = glAccountRepository.findById(glAccountId)
                .orElseThrow(() -> new GLAccountNotFoundException("GL account not found: " + glAccountId));

        // Update mutable fields
        if (request.getAccountName() != null) {
            account.setAccountName(request.getAccountName());
        }
        if (request.getDescription() != null) {
            account.setDescription(request.getDescription());
        }

        account.setModifiedBy("SYSTEM"); // TODO: Get from security context

        account = glAccountRepository.save(account);

        log.info("Updated GL account: id={}, code={}", account.getGlAccountId(), account.getAccountCode());

        return toResponse(account);
    }

    /**
     * Activates a GL account, making it available for posting.
     * Sets activation date to now if not already set.
     *
     * @param glAccountId account identifier
     * @return activated GL account response
     * @throws GLAccountNotFoundException if account not found
     */
    public GLAccountResponse activateGLAccount(@NonNull UUID glAccountId) {
        log.info("Activating GL account: id={}", glAccountId);

        GLAccount account = glAccountRepository.findById(glAccountId)
                .orElseThrow(() -> new GLAccountNotFoundException("GL account not found: " + glAccountId));

        // Set activation date if not already set
        if (account.getActivationDate() == null) {
            account.setActivationDate(LocalDateTime.now());
        }

        // Clear deactivation date to reactivate
        account.setDeactivationDate(null);
        account.setModifiedBy("SYSTEM"); // TODO: Get from security context

        account = glAccountRepository.save(account);

        log.info("Activated GL account: id={}, code={}", account.getGlAccountId(), account.getAccountCode());

        return toResponse(account);
    }

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
    public GLAccountResponse deactivateGLAccount(@NonNull UUID glAccountId) {
        log.info("Deactivating GL account: id={}", glAccountId);

        GLAccount account = glAccountRepository.findById(glAccountId)
                .orElseThrow(() -> new GLAccountNotFoundException("GL account not found: " + glAccountId));

        // Verify zero balance
        BigDecimal balance = journalEntryLineRepository.getAccountBalance(glAccountId);
        if (balance.compareTo(BigDecimal.ZERO) != 0) {
            String msg = "Cannot deactivate account " + account.getAccountCode() + " with non-zero balance: " + balance;
            log.warn(msg);
            throw new AccountNotZeroBalanceException(msg);
        }

        account.setDeactivationDate(LocalDateTime.now());
        account.setModifiedBy("SYSTEM"); // TODO: Get from security context

        account = glAccountRepository.save(account);

        log.info("Deactivated GL account: id={}, code={}", account.getGlAccountId(), account.getAccountCode());

        return toResponse(account);
    }

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
    public GLAccountResponse archiveGLAccount(@NonNull UUID glAccountId) {
        log.info("Archiving GL account: id={}", glAccountId);

        GLAccount account = glAccountRepository.findById(glAccountId)
                .orElseThrow(() -> new GLAccountNotFoundException("GL account not found: " + glAccountId));

        // Verify account is INACTIVE
        String status = account.getDerivedStatus();
        if (!"INACTIVE".equals(status)) {
            String msg = "Cannot archive account " + account.getAccountCode() + " with status " + status +
                    ". Account must be INACTIVE to archive.";
            log.warn(msg);
            throw new AccountNotInactiveException(msg);
        }

        // Archival is represented by setting description to indicate archived status
        // In production, you might have a separate 'archived' boolean field or status
        if (account.getDescription() == null) {
            account.setDescription("[ARCHIVED]");
        } else if (!account.getDescription().contains("[ARCHIVED]")) {
            account.setDescription("[ARCHIVED] " + account.getDescription());
        }

        account.setModifiedBy("SYSTEM"); // TODO: Get from security context

        account = glAccountRepository.save(account);

        log.info("Archived GL account: id={}, code={}", account.getGlAccountId(), account.getAccountCode());

        return toResponse(account);
    }

    /**
     * Retrieves current balance (sum of posted debit/credit lines) for account.
     *
     * @param glAccountId account identifier
     * @return account balance response
     * @throws GLAccountNotFoundException if account not found
     */
    public GLAccountBalanceResponse getAccountBalance(@NonNull UUID glAccountId) {
        log.debug("Getting balance for GL account: id={}", glAccountId);

        GLAccount account = glAccountRepository.findById(glAccountId)
                .orElseThrow(() -> new GLAccountNotFoundException("GL account not found: " + glAccountId));

        BigDecimal balance = journalEntryLineRepository.getAccountBalance(glAccountId);

        GLAccountBalanceResponse response = new GLAccountBalanceResponse();
        response.setGlAccountId(account.getGlAccountId());
        response.setAccountCode(account.getAccountCode());
        response.setAccountName(account.getAccountName());
        response.setBalance(balance);
        response.setAsOfDate(Instant.now());

        return response;
    }

    /**
     * Lists GL accounts with pagination and filtering by status, type, etc.
     *
     * @param page   page number (0-based)
     * @param size   page size
     * @param sort   sort field (default: accountCode)
     * @param status filter by status (ACTIVE, INACTIVE, NOT_YET_ACTIVE) - optional
     * @return paginated list of GL accounts
     */
    public GLAccountListResponse listGLAccounts(int page, int size, String sort, String status) {
        log.debug("Listing GL accounts: page={}, size={}, sort={}, status={}", page, size, sort, status);

        // Build pageable with sorting
        Sort sortOrder = Sort.by(sort != null ? sort : "accountCode");
        Pageable pageable = PageRequest.of(page, size, sortOrder);

        // Fetch all accounts (filtering by status requires derived field)
        Page<GLAccount> accountPage = glAccountRepository.findAll(pageable);

        // Filter by status if provided
        List<GLAccountResponse> filteredResults = accountPage.getContent().stream()
                .map(this::toResponse)
                .filter(response -> status == null || status.isBlank() ||
                        response.getStatus().name().equalsIgnoreCase(status))
                .collect(Collectors.toList());

        // Note: This in-memory filtering affects pagination accuracy
        // For production, consider adding a computed column or view for status

        GLAccountListResponse response = new GLAccountListResponse();
        response.setResults(filteredResults);
        response.setTotalCount(accountPage.getTotalElements());
        response.setPageNumber(accountPage.getNumber());
        response.setPageSize(accountPage.getSize());
        response.setTotalPages(accountPage.getTotalPages());

        return response;
    }

    /**
     * Validates GL account properties and constraints.
     * Checks account code format, type validity, and parent account existence.
     *
     * @param request account creation request
     * @throws IllegalArgumentException if validation fails
     */
    public void validateGLAccount(@NonNull GLAccountCreateRequest request) {
        log.debug("Validating GL account request: code={}", request.getAccountCode());

        if (request.getAccountCode() == null || request.getAccountCode().isBlank()) {
            throw new IllegalArgumentException("Account code is required");
        }

        if (request.getAccountName() == null || request.getAccountName().isBlank()) {
            throw new IllegalArgumentException("Account name is required");
        }

        if (request.getAccountType() == null) {
            throw new IllegalArgumentException("Account type is required");
        }

        // Validate parent account exists if specified
        if (request.getParentAccountId() != null) {
            if (!glAccountRepository.existsById(request.getParentAccountId())) {
                throw new IllegalArgumentException("Parent account not found: " + request.getParentAccountId());
            }
        }
    }

    /**
     * Validates that an account is active and can accept postings on a given date.
     *
     * @param glAccountId     account identifier
     * @param transactionDate date of the transaction
     * @throws GLAccountNotFoundException if account not found
     * @throws IllegalArgumentException   if account is not active on transaction
     *                                    date
     */
    public void validateAccountForPosting(@NonNull UUID glAccountId, @NonNull LocalDateTime transactionDate) {
        log.debug("Validating GL account for posting: id={}, date={}", glAccountId, transactionDate);

        GLAccount account = glAccountRepository.findById(glAccountId)
                .orElseThrow(() -> new GLAccountNotFoundException("GL account not found: " + glAccountId));

        // Check if account is active on transaction date
        if (account.getActivationDate() != null && account.getActivationDate().isAfter(transactionDate)) {
            throw new IllegalArgumentException("Account " + account.getAccountCode() +
                    " is not yet active on " + transactionDate);
        }

        if (account.getDeactivationDate() != null && !account.getDeactivationDate().isAfter(transactionDate)) {
            throw new IllegalArgumentException("Account " + account.getAccountCode() +
                    " is inactive as of " + transactionDate);
        }
    }

    /**
     * Converts GL account entity to response DTO with derived status.
     */
    private GLAccountResponse toResponse(GLAccount account) {
        GLAccountResponse response = new GLAccountResponse();
        response.setGlAccountId(account.getGlAccountId());
        response.setAccountCode(account.getAccountCode());
        response.setAccountName(account.getAccountName());
        response.setAccountType(account.getAccountType());
        response.setDescription(account.getDescription());
        response.setParentAccountId(account.getParentAccountId());
        response.setActivationDate(account.getActivationDate());
        response.setDeactivationDate(account.getDeactivationDate());
        response.setStatus(GLAccountStatus.valueOf(account.getDerivedStatus()));
        response.setCreatedAt(account.getCreatedAt());
        response.setCreatedBy(account.getCreatedBy());
        response.setModifiedAt(account.getModifiedAt());
        response.setModifiedBy(account.getModifiedBy());
        response.setVersion(account.getVersion());
        return response;
    }
}
