package com.positivity.accounting.tenancy;

import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_A;
import static com.positivity.tenancy.testing.TenantTestSupport.asTenant;
import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.accounting.internal.dto.JournalEntryCreateRequest;
import com.positivity.accounting.internal.dto.JournalEntryResponse;
import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.accounting.internal.enums.AccountSubtype;
import com.positivity.accounting.internal.enums.AccountType;
import com.positivity.accounting.internal.repository.GLAccountRepository;
import com.positivity.accounting.internal.repository.JournalEntryLineRepository;
import com.positivity.accounting.internal.service.JournalEntryService;
import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.tenancy.TenantContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The ledger balance the bank reconciliation reads (SPEC-manual-bank-reconciliation §3.7 "Ledger balance and
 * reversals", G15, §8.1 [M]; story S4, #2303) on the real baseline: {@code getAccountBalanceAsOf} counts every
 * entry that was posted — {@code POSTED} or {@code REVERSED} — at its own transaction date and never a
 * {@code DRAFT}, so a reversed pair counts the original from its date and the inverse from the reversal's.
 * Requires Docker.
 */
@DisplayName("Bank reconciliation ledger balance on Postgres (#2303)")
class BankReconciliationLedgerPostgresIT extends PostgresTenancyTestBase {

    private static final BigDecimal HUNDRED = new BigDecimal("100.00");

    /** 23:59:59.999999 — the as-of bound at Postgres timestamp(6) precision (§8.1). */
    private static final LocalTime END_OF_DAY = LocalTime.of(23, 59, 59, 999_999_000);

    @Autowired
    private GLAccountRepository glAccounts;

    @Autowired
    private JournalEntryService journalEntries;

    @Autowired
    private JournalEntryLineRepository lines;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final List<UUID> accounts = new ArrayList<>();

    @AfterEach
    void clear() {
        TenantContext.clear();
        JdbcTemplate owner = new JdbcTemplate(ownerDataSource());
        for (UUID account : accounts) {
            List<UUID> entries = owner.queryForList(
                    "SELECT DISTINCT journal_entry_id FROM journal_entry_line WHERE gl_account_id = ?",
                    UUID.class,
                    account);
            for (UUID entry : entries) {
                owner.update(
                        "UPDATE journal_entry SET reversal_journal_entry_id = NULL, reversed_by_journal_entry_id = NULL"
                                + " WHERE journal_entry_id = ?",
                        entry);
            }
            for (UUID entry : entries) {
                owner.update("DELETE FROM journal_entry_line WHERE journal_entry_id = ?", entry);
                owner.update("DELETE FROM journal_entry WHERE journal_entry_id = ?", entry);
            }
        }
        for (UUID account : accounts) {
            owner.update("DELETE FROM gl_account WHERE gl_account_id = ?", account);
        }
        accounts.clear();
    }

    @Test
    @DisplayName("a pair reversed on a later day reads 100 from A to B − 1 and 0 from B [M]")
    void reversalOnALaterDayCountsTheOriginalUntilTheReversal() {
        UUID cash = account(AccountType.ASSET, AccountSubtype.BANK_CASH, true);
        UUID counter = account(AccountType.LIABILITY, null, false);
        LocalDate a = LocalDate.of(2043, 3, 10);
        LocalDate b = LocalDate.of(2043, 3, 20);
        UUID original = post(cash, counter, a);
        reverse(original, b);

        assertThat(balanceAsOf(cash, a.minusDays(1))).isEqualByComparingTo("0");
        assertThat(balanceAsOf(cash, a))
                .as("the original counts from its own date")
                .isEqualByComparingTo("100");
        assertThat(balanceAsOf(cash, b.minusDays(1))).isEqualByComparingTo("100");
        assertThat(balanceAsOf(cash, b))
                .as("the reversal nets the pair to zero from its date — never −100")
                .isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("a pair reversed on the same day reads 0 from A")
    void reversalOnTheSameDayNetsToZero() {
        UUID cash = account(AccountType.ASSET, AccountSubtype.BANK_CASH, true);
        UUID counter = account(AccountType.LIABILITY, null, false);
        LocalDate a = LocalDate.of(2043, 4, 10);
        reverse(post(cash, counter, a), a);

        assertThat(balanceAsOf(cash, a.minusDays(1))).isEqualByComparingTo("0");
        assertThat(balanceAsOf(cash, a)).isEqualByComparingTo("0");
        assertThat(balanceAsOf(cash, a.plusDays(30))).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("a DRAFT entry never counts; the as-of bound is the end of the day")
    void draftsNeverCountAndTheBoundIsEndOfDay() {
        UUID cash = account(AccountType.ASSET, AccountSubtype.BANK_CASH, true);
        UUID counter = account(AccountType.LIABILITY, null, false);
        LocalDate day = LocalDate.of(2043, 5, 31);
        inTx(() -> journalEntries.createJournalEntry(request(cash, counter, day.atTime(10, 0))));
        post(cash, counter, day.atTime(23, 30));
        post(cash, counter, day.plusDays(1).atStartOfDay());

        assertThat(balanceAsOf(cash, day))
                .as("the 23:30 entry is inside, the next-day 00:00 entry and the draft are not")
                .isEqualByComparingTo("100");
    }

    // ---- helpers ------------------------------------------------------------------------------

    private UUID account(AccountType type, AccountSubtype subtype, boolean reconcilable) {
        String suffix = UUIDv7Generator.generate().toString().substring(24);
        UUID id = inTx(() -> {
            GLAccount account = new GLAccount();
            account.setGlAccountId(UUIDv7Generator.generate());
            account.setAccountCode("L" + suffix);
            account.setAccountName("Ledger IT " + suffix);
            account.setAccountType(type);
            account.setAccountSubtype(subtype);
            account.setReconcilable(reconcilable);
            account.setActivationDate(LocalDateTime.of(2020, 1, 1, 0, 0));
            account.setCreatedBy("it");
            account.setModifiedBy("it");
            return glAccounts.save(account).getGlAccountId();
        });
        accounts.add(id);
        return id;
    }

    private UUID post(UUID cash, UUID counter, LocalDate date) {
        return post(cash, counter, date.atTime(12, 0));
    }

    private UUID post(UUID cash, UUID counter, LocalDateTime at) {
        return inTx(() -> {
            JournalEntryResponse created = journalEntries.createJournalEntry(request(cash, counter, at));
            return journalEntries
                    .postJournalEntry(created.getJournalEntryId(), null)
                    .getJournalEntryId();
        });
    }

    private void reverse(UUID entry, LocalDate date) {
        inTx(() -> journalEntries.reverseJournalEntry(entry, "Ledger IT reversal", date));
    }

    private BigDecimal balanceAsOf(UUID account, LocalDate day) {
        return inTx(() -> lines.getAccountBalanceAsOf(account, day.atTime(END_OF_DAY)));
    }

    private static JournalEntryCreateRequest request(UUID cash, UUID counter, LocalDateTime at) {
        return JournalEntryCreateRequest.builder()
                .transactionDate(at)
                .sourceEventId(UUIDv7Generator.generate())
                .description("Ledger IT")
                .lines(List.of(
                        JournalEntryCreateRequest.JournalEntryLineRequest.builder()
                                .glAccountId(cash)
                                .debitAmount(HUNDRED)
                                .creditAmount(BigDecimal.ZERO)
                                .build(),
                        JournalEntryCreateRequest.JournalEntryLineRequest.builder()
                                .glAccountId(counter)
                                .debitAmount(BigDecimal.ZERO)
                                .creditAmount(HUNDRED)
                                .build()))
                .build();
    }

    private <T> T inTx(java.util.concurrent.Callable<T> work) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        return asTenant(
                TENANT_A,
                () -> tx.execute(status -> {
                    try {
                        return work.call();
                    } catch (RuntimeException e) {
                        throw e;
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }));
    }
}
