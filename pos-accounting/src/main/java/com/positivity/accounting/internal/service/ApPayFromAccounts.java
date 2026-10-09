package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.bankrec.readmodel.BankAccountCurrencies;
import com.positivity.accounting.internal.config.LedgerCurrency;
import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.accounting.internal.enums.AccountSubtype;
import com.positivity.accounting.internal.repository.GLAccountRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/**
 * The one rule for the accounts a vendor payment may come from (CAP:550 S42, #2603; AW41; AP reads #2670). The pay
 * command's slot 1c ({@link APPaymentPreGatewayChecks#checkRequest}) and the pay-from read ({@code GET
 * /v1/accounting/ap/pay-from-accounts}) both ask it, so the accounts the page offers are exactly the ones the payment
 * accepts (P7): there is no copy of the rule anywhere else.
 *
 * <p><b>Eligible bank account</b>: a {@code BANK_CASH} GL account active at the start of the execution date (the
 * instant its entry posts at: activated at or before it, not deactivated by it), not deactivated by the command's own
 * moment ({@link #fundingCutoff}), and not in a foreign currency ({@link BankAccountCurrencies}, {@link
 * LedgerCurrency}). {@code bankAccountId} may be omitted only when exactly one eligible account exists.
 *
 * <p><b>Execution date</b>: the tenant's business date ({@link #businessDate}). Without an accounting time zone the
 * accounts are judged on today's UTC date; the payment then refuses in slot 5a.
 */
@Component
@RequiredArgsConstructor
public class ApPayFromAccounts {

    private final Clock clock;
    private final AccountingCalendarZoneResolver zoneResolver;
    private final GLAccountRepository glAccounts;
    private final BankAccountCurrencies bankAccountCurrencies;
    private final LedgerCurrency ledgerCurrency;

    /**
     * The tenant's business date now, read once per command; empty when the tenant has no accounting time zone, which
     * the pay command's slot 5a refuses.
     */
    public @NonNull Optional<LocalDate> businessDate() {
        return zoneResolver.find().map(zone -> LocalDate.ofInstant(clock.instant(), zone));
    }

    /** The date eligibility is judged on: the business date, or today's UTC date when the zone is unset. */
    public @NonNull LocalDate executionDate(@NonNull Optional<LocalDate> businessDate) {
        return businessDate.orElseGet(() -> LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC));
    }

    /** The functional currency every eligible account is in. */
    public @NonNull String currencyCode() {
        return ledgerCurrency.code();
    }

    /**
     * The accounts a payment executed now on {@code date} may come from, in account-number order: what an omitted
     * {@code bankAccountId} chooses among and what the pay-from read lists.
     */
    public @NonNull List<GLAccount> eligible(@NonNull LocalDate date) {
        return eligible(date, fundingCutoff(date));
    }

    /** Whether a payment executed now on {@code date} may come from {@code glAccountId}. */
    public boolean isEligible(@NonNull UUID glAccountId, @NonNull LocalDate date) {
        LocalDateTime cutoff = fundingCutoff(date);
        return glAccounts
                .findById(glAccountId)
                .filter(account -> isEligible(account, date, cutoff))
                .isPresent();
    }

    /** The one account an omitted {@code bankAccountId} resolves to on {@code date}, or empty with none or several. */
    public @NonNull Optional<UUID> defaultFor(@NonNull List<GLAccount> eligible) {
        return eligible.size() == 1 ? Optional.of(eligible.getFirst().getGlAccountId()) : Optional.empty();
    }

    /**
     * The one bank account active at the start of {@code date}, or empty when there is none or more than one: what an
     * omitted {@code bankAccountId} of an idempotent replay resolves to. A replay funds nothing (the payment executed
     * already), so the pay command's "not deactivated by now" condition does not apply to it.
     */
    public @NonNull Optional<UUID> replayDefault(@NonNull LocalDate date) {
        return defaultFor(eligible(date, date.atStartOfDay()));
    }

    /**
     * The instant a funding account must not be deactivated by: the later of the start of {@code date} and the pay
     * command's own moment in the tenant's calendar, the clock and zone {@code deactivateGLAccount} stamps with
     * (Accounting ruling of 2026-10-08, #2603 comment 6068272860). An account deactivated at any point up to the pay
     * command cannot fund it; the entry still posts at the start of the day. Without a zone, UTC (slot 5a refuses).
     */
    private LocalDateTime fundingCutoff(LocalDate date) {
        LocalDateTime now =
                LocalDateTime.ofInstant(clock.instant(), zoneResolver.find().orElse(ZoneOffset.UTC));
        LocalDateTime startOfDay = date.atStartOfDay();
        return now.isAfter(startOfDay) ? now : startOfDay;
    }

    private List<GLAccount> eligible(LocalDate date, LocalDateTime fundingCutoff) {
        return glAccounts.findBySubtypeActiveAt(AccountSubtype.BANK_CASH, date.atStartOfDay(), fundingCutoff).stream()
                .filter(this::inFunctionalCurrency)
                .toList();
    }

    private boolean isEligible(GLAccount account, LocalDate date, LocalDateTime fundingCutoff) {
        if (account.getAccountSubtype() != AccountSubtype.BANK_CASH) {
            return false;
        }
        // Active at the start of the execution day, the instant the entry posts at (APPaymentPostingService), by the
        // posting's own rule (GLAccountService.validateAccountForPosting): an account activated later that day cannot
        // take the entry, so it is not eligible that day (#2641 review, MAJOR 2).
        // And not deactivated by the pay command's own moment (fundingCutoff, never before the start of the day).
        LocalDateTime at = date.atStartOfDay();
        boolean active = (account.getActivationDate() == null
                        || !account.getActivationDate().isAfter(at))
                && (account.getDeactivationDate() == null
                        || account.getDeactivationDate().isAfter(fundingCutoff));
        return active && inFunctionalCurrency(account);
    }

    /** Not in a foreign currency: an account without a profile states none and counts as functional. */
    private boolean inFunctionalCurrency(GLAccount account) {
        return bankAccountCurrencies
                .currencyOf(account.getGlAccountId())
                .filter(ledgerCurrency::isForeign)
                .isEmpty();
    }
}
