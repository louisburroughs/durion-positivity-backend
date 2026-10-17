package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.bankrec.readmodel.BankPositionFacts;
import com.positivity.accounting.internal.bankrec.service.BankCashAccounts;
import com.positivity.accounting.internal.bankrec.service.BankCashAccounts.BankCashAccount;
import com.positivity.accounting.internal.config.LedgerCurrency;
import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.accounting.internal.enums.AccountSubtype;
import com.positivity.accounting.internal.repository.GLAccountRepository;
import com.positivity.accounting.internal.repository.JournalEntryLineRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Which ledger accounts hold the shop's cash, by role, and what they hold (CAP:550 S19, #2515; SPEC-accounting-workspace
 * §4.1; AW9). The one place the cash position and the outlook agree on the rule, so they cannot drift:
 *
 * <ul>
 *   <li><b>In the bank</b>: the active {@code BANK_CASH} accounts in the functional currency; one in another currency is
 *       left out and counted (ADR-0067 PC-9), never summed at par.
 *   <li><b>Waiting to be deposited</b>: the accounts behind the mapping keys {@code UNDEPOSITED_FUNDS} (1090) and {@code
 *       CASH_CLEARING} (1095), both shown and netted.
 *   <li><b>Kept in drawers</b>: the active {@code CASH_ON_HAND} accounts (1080).
 *   <li><b>Settlement suspense</b> ({@code SETTLEMENT_SUSPENSE}, 2350) is read for its warning and is never a
 *       component.
 * </ul>
 *
 * No account number appears in code: the roles are the mapping keys and the account subtypes. A balance is debits minus
 * credits over POSTED and REVERSED entries, the rule {@code JournalEntryLineRepository#getAccountBalance} applies,
 * summed for all the accounts in one query. A role with no account yields no rows and a zero.
 */
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CashAccounts {

    private final BankCashAccounts bankCashAccounts;
    private final BankPositionFacts bankPositionFacts;
    private final GLAccountRepository glAccounts;
    private final JournalEntryLineRepository journalEntryLines;
    private final GLMappingResolver glMappingResolver;
    private final LedgerCurrency ledgerCurrency;
    private final AccountingCalendarZoneResolver zoneResolver;
    private final Clock clock;

    /** One cash account with its books balance. */
    public record Row(
            @NonNull UUID glAccountId,
            @NonNull String accountCode,
            @NonNull String accountName,
            @NonNull BigDecimal balance) {}

    /**
     * The cash accounts by role. {@code clearingAccountId} is the account behind {@code CASH_CLEARING}, null when
     * unmapped; {@code suspenseBalance} is the signed ledger balance (debits minus credits) of settlement suspense.
     */
    public record Components(
            @NonNull List<Row> bank,
            @NonNull List<Row> waiting,
            @NonNull List<Row> drawers,
            @NonNull BigDecimal suspenseBalance,
            @Nullable UUID clearingAccountId,
            int excludedForeignCurrencyAccounts) {

        /** The sum of the rows' balances. */
        public static @NonNull BigDecimal total(@NonNull List<Row> rows) {
            return rows.stream().map(Row::balance).reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        /** The balance of the account behind {@code CASH_CLEARING}; zero when it is unmapped. */
        public @NonNull BigDecimal clearingBalance() {
            return waiting.stream()
                    .filter(row -> row.glAccountId().equals(clearingAccountId))
                    .map(Row::balance)
                    .findFirst()
                    .orElse(BigDecimal.ZERO);
        }
    }

    /** The accounts by role and their balances, as of now. */
    public @NonNull Components read() {
        LocalDateTime at = zoneResolver.postingDateTime(clock.instant());
        String currency = ledgerCurrency.code();

        List<BankCashAccount> activeBank = bankCashAccounts.listActive();
        Map<UUID, String> currencies = bankPositionFacts.currenciesOf(
                activeBank.stream().map(BankCashAccount::glAccountId).toList());
        List<BankCashAccount> bank = new ArrayList<>();
        int excluded = 0;
        for (BankCashAccount account : activeBank) {
            if (ledgerCurrency.isForeign(currencies.get(account.glAccountId()))) {
                excluded++;
            } else {
                bank.add(account);
            }
        }

        Optional<UUID> undeposited = glMappingResolver.findGLAccount(
                DepositServiceImpl.POSTING_CATEGORY, DepositServiceImpl.UNDEPOSITED_FUNDS_KEY, at);
        Optional<UUID> clearing = glMappingResolver.findGLAccount(
                DepositServiceImpl.POSTING_CATEGORY, DepositServiceImpl.CASH_CLEARING_KEY, at);
        Optional<UUID> suspense = glMappingResolver.findGLAccount(
                SettlementReconciliationServiceImpl.SETTLEMENT_CATEGORY,
                SettlementReconciliationServiceImpl.SUSPENSE_MAPPING_KEY,
                at);
        Set<UUID> waitingIds = new LinkedHashSet<>();
        undeposited.ifPresent(waitingIds::add);
        clearing.ifPresent(waitingIds::add);
        Map<UUID, GLAccount> waitingAccounts = new HashMap<>();
        glAccounts.findAllById(waitingIds).forEach(account -> waitingAccounts.put(account.getGlAccountId(), account));
        List<GLAccount> drawerAccounts = glAccounts.findBySubtypeActiveAt(AccountSubtype.CASH_ON_HAND, at, at);

        Set<UUID> ids = new LinkedHashSet<>();
        bank.forEach(account -> ids.add(account.glAccountId()));
        ids.addAll(waitingAccounts.keySet());
        drawerAccounts.forEach(account -> ids.add(account.getGlAccountId()));
        suspense.ifPresent(ids::add);
        Map<UUID, BigDecimal> balances = balancesOf(ids);

        List<Row> bankRows = bank.stream()
                .map(account -> row(
                        account.glAccountId(),
                        account.accountCode(),
                        account.accountName(),
                        balances,
                        currency))
                .toList();
        List<Row> waitingRows = waitingAccounts.values().stream()
                .sorted(Comparator.comparing(GLAccount::getAccountCode))
                .map(account -> row(
                        account.getGlAccountId(), account.getAccountCode(), account.getAccountName(), balances, currency))
                .toList();
        List<Row> drawerRows = drawerAccounts.stream()
                .map(account -> row(
                        account.getGlAccountId(), account.getAccountCode(), account.getAccountName(), balances, currency))
                .toList();
        BigDecimal suspenseBalance = suspense
                .map(id -> InvoiceBalanceCalculator.atCurrencyScale(
                        balances.getOrDefault(id, BigDecimal.ZERO), currency))
                .orElse(InvoiceBalanceCalculator.atCurrencyScale(BigDecimal.ZERO, currency));
        return new Components(
                bankRows, waitingRows, drawerRows, suspenseBalance, clearing.orElse(null), excluded);
    }

    private Map<UUID, BigDecimal> balancesOf(Collection<UUID> ids) {
        Map<UUID, BigDecimal> balances = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            return balances;
        }
        for (JournalEntryLineRepository.AccountBalance row : journalEntryLines.getAccountBalances(ids)) {
            balances.put(row.getGlAccountId(), row.getBalance());
        }
        return balances;
    }

    private static Row row(UUID id, String code, String name, Map<UUID, BigDecimal> balances, String currency) {
        return new Row(
                id,
                code,
                name,
                InvoiceBalanceCalculator.atCurrencyScale(balances.getOrDefault(id, BigDecimal.ZERO), currency));
    }
}
