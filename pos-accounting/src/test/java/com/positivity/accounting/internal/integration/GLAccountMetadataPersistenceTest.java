package com.positivity.accounting.internal.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.accounting.BaseIntegrationTest;
import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.accounting.internal.enums.AccountSubtype;
import com.positivity.accounting.internal.enums.AccountType;
import com.positivity.accounting.internal.repository.GLAccountRepository;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistence round-trip tests for the Story H1 GLAccount metadata columns
 * (accountSubtype, reconcilable) against the H2 test schema (Issue #934).
 */
@Transactional
@DisplayName("GLAccount Metadata Persistence Tests")
class GLAccountMetadataPersistenceTest extends BaseIntegrationTest {

    @Autowired
    private GLAccountRepository glAccountRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("Should persist and reload accountSubtype and reconcilable")
    void shouldRoundTripSubtypeAndReconcilable() {
        UUID id = UUID.fromString("00000000-0000-4000-a000-0000000000b1");
        GLAccount account = newAccount(id, "1090T", "Undeposited Funds Test");
        account.setAccountSubtype(AccountSubtype.UNDEPOSITED_FUNDS);
        account.setReconcilable(true);

        glAccountRepository.saveAndFlush(account);
        entityManager.clear();

        GLAccount reloaded = glAccountRepository.findById(id).orElseThrow();
        assertThat(reloaded.getAccountSubtype()).isEqualTo(AccountSubtype.UNDEPOSITED_FUNDS);
        assertThat(reloaded.isReconcilable()).isTrue();
    }

    @Test
    @DisplayName("Should default to null subtype and non-reconcilable when metadata not set")
    void shouldDefaultMetadataWhenUnset() {
        UUID id = UUID.fromString("00000000-0000-4000-a000-0000000000b2");
        GLAccount account = newAccount(id, "4000T", "Revenue Test");

        glAccountRepository.saveAndFlush(account);
        entityManager.clear();

        GLAccount reloaded = glAccountRepository.findById(id).orElseThrow();
        assertThat(reloaded.getAccountSubtype()).isNull();
        assertThat(reloaded.isReconcilable()).isFalse();
    }

    private static GLAccount newAccount(UUID id, String code, String name) {
        GLAccount account = new GLAccount();
        account.setGlAccountId(id);
        account.setAccountCode(code);
        account.setAccountName(name);
        account.setAccountType(AccountType.ASSET);
        account.setActivationDate(LocalDateTime.of(2025, 1, 1, 0, 0));
        account.setCreatedBy("test");
        account.setModifiedBy("test");
        return account;
    }
}
