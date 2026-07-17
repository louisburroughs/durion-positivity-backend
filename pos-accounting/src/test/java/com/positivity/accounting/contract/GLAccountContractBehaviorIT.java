package com.positivity.accounting.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.accounting.BaseContractIntegrationTest;
import com.positivity.accounting.internal.dto.GLAccountBalanceResponse;
import com.positivity.accounting.internal.dto.GLAccountCreateRequest;
import com.positivity.accounting.internal.dto.GLAccountListResponse;
import com.positivity.accounting.internal.dto.GLAccountResponse;
import com.positivity.accounting.internal.dto.GLAccountUpdateRequest;
import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.internal.entity.JournalEntryLine;
import com.positivity.accounting.internal.enums.AccountSubtype;
import com.positivity.accounting.internal.enums.AccountType;
import com.positivity.accounting.internal.enums.GLAccountStatus;
import com.positivity.accounting.internal.exception.AccountNotInactiveException;
import com.positivity.accounting.internal.exception.AccountNotZeroBalanceException;
import com.positivity.accounting.internal.exception.DuplicateAccountCodeException;
import com.positivity.accounting.internal.exception.GLAccountNotFoundException;
import com.positivity.accounting.service.GLAccountService;
import com.positivity.accounting.service.JournalEntryService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * Contract behavior tests for GL Account operations (CAP-050).
 *
 * Tests verify:
 * - Happy path CRUD operations
 * - Account lifecycle (activate, deactivate, archive)
 * - Validation rules (duplicate codes, zero balance requirement)
 * - Derived status calculation
 * - Balance queries
 */
@Transactional
public class GLAccountContractBehaviorIT extends BaseContractIntegrationTest {
    private static final Clock TEST_CLOCK = Clock.systemUTC();

    @Autowired
    private GLAccountService glAccountService;

    @Autowired
    private JournalEntryService journalEntryService;

    @Test
    @DisplayName("Create GL account - happy path")
    void testCreateGLAccount_Success() {
        // Given
        GLAccountCreateRequest request = GLAccountCreateRequest.builder()
                .accountCode("1000")
                .accountName("Cash")
                .accountType(AccountType.ASSET)
                .accountSubtype(AccountSubtype.BANK_CASH)
                .reconcilable(true)
                .description("Primary cash account")
                .build();

        // When
        GLAccountResponse response = glAccountService.createGLAccount(request);

        // Then
        assertThat(response).isNotNull();
        assertThat(response.getGlAccountId()).isNotNull();
        assertThat(response.getAccountCode()).isEqualTo("1000");
        assertThat(response.getAccountName()).isEqualTo("Cash");
        assertThat(response.getAccountType()).isEqualTo(AccountType.ASSET);
        assertThat(response.getAccountSubtype()).isEqualTo(AccountSubtype.BANK_CASH);
        assertThat(response.isReconcilable()).isTrue();
        assertThat(response.getDescription()).isEqualTo("Primary cash account");
        assertThat(response.getActivationDate()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(GLAccountStatus.ACTIVE);

        // And the metadata survives a fresh read
        GLAccountResponse retrieved = glAccountService.getGLAccount(response.getGlAccountId());
        assertThat(retrieved.getAccountSubtype()).isEqualTo(AccountSubtype.BANK_CASH);
        assertThat(retrieved.isReconcilable()).isTrue();
    }

    @Test
    @DisplayName("Create GL account - accountSubtype defaults to null and reconcilable to false")
    void testCreateGLAccount_MetadataDefaults() {
        // Given - request without accountSubtype/reconcilable
        GLAccountCreateRequest request = GLAccountCreateRequest.builder()
                .accountCode("1010")
                .accountName("Petty Cash")
                .accountType(AccountType.ASSET)
                .build();

        // When
        GLAccountResponse response = glAccountService.createGLAccount(request);

        // Then
        assertThat(response.getAccountSubtype()).isNull();
        assertThat(response.isReconcilable()).isFalse();

        GLAccountResponse retrieved = glAccountService.getGLAccount(response.getGlAccountId());
        assertThat(retrieved.getAccountSubtype()).isNull();
        assertThat(retrieved.isReconcilable()).isFalse();
    }

    @Test
    @DisplayName("Create GL account - duplicate code rejected")
    void testCreateGLAccount_DuplicateCode() {
        // Given - create first account
        GLAccountCreateRequest request1 = GLAccountCreateRequest.builder()
                .accountCode("2000")
                .accountName("Accounts Receivable")
                .accountType(AccountType.ASSET)
                .build();
        glAccountService.createGLAccount(request1);

        // When/Then - attempt to create duplicate
        GLAccountCreateRequest request2 = GLAccountCreateRequest.builder()
                .accountCode("2000") // duplicate
                .accountName("Duplicate AR")
                .accountType(AccountType.ASSET)
                .build();

        assertThatThrownBy(() -> glAccountService.createGLAccount(request2))
                .isInstanceOf(DuplicateAccountCodeException.class)
                .hasMessageContaining("2000");
    }

    @Test
    @DisplayName("Retrieve GL account by ID - happy path")
    void testGetGLAccount_Success() {
        // Given - create account
        GLAccountCreateRequest request = GLAccountCreateRequest.builder()
                .accountCode("3000")
                .accountName("Inventory")
                .accountType(AccountType.ASSET)
                .build();
        GLAccountResponse created = glAccountService.createGLAccount(request);

        // When
        GLAccountResponse retrieved = glAccountService.getGLAccount(created.getGlAccountId());

        // Then
        assertThat(retrieved).isNotNull();
        assertThat(retrieved.getGlAccountId()).isEqualTo(created.getGlAccountId());
        assertThat(retrieved.getAccountCode()).isEqualTo("3000");
        assertThat(retrieved.getStatus()).isEqualTo(GLAccountStatus.ACTIVE);
    }

    @Test
    @DisplayName("Retrieve GL account - not found")
    void testGetGLAccount_NotFound() {
        // Given
        UUID nonExistentId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        // When/Then
        assertThatThrownBy(() -> glAccountService.getGLAccount(nonExistentId))
                .isInstanceOf(GLAccountNotFoundException.class)
                .hasMessageContaining(nonExistentId.toString());
    }

    @Test
    @DisplayName("Update GL account - happy path")
    void testUpdateGLAccount_Success() {
        // Given - create account
        GLAccountCreateRequest createRequest = GLAccountCreateRequest.builder()
                .accountCode("4000")
                .accountName("Equipment")
                .accountType(AccountType.ASSET)
                .description("Original description")
                .build();
        GLAccountResponse created = glAccountService.createGLAccount(createRequest);

        // When - update mutable fields
        GLAccountUpdateRequest updateRequest = GLAccountUpdateRequest.builder()
                .accountName("Equipment - Updated")
                .description("Updated description")
                .build();
        GLAccountResponse updated = glAccountService.updateGLAccount(created.getGlAccountId(), updateRequest);

        // Then
        assertThat(updated.getAccountName()).isEqualTo("Equipment - Updated");
        assertThat(updated.getDescription()).isEqualTo("Updated description");
        assertThat(updated.getAccountCode()).isEqualTo("4000"); // immutable
        assertThat(updated.getAccountType()).isEqualTo(AccountType.ASSET); // immutable
    }

    @Test
    @DisplayName("Update GL account - accountSubtype and reconcilable round-trip")
    void testUpdateGLAccount_MetadataRoundTrip() {
        // Given - account created without metadata
        GLAccountResponse created = glAccountService.createGLAccount(GLAccountCreateRequest.builder()
                .accountCode("4100")
                .accountName("Undeposited Funds")
                .accountType(AccountType.ASSET)
                .build());
        assertThat(created.getAccountSubtype()).isNull();
        assertThat(created.isReconcilable()).isFalse();

        // When - metadata-only update
        GLAccountUpdateRequest updateRequest = GLAccountUpdateRequest.builder()
                .accountSubtype(AccountSubtype.UNDEPOSITED_FUNDS)
                .reconcilable(true)
                .build();
        GLAccountResponse updated = glAccountService.updateGLAccount(created.getGlAccountId(), updateRequest);

        // Then - metadata applied, other fields untouched
        assertThat(updated.getAccountSubtype()).isEqualTo(AccountSubtype.UNDEPOSITED_FUNDS);
        assertThat(updated.isReconcilable()).isTrue();
        assertThat(updated.getAccountName()).isEqualTo("Undeposited Funds");
        assertThat(updated.getAccountCode()).isEqualTo("4100");

        // And visible on subsequent GET
        GLAccountResponse retrieved = glAccountService.getGLAccount(created.getGlAccountId());
        assertThat(retrieved.getAccountSubtype()).isEqualTo(AccountSubtype.UNDEPOSITED_FUNDS);
        assertThat(retrieved.isReconcilable()).isTrue();
    }

    @Test
    @DisplayName("Activate GL account - happy path")
    void testActivateGLAccount_Success() {
        // Given - create account with future activation date
        GLAccountCreateRequest request = GLAccountCreateRequest.builder()
                .accountCode("5000")
                .accountName("Future Account")
                .accountType(AccountType.ASSET)
                .activationDate(LocalDateTime.now(TEST_CLOCK).plusDays(10))
                .build();
        GLAccountResponse created = glAccountService.createGLAccount(request);
        assertThat(created.getStatus()).isEqualTo(GLAccountStatus.NOT_YET_ACTIVE);

        // When - activate now
        GLAccountResponse activated = glAccountService.activateGLAccount(created.getGlAccountId());

        // Then
        assertThat(activated.getStatus()).isEqualTo(GLAccountStatus.ACTIVE);
        assertThat(activated.getActivationDate()).isBeforeOrEqualTo(LocalDateTime.now(TEST_CLOCK));
    }

    @Test
    @DisplayName("Deactivate GL account - happy path (zero balance)")
    void testDeactivateGLAccount_Success() {
        // Given - create active account with zero balance
        GLAccountCreateRequest request = GLAccountCreateRequest.builder()
                .accountCode("6000")
                .accountName("Temp Account")
                .accountType(AccountType.EXPENSE)
                .build();
        GLAccountResponse created = glAccountService.createGLAccount(request);

        // When
        GLAccountResponse deactivated = glAccountService.deactivateGLAccount(created.getGlAccountId());

        // Then
        assertThat(deactivated.getStatus()).isEqualTo(GLAccountStatus.INACTIVE);
        assertThat(deactivated.getDeactivationDate()).isNotNull();
        assertThat(deactivated.getDeactivationDate()).isBeforeOrEqualTo(LocalDateTime.now(TEST_CLOCK));
    }

    @Test
    @DisplayName("Deactivate GL account - non-zero balance rejected")
    void testDeactivateGLAccount_NonZeroBalance() {
        // Given — create the account under test
        GLAccountResponse created = glAccountService.createGLAccount(GLAccountCreateRequest.builder()
                .accountCode("7000")
                .accountName("Account with Balance")
                .accountType(AccountType.ASSET)
                .build());

        // A second active account to hold the offsetting credit (entry must be balanced)
        GLAccountResponse counterpart = glAccountService.createGLAccount(GLAccountCreateRequest.builder()
                .accountCode("7001")
                .accountName("Counterpart Account")
                .accountType(AccountType.LIABILITY)
                .build());

        // Build a balanced journal entry: debit 500.00 to account 7000
        JournalEntryLine debitLine = new JournalEntryLine();
        debitLine.setLineNumber(1);
        debitLine.setGlAccountId(created.getGlAccountId());
        debitLine.setDebitAmount(new BigDecimal("500.00"));
        debitLine.setCreditAmount(BigDecimal.ZERO);
        debitLine.setDescription("Test debit to 7000");

        JournalEntryLine creditLine = new JournalEntryLine();
        creditLine.setLineNumber(2);
        creditLine.setGlAccountId(counterpart.getGlAccountId());
        creditLine.setDebitAmount(BigDecimal.ZERO);
        creditLine.setCreditAmount(new BigDecimal("500.00"));
        creditLine.setDescription("Offsetting credit to 7001");

        JournalEntry entry = new JournalEntry();
        entry.setTransactionDate(LocalDateTime.now(TEST_CLOCK));
        entry.setDescription("Non-zero balance setup for deactivation test");
        entry.setLines(List.of(debitLine, creditLine));

        JournalEntry draft = journalEntryService.createJournalEntry(entry);
        journalEntryService.postJournalEntry(draft.getJournalEntryId());

        // When / Then — deactivation must fail because the account has a 500.00 debit balance
        UUID accountId = created.getGlAccountId();
        assertThatThrownBy(() -> glAccountService.deactivateGLAccount(accountId))
                .isInstanceOf(AccountNotZeroBalanceException.class)
                .hasMessageContaining("7000")
                .hasMessageContaining("500");

        // Account must remain ACTIVE — state should not have changed
        GLAccountResponse unchanged = glAccountService.getGLAccount(accountId);
        assertThat(unchanged.getStatus()).isEqualTo(GLAccountStatus.ACTIVE);
        assertThat(unchanged.getDeactivationDate()).isNull();
    }

    @Test
    @DisplayName("Archive GL account - happy path")
    void testArchiveGLAccount_Success() {
        // Given - create and deactivate account
        GLAccountCreateRequest request = GLAccountCreateRequest.builder()
                .accountCode("8000")
                .accountName("Old Account")
                .accountType(AccountType.EXPENSE)
                .build();
        GLAccountResponse created = glAccountService.createGLAccount(request);
        GLAccountResponse deactivated = glAccountService.deactivateGLAccount(created.getGlAccountId());

        // When
        GLAccountResponse archived = glAccountService.archiveGLAccount(deactivated.getGlAccountId());

        // Then
        assertThat(archived.getDescription()).contains("[ARCHIVED]");
        assertThat(archived.getStatus()).isEqualTo(GLAccountStatus.INACTIVE);
    }

    @Test
    @DisplayName("Archive GL account - not inactive rejected")
    void testArchiveGLAccount_NotInactive() {
        // Given - create active account
        GLAccountCreateRequest request = GLAccountCreateRequest.builder()
                .accountCode("9000")
                .accountName("Active Account")
                .accountType(AccountType.REVENUE)
                .build();
        GLAccountResponse created = glAccountService.createGLAccount(request);

        // When/Then - attempt to archive active account
        assertThatThrownBy(() -> glAccountService.archiveGLAccount(created.getGlAccountId()))
                .isInstanceOf(AccountNotInactiveException.class)
                .hasMessageContaining("9000");
    }

    @Test
    @DisplayName("Get account balance - zero balance")
    void testGetAccountBalance_ZeroBalance() {
        // Given - create account with no postings
        GLAccountCreateRequest request = GLAccountCreateRequest.builder()
                .accountCode("1100")
                .accountName("Cash - Test Balance")
                .accountType(AccountType.ASSET)
                .build();
        GLAccountResponse created = glAccountService.createGLAccount(request);

        // When
        GLAccountBalanceResponse balance = glAccountService.getAccountBalance(created.getGlAccountId());

        // Then
        assertThat(balance).isNotNull();
        assertThat(balance.getGlAccountId()).isEqualTo(created.getGlAccountId());
        assertThat(balance.getAccountCode()).isEqualTo("1100");
        assertThat(balance.getBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(balance.getAsOfDate()).isNotNull();
    }

    @Test
    @DisplayName("List GL accounts - pagination")
    void testListGLAccounts_Pagination() {
        // Given - create multiple accounts
        for (int i = 0; i < 5; i++) {
            GLAccountCreateRequest request = GLAccountCreateRequest.builder()
                    .accountCode("1" + i + "00")
                    .accountName("Test Account " + i)
                    .accountType(AccountType.ASSET)
                    .build();
            glAccountService.createGLAccount(request);
        }

        // When
        GLAccountListResponse response = glAccountService.listGLAccounts(0, 3, "accountCode", null);

        // Then
        assertThat(response).isNotNull();
        assertThat(response.getResults()).hasSizeGreaterThanOrEqualTo(3);
        assertThat(response.getPageNumber()).isEqualTo(0);
        assertThat(response.getPageSize()).isEqualTo(3);
    }

    @Test
    @DisplayName("List GL accounts - filter by ACTIVE status")
    void testListGLAccounts_FilterByStatus() {
        // Given - create active and inactive accounts
        GLAccountCreateRequest activeRequest = GLAccountCreateRequest.builder()
                .accountCode("1200")
                .accountName("Active Account")
                .accountType(AccountType.ASSET)
                .build();
        glAccountService.createGLAccount(activeRequest);

        GLAccountCreateRequest inactiveRequest = GLAccountCreateRequest.builder()
                .accountCode("1300")
                .accountName("Inactive Account")
                .accountType(AccountType.ASSET)
                .build();
        GLAccountResponse inactive = glAccountService.createGLAccount(inactiveRequest);
        glAccountService.deactivateGLAccount(inactive.getGlAccountId());

        // When - filter by ACTIVE
        GLAccountListResponse response = glAccountService.listGLAccounts(0, 10, "accountCode", "ACTIVE");

        // Then
        assertThat(response.getResults()).isNotEmpty();
        assertThat(response.getResults()).allMatch(account -> account.getStatus() == GLAccountStatus.ACTIVE);
    }

    @Test
    @DisplayName("Derived status - ACTIVE")
    void testDerivedStatus_Active() {
        // Given - account with activation date in past, no deactivation
        GLAccountCreateRequest request = GLAccountCreateRequest.builder()
                .accountCode("2100")
                .accountName("Currently Active")
                .accountType(AccountType.ASSET)
                .activationDate(LocalDateTime.now(TEST_CLOCK).minusDays(1))
                .build();

        // When
        GLAccountResponse created = glAccountService.createGLAccount(request);

        // Then
        assertThat(created.getStatus()).isEqualTo(GLAccountStatus.ACTIVE);
    }

    @Test
    @DisplayName("Derived status - NOT_YET_ACTIVE")
    void testDerivedStatus_NotYetActive() {
        // Given - account with activation date in future
        GLAccountCreateRequest request = GLAccountCreateRequest.builder()
                .accountCode("2200")
                .accountName("Future Active")
                .accountType(AccountType.ASSET)
                .activationDate(LocalDateTime.now(TEST_CLOCK).plusDays(5))
                .build();

        // When
        GLAccountResponse created = glAccountService.createGLAccount(request);

        // Then
        assertThat(created.getStatus()).isEqualTo(GLAccountStatus.NOT_YET_ACTIVE);
    }

    @Test
    @DisplayName("Derived status - INACTIVE")
    void testDerivedStatus_Inactive() {
        // Given - create and immediately deactivate account
        GLAccountCreateRequest request = GLAccountCreateRequest.builder()
                .accountCode("2300")
                .accountName("Quickly Deactivated")
                .accountType(AccountType.EXPENSE)
                .build();
        GLAccountResponse created = glAccountService.createGLAccount(request);

        // When
        GLAccountResponse deactivated = glAccountService.deactivateGLAccount(created.getGlAccountId());

        // Then
        assertThat(deactivated.getStatus()).isEqualTo(GLAccountStatus.INACTIVE);
    }

    @Test
    @DisplayName("Validate account for posting - active account allowed")
    void testValidateAccountForPosting_Active() {
        // Given - create active account
        GLAccountCreateRequest request = GLAccountCreateRequest.builder()
                .accountCode("3100")
                .accountName("Postable Account")
                .accountType(AccountType.EXPENSE)
                .build();
        GLAccountResponse created = glAccountService.createGLAccount(request);

        // When/Then - should not throw
        glAccountService.validateAccountForPosting(created.getGlAccountId(), LocalDateTime.now(TEST_CLOCK));
    }

    @Test
    @DisplayName("Validate account for posting - inactive account rejected")
    void testValidateAccountForPosting_Inactive() {
        // Given - create and deactivate account
        GLAccountCreateRequest request = GLAccountCreateRequest.builder()
                .accountCode("3200")
                .accountName("Inactive Account")
                .accountType(AccountType.EXPENSE)
                .build();
        GLAccountResponse created = glAccountService.createGLAccount(request);
        glAccountService.deactivateGLAccount(created.getGlAccountId());

        // When/Then - should throw
        assertThatThrownBy(() -> glAccountService.validateAccountForPosting(
                        created.getGlAccountId(), LocalDateTime.now(TEST_CLOCK).plusDays(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inactive");
    }
}
