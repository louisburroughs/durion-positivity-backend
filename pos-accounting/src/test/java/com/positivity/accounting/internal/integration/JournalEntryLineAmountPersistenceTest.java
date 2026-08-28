package com.positivity.accounting.internal.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.accounting.BaseIntegrationTest;
import com.positivity.accounting.internal.dto.JournalEntryCreateRequest;
import com.positivity.accounting.internal.dto.JournalEntryResponse;
import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.accounting.internal.entity.JournalEntryLine;
import com.positivity.accounting.internal.enums.AccountType;
import com.positivity.accounting.internal.repository.GLAccountRepository;
import com.positivity.accounting.internal.repository.JournalEntryLineRepository;
import com.positivity.accounting.internal.service.JournalEntryService;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * Regression coverage for the {@code JournalEntryMapper#toLineEntity} null-amount bug: a
 * {@link JournalEntryCreateRequest.JournalEntryLineRequest} that omits one side of the amount
 * (single-sided lines are the normal case — see {@code isSingleSidedAmount}) must persist that
 * side as {@link BigDecimal#ZERO}, matching {@link JournalEntryLine}'s own default and its
 * {@code nullable = false} columns, not as {@code null}.
 *
 * <p>Prior to the fix this was invisible under a plain {@code save()} inside a
 * {@code @Transactional} test — the pending INSERT was rolled back before Hibernate ever ran it
 * against the {@code NOT NULL} constraint. Asserting at the persistence layer (flush + a fresh
 * repository read) is what makes the bug reproduce; a pure DTO/entity-in-memory assertion would
 * not have caught it.
 */
@Transactional
@DisplayName("JournalEntryLine amount persistence — omitted request amounts")
class JournalEntryLineAmountPersistenceTest extends BaseIntegrationTest {

    @Autowired
    private JournalEntryService journalEntryService;

    @Autowired
    private GLAccountRepository glAccountRepository;

    @Autowired
    private JournalEntryLineRepository journalEntryLineRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("Omitted line amount persists as ZERO, not NULL, after flush")
    void omittedLineAmountPersistsAsZero() {
        GLAccount debitAccount = newAccount("00000000-0000-4000-a000-0000000000c1", "1099T", "Persistence Test Debit");
        GLAccount creditAccount =
                newAccount("00000000-0000-4000-a000-0000000000c2", "2099T", "Persistence Test Credit");
        glAccountRepository.save(debitAccount);
        glAccountRepository.save(creditAccount);

        JournalEntryCreateRequest request = JournalEntryCreateRequest.builder()
                .transactionDate(LocalDateTime.of(2025, 1, 1, 10, 0))
                .lines(List.of(
                        // creditAmount omitted (single-sided debit line)
                        JournalEntryCreateRequest.JournalEntryLineRequest.builder()
                                .glAccountId(debitAccount.getGlAccountId())
                                .debitAmount(new BigDecimal("100.00"))
                                .build(),
                        // debitAmount omitted (single-sided credit line)
                        JournalEntryCreateRequest.JournalEntryLineRequest.builder()
                                .glAccountId(creditAccount.getGlAccountId())
                                .creditAmount(new BigDecimal("100.00"))
                                .build()))
                .build();

        JournalEntryResponse created = journalEntryService.createJournalEntry(request);

        // Force the still-pending INSERTs to actually execute (mirrors what
        // saveAndFlush() does on the post/update paths) and drop the persistence
        // context cache so the read below hits the database, not in-memory state.
        entityManager.flush();
        entityManager.clear();

        List<JournalEntryLine> lines =
                journalEntryLineRepository.findByJournalEntry_JournalEntryId(created.getJournalEntryId());
        assertThat(lines).hasSize(2);
        for (JournalEntryLine line : lines) {
            assertThat(line.getDebitAmount())
                    .as("debitAmount must never persist as null")
                    .isNotNull();
            assertThat(line.getCreditAmount())
                    .as("creditAmount must never persist as null")
                    .isNotNull();
        }

        JournalEntryLine debitLine = lines.stream()
                .filter(l -> l.getGlAccountId().equals(debitAccount.getGlAccountId()))
                .findFirst()
                .orElseThrow();
        assertThat(debitLine.getDebitAmount()).isEqualByComparingTo("100.00");
        assertThat(debitLine.getCreditAmount()).isEqualByComparingTo(BigDecimal.ZERO);

        JournalEntryLine creditLine = lines.stream()
                .filter(l -> l.getGlAccountId().equals(creditAccount.getGlAccountId()))
                .findFirst()
                .orElseThrow();
        assertThat(creditLine.getCreditAmount()).isEqualByComparingTo("100.00");
        assertThat(creditLine.getDebitAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    private static GLAccount newAccount(String id, String code, String name) {
        GLAccount account = new GLAccount();
        account.setGlAccountId(UUID.fromString(id));
        account.setAccountCode(code);
        account.setAccountName(name);
        account.setAccountType(AccountType.ASSET);
        account.setActivationDate(LocalDateTime.of(2024, 1, 1, 0, 0));
        account.setCreatedBy("testuser");
        account.setModifiedBy("testuser");
        return account;
    }
}
