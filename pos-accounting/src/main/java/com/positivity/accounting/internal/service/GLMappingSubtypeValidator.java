package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.accounting.internal.enums.AccountSubtype;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/**
 * Non-blocking plausibility check for GL mapping target-account subtypes
 * (Story H1, Issue #934).
 *
 * Cash-receipt / settlement posting codes (e.g. payment application,
 * settlement clearing) are expected to resolve to accounts whose subtype is
 * BANK_CASH, UNDEPOSITED_FUNDS, or RECEIVABLE. When a mapping for such a code
 * targets an account with a different (non-null) subtype, this validator logs
 * a WARN and returns the warning text — it never rejects the mapping, and
 * accounts without a subtype are never flagged (backfill is optional).
 *
 * Invoked from {@link GLMappingServiceImpl#createMapping} (source-system/
 * external-code mappings) and from
 * {@link DefaultGLMappingServiceImpl#createDefaultMapping} /
 * {@link DefaultGLMappingServiceImpl#updateDefaultMapping} (default event-type
 * mappings) — the production seams where posting configuration is created or
 * changed.
 */
@Slf4j
@Component
public class GLMappingSubtypeValidator {

    /** Subtypes plausible as targets of cash-receipt/settlement mappings. */
    static final Set<AccountSubtype> CASH_RECEIPT_PLAUSIBLE_SUBTYPES =
            EnumSet.of(AccountSubtype.RECEIVABLE, AccountSubtype.BANK_CASH, AccountSubtype.UNDEPOSITED_FUNDS);

    /**
     * Markers identifying cash-receipt/settlement posting codes. Matched as
     * case-insensitive substrings of the mapping's external code so variants
     * like {@code SETTLEMENT_CLEARED} or {@code PAYMENT_APPLICATION_DEFAULT}
     * are covered.
     */
    private static final Set<String> CASH_RECEIPT_CODE_MARKERS =
            Set.of("PAYMENT_APPLICATION", "PAYMENT_RECEIVED", "CASH_RECEIPT", "SETTLEMENT");

    /**
     * Check the target account's subtype plausibility for the given external
     * code. Logs a WARN and returns the warning text when the code is a
     * cash-receipt/settlement code and the account carries an implausible
     * subtype; returns empty otherwise.
     *
     * @param externalCode the mapping's external code
     * @param glAccount    the resolved target GL account
     * @return the warning text if a plausibility warning fired, else empty
     */
    public Optional<String> checkCashReceiptSubtype(@NonNull String externalCode, @NonNull GLAccount glAccount) {
        if (!isCashReceiptCode(externalCode)) {
            return Optional.empty();
        }
        AccountSubtype subtype = glAccount.getAccountSubtype();
        if (subtype == null || CASH_RECEIPT_PLAUSIBLE_SUBTYPES.contains(subtype)) {
            return Optional.empty();
        }
        String warning = String.format(
                "GL mapping plausibility warning: cash-receipt/settlement code '%s' maps to account %s (%s) "
                        + "with subtype %s; expected one of %s. Mapping accepted (warning only).",
                externalCode,
                glAccount.getAccountCode(),
                glAccount.getAccountName(),
                subtype,
                CASH_RECEIPT_PLAUSIBLE_SUBTYPES);
        log.warn(warning);
        return Optional.of(warning);
    }

    private static boolean isCashReceiptCode(String externalCode) {
        String normalized = externalCode.toUpperCase(Locale.ROOT);
        return CASH_RECEIPT_CODE_MARKERS.stream().anyMatch(normalized::contains);
    }
}
