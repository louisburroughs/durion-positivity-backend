package com.positivity.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import com.positivity.accounting.internal.dto.GLAccountBalanceResponse;
import com.positivity.accounting.internal.dto.GLAccountCreateRequest;
import com.positivity.accounting.internal.dto.GLAccountListResponse;
import com.positivity.accounting.internal.dto.GLAccountResponse;
import com.positivity.accounting.internal.dto.GLAccountUpdateRequest;
import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.accounting.internal.enums.AccountType;
import com.positivity.accounting.internal.enums.GLAccountStatus;
import com.positivity.accounting.internal.exception.AccountNotInactiveException;
import com.positivity.accounting.internal.exception.AccountNotZeroBalanceException;
import com.positivity.accounting.internal.exception.DuplicateAccountCodeException;
import com.positivity.accounting.internal.exception.GLAccountNotFoundException;
import com.positivity.accounting.internal.repository.GLAccountRepository;
import com.positivity.accounting.internal.repository.JournalEntryLineRepository;
import com.positivity.accounting.internal.service.GLAccountServiceImpl;

/**
 * Unit tests for GLAccountServiceImpl.
 *
 * Tests GL account lifecycle: create, read, update, activate/deactivate, archive,
 * balance queries, listing, and validation.
 *
 * Note: SecurityContextHelper.getCurrentUsernameOrDefault returns "SYSTEM" when
 * no security context is set (unit test environment without authentication).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GLAccountService Unit Tests")
class GLAccountServiceTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Spy
    Clock clock = TEST_CLOCK;

    @Mock
    private GLAccountRepository glAccountRepository;

    @Mock
    private JournalEntryLineRepository journalEntryLineRepository;

    @InjectMocks
    private GLAccountServiceImpl service;

    private UUID testAccountId;
    private UUID testParentId;
    private GLAccount testAccount;

    @BeforeEach
    void setUp() {
        testAccountId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        testParentId = UUID.fromString("00000000-0000-0000-0000-000000000002");

        testAccount = new GLAccount();
        testAccount.setGlAccountId(testAccountId);
        testAccount.setAccountCode("1000");
        testAccount.setAccountName("Cash");
        testAccount.setAccountType(AccountType.ASSET);
        testAccount.setDescription("Primary cash account");
        testAccount.setActivationDate(LocalDateTime.of(2023, 1, 1, 0, 0));
        testAccount.setVersion(1);
    }

    // ===== CREATE =====

    @Nested
    @DisplayName("createGLAccount")
    class CreateGLAccount {

        @Test
        @DisplayName("creates account successfully when code is unique")
        void success() {
            GLAccountCreateRequest request = GLAccountCreateRequest.builder()
                    .accountCode("1000")
                    .accountName("Cash")
                    .accountType(AccountType.ASSET)
                    .description("Primary cash")
                    .activationDate(LocalDateTime.of(2024, 1, 1, 0, 0))
                    .build();

            when(glAccountRepository.existsByAccountCode("1000")).thenReturn(false);
            when(glAccountRepository.save(any(GLAccount.class))).thenAnswer(inv -> {
                GLAccount a = inv.getArgument(0);
                a.setGlAccountId(testAccountId);
                return a;
            });

            GLAccountResponse result = service.createGLAccount(request);

            assertThat(result).isNotNull();
            assertThat(result.getAccountCode()).isEqualTo("1000");
            verify(glAccountRepository).existsByAccountCode("1000");
        }

        @Test
        @DisplayName("uses clock-based activation date when not provided in request")
        void defaultActivationDate() {
            GLAccountCreateRequest request = GLAccountCreateRequest.builder()
                    .accountCode("1001")
                    .accountName("Bank")
                    .accountType(AccountType.ASSET)
                    .activationDate(null)
                    .build();

            when(glAccountRepository.existsByAccountCode("1001")).thenReturn(false);
            when(glAccountRepository.save(any(GLAccount.class))).thenAnswer(inv -> {
                GLAccount a = inv.getArgument(0);
                a.setGlAccountId(testAccountId);
                return a;
            });

            GLAccountResponse result = service.createGLAccount(request);

            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("throws DuplicateAccountCodeException when code already exists")
        void duplicateCode() {
            GLAccountCreateRequest request = GLAccountCreateRequest.builder()
                    .accountCode("1000")
                    .accountName("Cash")
                    .accountType(AccountType.ASSET)
                    .activationDate(LocalDateTime.now())
                    .build();

            when(glAccountRepository.existsByAccountCode("1000")).thenReturn(true);

            assertThatThrownBy(() -> service.createGLAccount(request))
                    .isInstanceOf(DuplicateAccountCodeException.class)
                    .hasMessageContaining("already exists");
        }
    }

    // ===== GET =====

    @Nested
    @DisplayName("getGLAccount")
    class GetGLAccount {

        @Test
        @DisplayName("returns account response when found")
        void success() {
            when(glAccountRepository.findById(testAccountId))
                    .thenReturn(Optional.of(testAccount));

            GLAccountResponse result = service.getGLAccount(testAccountId);

            assertThat(result).isNotNull();
            assertThat(result.getAccountCode()).isEqualTo("1000");
            assertThat(result.getStatus()).isEqualTo(GLAccountStatus.ACTIVE);
        }

        @Test
        @DisplayName("throws GLAccountNotFoundException when not found")
        void notFound() {
            when(glAccountRepository.findById(testAccountId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getGLAccount(testAccountId))
                    .isInstanceOf(GLAccountNotFoundException.class);
        }
    }

    // ===== UPDATE =====

    @Test
    @DisplayName("updateGLAccount - updates name and description")
    void updateGLAccount_success() {
        GLAccountUpdateRequest request = GLAccountUpdateRequest.builder()
                .accountName("Updated Cash")
                .description("Updated description")
                .build();

        when(glAccountRepository.findById(testAccountId)).thenReturn(Optional.of(testAccount));
        when(glAccountRepository.save(any(GLAccount.class))).thenReturn(testAccount);

        GLAccountResponse result = service.updateGLAccount(testAccountId, request);

        assertThat(result).isNotNull();
        verify(glAccountRepository).save(testAccount);
    }

    @Test
    @DisplayName("updateGLAccount - updates only name when description is null")
    void updateGLAccount_onlyName() {
        GLAccountUpdateRequest request = GLAccountUpdateRequest.builder()
                .accountName("New Name")
                .build();

        when(glAccountRepository.findById(testAccountId)).thenReturn(Optional.of(testAccount));
        when(glAccountRepository.save(any(GLAccount.class))).thenReturn(testAccount);

        GLAccountResponse result = service.updateGLAccount(testAccountId, request);

        assertThat(result).isNotNull();
    }

    // ===== ACTIVATE =====

    @Test
    @DisplayName("activateGLAccount - activates using clock-based date")
    void activateGLAccount_defaultDate() {
        when(glAccountRepository.findById(testAccountId)).thenReturn(Optional.of(testAccount));
        when(glAccountRepository.save(any(GLAccount.class))).thenReturn(testAccount);

        GLAccountResponse result = service.activateGLAccount(testAccountId);

        assertThat(result).isNotNull();
        verify(glAccountRepository).save(testAccount);
    }

    @Test
    @DisplayName("activateGLAccount - activates with effective date")
    void activateGLAccount_withEffectiveDate() {
        LocalDateTime effectiveDate = LocalDateTime.of(2024, 6, 1, 0, 0);
        when(glAccountRepository.findById(testAccountId)).thenReturn(Optional.of(testAccount));
        when(glAccountRepository.save(any(GLAccount.class))).thenReturn(testAccount);

        GLAccountResponse result = service.activateGLAccount(testAccountId, effectiveDate);

        assertThat(result).isNotNull();
        assertThat(testAccount.getActivationDate()).isEqualTo(effectiveDate);
        assertThat(testAccount.getDeactivationDate()).isNull();
    }

    // ===== DEACTIVATE =====

    @Nested
    @DisplayName("deactivateGLAccount")
    class DeactivateGLAccount {

        @Test
        @DisplayName("deactivates when balance is zero")
        void success() {
            when(glAccountRepository.findById(testAccountId)).thenReturn(Optional.of(testAccount));
            when(journalEntryLineRepository.getAccountBalance(testAccountId))
                    .thenReturn(BigDecimal.ZERO);
            when(glAccountRepository.save(any(GLAccount.class))).thenReturn(testAccount);

            GLAccountResponse result = service.deactivateGLAccount(testAccountId);

            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("throws AccountNotZeroBalanceException when balance is non-zero")
        void nonZeroBalance() {
            when(glAccountRepository.findById(testAccountId)).thenReturn(Optional.of(testAccount));
            when(journalEntryLineRepository.getAccountBalance(testAccountId))
                    .thenReturn(new BigDecimal("500.00"));

            assertThatThrownBy(() -> service.deactivateGLAccount(testAccountId))
                    .isInstanceOf(AccountNotZeroBalanceException.class)
                    .hasMessageContaining("non-zero balance");
        }
    }

    // ===== ARCHIVE =====

    @Nested
    @DisplayName("archiveGLAccount")
    class ArchiveGLAccount {

        @Test
        @DisplayName("archives INACTIVE account successfully (no existing description)")
        void success_noDescription() {
            testAccount.setDeactivationDate(LocalDateTime.of(2023, 6, 1, 0, 0));
            testAccount.setDescription(null);

            when(glAccountRepository.findById(testAccountId)).thenReturn(Optional.of(testAccount));
            when(glAccountRepository.save(any(GLAccount.class))).thenReturn(testAccount);

            GLAccountResponse result = service.archiveGLAccount(testAccountId);

            assertThat(result).isNotNull();
            assertThat(testAccount.getDescription()).contains("[ARCHIVED]");
        }

        @Test
        @DisplayName("archives INACTIVE account - prepends [ARCHIVED] to existing description")
        void success_withDescription() {
            testAccount.setDeactivationDate(LocalDateTime.of(2023, 6, 1, 0, 0));
            testAccount.setDescription("Original description");

            when(glAccountRepository.findById(testAccountId)).thenReturn(Optional.of(testAccount));
            when(glAccountRepository.save(any(GLAccount.class))).thenReturn(testAccount);

            service.archiveGLAccount(testAccountId);

            assertThat(testAccount.getDescription()).startsWith("[ARCHIVED]");
            assertThat(testAccount.getDescription()).contains("Original description");
        }

        @Test
        @DisplayName("no double-archive if already archived")
        void noDoubleArchive() {
            testAccount.setDeactivationDate(LocalDateTime.of(2023, 6, 1, 0, 0));
            testAccount.setDescription("[ARCHIVED] Original");

            when(glAccountRepository.findById(testAccountId)).thenReturn(Optional.of(testAccount));
            when(glAccountRepository.save(any(GLAccount.class))).thenReturn(testAccount);

            service.archiveGLAccount(testAccountId);

            // Description should still start with [ARCHIVED] but not doubled
            assertThat(testAccount.getDescription()).startsWith("[ARCHIVED]");
            assertThat(testAccount.getDescription()).doesNotContain("[ARCHIVED] [ARCHIVED]");
        }

        @Test
        @DisplayName("throws AccountNotInactiveException when account is not INACTIVE")
        void notInactive() {
            // Account has future activation date → NOT_YET_ACTIVE
            testAccount.setActivationDate(LocalDateTime.of(2099, 1, 1, 0, 0));

            when(glAccountRepository.findById(testAccountId)).thenReturn(Optional.of(testAccount));

            assertThatThrownBy(() -> service.archiveGLAccount(testAccountId))
                    .isInstanceOf(AccountNotInactiveException.class)
                    .hasMessageContaining("must be INACTIVE");
        }
    }

    // ===== BALANCE =====

    @Test
    @DisplayName("getAccountBalance - returns balance for existing account")
    void getAccountBalance_success() {
        when(glAccountRepository.findById(testAccountId)).thenReturn(Optional.of(testAccount));
        when(journalEntryLineRepository.getAccountBalance(testAccountId))
                .thenReturn(new BigDecimal("1500.00"));

        GLAccountBalanceResponse result = service.getAccountBalance(testAccountId);

        assertThat(result).isNotNull();
        assertThat(result.getBalance()).isEqualByComparingTo(new BigDecimal("1500.00"));
        assertThat(result.getAccountCode()).isEqualTo("1000");
    }

    // ===== LIST =====

    @Test
    @DisplayName("listGLAccounts - returns all accounts without status filter")
    void listGLAccounts_noFilter() {
        Page<GLAccount> page = new PageImpl<>(List.of(testAccount));
        when(glAccountRepository.findAll(any(Pageable.class))).thenReturn(page);

        GLAccountListResponse result = service.listGLAccounts(0, 10, "accountCode", null);

        assertThat(result).isNotNull();
        assertThat(result.getResults()).hasSize(1);
    }

    @Test
    @DisplayName("listGLAccounts - filters by status")
    void listGLAccounts_withStatusFilter() {
        Page<GLAccount> page = new PageImpl<>(List.of(testAccount));
        when(glAccountRepository.findAll(any(Pageable.class))).thenReturn(page);

        GLAccountListResponse result = service.listGLAccounts(0, 10, "accountCode", "ACTIVE");

        assertThat(result).isNotNull();
        assertThat(result.getResults()).hasSize(1);
        assertThat(result.getResults().get(0).getStatus()).isEqualTo(GLAccountStatus.ACTIVE);
    }

    @Test
    @DisplayName("listGLAccounts - excludes account not matching status filter")
    void listGLAccounts_statusFilterExcludes() {
        Page<GLAccount> page = new PageImpl<>(List.of(testAccount)); // ACTIVE account
        when(glAccountRepository.findAll(any(Pageable.class))).thenReturn(page);

        GLAccountListResponse result = service.listGLAccounts(0, 10, "accountCode", "INACTIVE");

        assertThat(result.getResults()).isEmpty(); // ACTIVE account filtered out
    }

    // ===== VALIDATE =====

    @Nested
    @DisplayName("validateGLAccount")
    class ValidateGLAccount {

        @Test
        @DisplayName("passes validation for valid request")
        void success() {
            GLAccountCreateRequest request = GLAccountCreateRequest.builder()
                    .accountCode("1000")
                    .accountName("Cash")
                    .accountType(AccountType.ASSET)
                    .activationDate(LocalDateTime.now())
                    .build();

            // Should not throw
            service.validateGLAccount(request);
        }

        @Test
        @DisplayName("throws when account code is blank")
        void blankCode() {
            GLAccountCreateRequest request = GLAccountCreateRequest.builder()
                    .accountCode("  ")
                    .accountName("Cash")
                    .accountType(AccountType.ASSET)
                    .activationDate(LocalDateTime.now())
                    .build();

            assertThatThrownBy(() -> service.validateGLAccount(request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Account code is required");
        }

        @Test
        @DisplayName("throws when account name is blank")
        void blankName() {
            GLAccountCreateRequest request = GLAccountCreateRequest.builder()
                    .accountCode("1000")
                    .accountName("  ")
                    .accountType(AccountType.ASSET)
                    .activationDate(LocalDateTime.now())
                    .build();

            assertThatThrownBy(() -> service.validateGLAccount(request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Account name is required");
        }

        @Test
        @DisplayName("throws when account type is null")
        void nullAccountType() {
            GLAccountCreateRequest request = GLAccountCreateRequest.builder()
                    .accountCode("1000")
                    .accountName("Cash")
                    .accountType(null)
                    .activationDate(LocalDateTime.now())
                    .build();

            assertThatThrownBy(() -> service.validateGLAccount(request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Account type is required");
        }

        @Test
        @DisplayName("throws when parent account does not exist")
        void parentNotFound() {
            GLAccountCreateRequest request = GLAccountCreateRequest.builder()
                    .accountCode("1001")
                    .accountName("Cash Sub")
                    .accountType(AccountType.ASSET)
                    .parentAccountId(testParentId)
                    .activationDate(LocalDateTime.now())
                    .build();

            when(glAccountRepository.existsById(testParentId)).thenReturn(false);

            assertThatThrownBy(() -> service.validateGLAccount(request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Parent account not found");
        }
    }

    // ===== VALIDATE FOR POSTING =====

    @Nested
    @DisplayName("validateAccountForPosting")
    class ValidateAccountForPosting {

        @Test
        @DisplayName("passes when account is active on transaction date")
        void success() {
            LocalDateTime transactionDate = LocalDateTime.of(2024, 3, 15, 0, 0);

            when(glAccountRepository.findById(testAccountId)).thenReturn(Optional.of(testAccount));

            // Should not throw
            service.validateAccountForPosting(testAccountId, transactionDate);
        }

        @Test
        @DisplayName("throws when account is not yet active on transaction date")
        void notYetActive() {
            testAccount.setActivationDate(LocalDateTime.of(2025, 1, 1, 0, 0));
            LocalDateTime transactionDate = LocalDateTime.of(2024, 6, 1, 0, 0);

            when(glAccountRepository.findById(testAccountId)).thenReturn(Optional.of(testAccount));

            assertThatThrownBy(() -> service.validateAccountForPosting(testAccountId, transactionDate))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not yet active");
        }

        @Test
        @DisplayName("throws when account is deactivated before transaction date")
        void deactivated() {
            testAccount.setDeactivationDate(LocalDateTime.of(2023, 1, 1, 0, 0));
            LocalDateTime transactionDate = LocalDateTime.of(2024, 1, 15, 0, 0);

            when(glAccountRepository.findById(testAccountId)).thenReturn(Optional.of(testAccount));

            assertThatThrownBy(() -> service.validateAccountForPosting(testAccountId, transactionDate))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("inactive");
        }
    }
}
