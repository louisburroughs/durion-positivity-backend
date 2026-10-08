package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.bankrec.readmodel.BankAccountCurrencies;
import com.positivity.accounting.internal.config.IsoCurrencyCodes;
import com.positivity.accounting.internal.config.LedgerCurrency;
import com.positivity.accounting.internal.dto.ExecuteAPPaymentRequest;
import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.accounting.internal.enums.AccountSubtype;
import com.positivity.accounting.internal.enums.PaymentMethod;
import com.positivity.accounting.internal.exception.AccountingTimeZoneUnsetException;
import com.positivity.accounting.internal.exception.CurrencyNotSupportedException;
import com.positivity.accounting.internal.exception.GLAccountNotActiveException;
import com.positivity.accounting.internal.exception.GLAccountNotFoundException;
import com.positivity.accounting.internal.exception.GLMappingNotConfiguredException;
import com.positivity.accounting.internal.exception.VendorBillException;
import com.positivity.accounting.internal.repository.GLAccountRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The AP pay command's own refusals (CAP:550 S42, #2603; AW40, AW41), run in the numbered slots of its pre-gateway
 * block ({@link APPaymentServiceImpl#executePayment}). Every one comes before the payment row is saved and before the
 * gateway is called, charges nothing and saves no payment, so the same {@code paymentRef} may be sent again once
 * corrected (a refusal's own audit row may commit in its own transaction). The first
 * refusal wins.
 *
 * <ul>
 *   <li><b>Slot 1</b> ({@link #checkRequest}): 1a the method, {@code CREDIT_CARD} and {@code OTHER} → 422 {@code
 *       AP_PAYMENT_METHOD_NOT_SUPPORTED} (OI-17); 1b the currency, not ISO 4217 → 400 {@code VALIDATION_ERROR}, other than
 *       the functional currency → 422 {@code CURRENCY_NOT_SUPPORTED} (ADR-0067 PC-9 (a)); 1c the bank account → 400
 *       {@code VALIDATION_ERROR} with {@code fieldErrors[bankAccountId]}. Currency comes before the bank account, so an
 *       eligibility check that depends on the functional currency never masks a currency refusal. S24 adds its vendor
 *       check at the end of this slot.
 *   <li><b>Slot 5</b> ({@link #checkPeriodAndMapping}): 5a the time zone → 422 {@code ACCOUNTING_TIME_ZONE_UNSET} (fails
 *       closed, #2558); 5b the hard lock → 422 {@code PERIOD_HARD_LOCKED}; 5c a closed period without an accepted
 *       override → 422 {@code PERIOD_CLOSED}; 5d the {@code AP_PAYMENT} mappings → 422 {@code GL_MAPPING_NOT_CONFIGURED}
 *       ({@code ACCOUNTS_PAYABLE} always, {@code PAYMENT_FEES} when the fee is above zero).
 * </ul>
 *
 * <p><b>Eligible bank account</b>: a {@code BANK_CASH} GL account active at the start of the execution date (the
 * instant its entry posts at: activated at or before it, not deactivated by it) and not in a foreign
 * currency ({@link BankAccountCurrencies}, {@link LedgerCurrency}). {@code bankAccountId} may be omitted only when
 * exactly one eligible account exists; inactive and foreign-currency accounts are not counted.
 *
 * <p><b>Execution date</b>: the tenant's business date, read once ({@link #businessDate}) and fixed in slot 5. It is
 * the date the period check uses, the date the entry posts on and the date a retry posts on, never re-dated (ruling 3
 * of #2603).
 */
@Component
@RequiredArgsConstructor
public class APPaymentPreGatewayChecks {

    /** The posting category an AP payment's entry resolves through (AW40). */
    public static final String POSTING_CATEGORY = "AP_PAYMENT";

    /** The debit of the gross: accounts payable (2000). */
    public static final String ACCOUNTS_PAYABLE_KEY = "ACCOUNTS_PAYABLE";

    /** The debit of the fee the bank charged (6030). */
    public static final String PAYMENT_FEES_KEY = "PAYMENT_FEES";

    static final String BANK_ACCOUNT_FIELD = "bankAccountId";

    private static final Set<PaymentMethod> UNSUPPORTED_METHODS =
            EnumSet.of(PaymentMethod.CREDIT_CARD, PaymentMethod.OTHER);

    private final Clock clock;
    private final AccountingCalendarZoneResolver zoneResolver;
    private final GLAccountRepository glAccounts;
    private final BankAccountCurrencies bankAccountCurrencies;
    private final LedgerCurrency ledgerCurrency;
    private final AccountingPeriodGate periodGate;
    private final GLMappingResolver glMappingResolver;
    private final GLAccountService glAccountService;

    /** What slot 5 fixed: the execution date and whether a closed-period override was accepted for it. */
    public record Execution(@NonNull LocalDate date, boolean overrideAccepted) {}

    /**
     * The tenant's business date now, read once per command; empty when the tenant has no accounting time zone, which
     * slot 5a refuses.
     */
    public @NonNull Optional<LocalDate> businessDate() {
        return zoneResolver.find().map(zone -> LocalDate.ofInstant(clock.instant(), zone));
    }

    /**
     * Slot 1: method, then currency, then bank account.
     *
     * @param businessDate the command's business date; when the zone is unset the bank account's activity is judged on
     *     today's UTC date, and slot 5a refuses the payment afterwards
     * @return the bank account the payment is made from
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public @NonNull UUID checkRequest(
            @NonNull ExecuteAPPaymentRequest request, @NonNull Optional<LocalDate> businessDate) {
        // 1a. The method (OI-17): no funding account is modelled for a card or an "other" payment.
        if (UNSUPPORTED_METHODS.contains(request.getPaymentMethod())) {
            throw new VendorBillException(
                    VendorBillException.Code.AP_PAYMENT_METHOD_NOT_SUPPORTED,
                    "A vendor payment by " + request.getPaymentMethod()
                            + " cannot be booked yet; pay by ACH, CHECK or WIRE");
        }
        // 1b. The currency: ISO 4217 (ADR-0067 R-3), then the functional currency (PC-9 (a)).
        String currency = request.getCurrency().trim();
        if (!IsoCurrencyCodes.isIso(currency.toUpperCase(Locale.ROOT))) {
            String message = "currency '" + currency + "' is not an ISO 4217 currency code";
            throw new VendorBillException(
                    VendorBillException.Code.VALIDATION_ERROR,
                    message,
                    List.of(new VendorBillException.FieldError("currency", message)),
                    null);
        }
        if (ledgerCurrency.isForeign(currency)) {
            throw new CurrencyNotSupportedException("A vendor payment in " + currency.toUpperCase(Locale.ROOT)
                    + " cannot be booked: the ledger books " + ledgerCurrency.code() + " only (ADR-0067 PC-9)");
        }
        // 1c. The bank account.
        LocalDate date = businessDate.orElseGet(() -> LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC));
        LocalDateTime fundingCutoff = fundingCutoff(date);
        UUID supplied = request.getBankAccountId();
        if (supplied != null) {
            if (!isEligible(supplied, date, fundingCutoff)) {
                throw bankAccountRefused("bankAccountId is not an active " + ledgerCurrency.code()
                        + " bank account (BANK_CASH) on " + date);
            }
            return supplied;
        }
        List<UUID> eligible = eligibleBankAccounts(date, fundingCutoff);
        if (eligible.size() != 1) {
            throw bankAccountRefused(
                    eligible.isEmpty()
                            ? "bankAccountId is required: there is no active " + ledgerCurrency.code()
                                    + " bank account (BANK_CASH) to pay from"
                            : "bankAccountId is required: " + eligible.size() + " active " + ledgerCurrency.code()
                                    + " bank accounts (BANK_CASH) could pay; choose one");
        }
        return eligible.getFirst();
    }

    /**
     * Slot 5: time zone, then hard lock, then closed period, then mapping. The period row is read without a lock: no
     * period lock is held across the gateway call (#2641 review).
     *
     * @param businessDate          the command's business date (empty when the zone is unset)
     * @param feeAmount             the payment's fee; {@code PAYMENT_FEES} is needed only above zero
     * @param overrideJustification the payer's closed-period justification, honoured only with {@code
     *                              accounting:period:override}
     * @return the fixed execution date and whether a closed-period override was accepted
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public @NonNull Execution checkPeriodAndMapping(
            @NonNull Optional<LocalDate> businessDate,
            @Nullable BigDecimal feeAmount,
            @Nullable String overrideJustification) {
        // 5a. Fails closed without an accounting time zone (#2558).
        LocalDate date = businessDate.orElseThrow(AccountingTimeZoneUnsetException::new);
        // 5b, 5c. The gate checks the hard lock, then the period (locked).
        boolean overrideAccepted = periodGate.assertPaymentDateAllowed(date, overrideJustification);
        // 5d. The mappings the entry will need, effective on the execution date (ruling 1 of #2603).
        requireMapping(ACCOUNTS_PAYABLE_KEY, date);
        if (feeAmount != null && feeAmount.signum() > 0) {
            requireMapping(PAYMENT_FEES_KEY, date);
        }
        return new Execution(date, overrideAccepted);
    }

    /**
     * The one bank account active at the start of {@code date}, or empty when there is none or more than one: what an
     * omitted {@code bankAccountId} of an idempotent replay resolves to. A replay funds nothing (the payment executed
     * already), so the pay command's "not deactivated by now" condition does not apply to it.
     */
    public @NonNull Optional<UUID> defaultBankAccount(@NonNull LocalDate date) {
        List<UUID> eligible = eligibleBankAccounts(date, date.atStartOfDay());
        return eligible.size() == 1 ? Optional.of(eligible.getFirst()) : Optional.empty();
    }

    /**
     * Resolves one {@code AP_PAYMENT} key at {@code at} to an account the entry can post to, refusing with a guided
     * {@code GL_MAPPING_NOT_CONFIGURED} (#2601) that names {@code AP_PAYMENT/<key>} when no mapping is effective or
     * its account is missing or not active at {@code at} (as {@code VendorBillPostingService.resolve} does). Slot 5d
     * and the posting both call it, so a payment that passes 5d never posts to an inactive mapped account.
     */
    public @NonNull UUID resolve(@NonNull String mappingKey, @NonNull LocalDateTime at) {
        try {
            UUID account = glMappingResolver.resolveGLAccount(POSTING_CATEGORY, mappingKey, at);
            glAccountService.validateAccountForPosting(account, at);
            return account;
        } catch (GLMappingNotConfiguredException | GLAccountNotActiveException | GLAccountNotFoundException e) {
            throw new GLMappingNotConfiguredException(
                    "No active " + POSTING_CATEGORY + "/" + mappingKey + " mapping on " + at.toLocalDate()
                            + "; an AP payment cannot be booked without it (" + e.getMessage() + ")",
                    POSTING_CATEGORY,
                    mappingKey,
                    "Set up the " + POSTING_CATEGORY + "/" + mappingKey + " GL mapping, then pay again.");
        }
    }

    private void requireMapping(String mappingKey, LocalDate date) {
        resolve(mappingKey, date.atStartOfDay());
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

    private boolean isEligible(UUID glAccountId, LocalDate date, LocalDateTime fundingCutoff) {
        return glAccounts
                .findById(glAccountId)
                .filter(a -> isEligible(a, date, fundingCutoff))
                .isPresent();
    }

    private List<UUID> eligibleBankAccounts(LocalDate date, LocalDateTime fundingCutoff) {
        return glAccounts.findBySubtypeActiveAt(AccountSubtype.BANK_CASH, date.atStartOfDay(), fundingCutoff).stream()
                .filter(account -> !isForeign(account))
                .map(GLAccount::getGlAccountId)
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
        return active && !isForeign(account);
    }

    private boolean isForeign(GLAccount account) {
        return bankAccountCurrencies
                .currencyOf(account.getGlAccountId())
                .filter(ledgerCurrency::isForeign)
                .isPresent();
    }

    private static VendorBillException bankAccountRefused(String message) {
        return new VendorBillException(
                VendorBillException.Code.VALIDATION_ERROR,
                message,
                List.of(new VendorBillException.FieldError(BANK_ACCOUNT_FIELD, message)),
                null);
    }
}
