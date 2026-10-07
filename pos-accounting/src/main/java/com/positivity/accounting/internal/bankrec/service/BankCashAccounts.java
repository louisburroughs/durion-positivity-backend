package com.positivity.accounting.internal.bankrec.service;

import com.positivity.accounting.internal.bankrec.enums.BankRecCloseScope;
import com.positivity.accounting.internal.bankrec.intake.BankRecErrorCode;
import com.positivity.accounting.internal.bankrec.intake.BankRecException;
import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.accounting.internal.enums.AccountSubtype;
import com.positivity.accounting.internal.repository.GLAccountRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The ledger side of "which accounts are bank accounts" (SPEC D5; story S2, #2301): a bank account
 * is an active GL account with {@code reconcilable = true} and subtype {@code BANK_CASH}. The core
 * reads GL accounts only through here, so the rule has one home.
 */
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BankCashAccounts {

    private final GLAccountRepository glAccounts;
    private final Clock clock;

    /** A bank account's id with the display values ADR-0064 puts beside it. */
    public record BankCashAccount(
            @NonNull UUID glAccountId,
            @NonNull String accountCode,
            @NonNull String accountName) {}

    /**
     * The account a statement or a transaction is written to.
     *
     * @throws BankRecException {@code VALIDATION_ERROR} (field {@code glAccountId}) when the tenant
     *     has no such account; {@code ACCOUNT_NOT_RECONCILABLE} when it is not a reconcilable {@code
     *     BANK_CASH} account
     */
    public @NonNull BankCashAccount requireForIntake(@NonNull UUID glAccountId) {
        GLAccount account = glAccounts
                .findById(glAccountId)
                .orElseThrow(() -> BankRecException.field(
                        BankRecErrorCode.VALIDATION_ERROR,
                        "GL account not found: " + glAccountId,
                        "glAccountId",
                        "unknown GL account"));
        return requireEligible(account);
    }

    /**
     * The account named in a path.
     *
     * @throws BankRecException {@code GL_ACCOUNT_NOT_FOUND} when the tenant has no such account;
     *     {@code ACCOUNT_NOT_RECONCILABLE} when it is not a reconcilable {@code BANK_CASH} account
     */
    public @NonNull BankCashAccount requireByPath(@NonNull UUID glAccountId) {
        GLAccount account = glAccounts
                .findById(glAccountId)
                .orElseThrow(() -> new BankRecException(
                        BankRecErrorCode.GL_ACCOUNT_NOT_FOUND, "GL account not found: " + glAccountId));
        return requireEligible(account);
    }

    /** Every active bank account of the tenant, ordered by account code. */
    public @NonNull List<BankCashAccount> listActive() {
        return glAccounts
                .findReconcilableActiveOn(
                        AccountSubtype.BANK_CASH, LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                .stream()
                .map(BankCashAccounts::toRef)
                .toList();
    }

    /**
     * The accounts close readiness evaluates under the tenant's {@code BANK_REC_CLOSE_SCOPE} (SPEC §5.2, D5; story
     * S6, #2305), ordered by account code: the active bank accounts, or every active reconcilable account.
     */
    public @NonNull List<BankCashAccount> listInScope(@NonNull BankRecCloseScope scope) {
        if (scope == BankRecCloseScope.BANK_CASH_SUBTYPE) {
            return listActive();
        }
        return glAccounts.findAllReconcilableActiveOn(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)).stream()
                .map(BankCashAccounts::toRef)
                .toList();
    }

    /** One page of {@link #listActive()}, cut in the database. */
    public @NonNull Page<BankCashAccount> pageActive(@NonNull Pageable pageable) {
        return glAccounts
                .findReconcilableActiveOn(
                        AccountSubtype.BANK_CASH, LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC), pageable)
                .map(BankCashAccounts::toRef);
    }

    /** Display values for the given ids; ids the tenant does not hold are absent. */
    public @NonNull Map<UUID, BankCashAccount> displayValues(@NonNull Collection<UUID> glAccountIds) {
        return glAccounts.findAllById(glAccountIds.stream().distinct().toList()).stream()
                .map(BankCashAccounts::toRef)
                .collect(Collectors.toMap(BankCashAccount::glAccountId, Function.identity(), (a, b) -> a));
    }

    private static BankCashAccount requireEligible(GLAccount account) {
        if (!isBankCash(account)) {
            throw new BankRecException(
                    BankRecErrorCode.ACCOUNT_NOT_RECONCILABLE,
                    "GL account " + account.getAccountCode() + " is not a reconcilable BANK_CASH account");
        }
        return toRef(account);
    }

    private static boolean isBankCash(GLAccount account) {
        return account.isReconcilable() && account.getAccountSubtype() == AccountSubtype.BANK_CASH;
    }

    private static BankCashAccount toRef(GLAccount account) {
        return new BankCashAccount(account.getGlAccountId(), account.getAccountCode(), account.getAccountName());
    }
}
