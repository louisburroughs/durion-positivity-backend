package com.positivity.accounting.internal.bankrec.intake;

import com.positivity.domainevents.bankfeed.BankTransactionsObservedV1.StatementHeader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * The read side of the intake port (SPEC-manual-bank-reconciliation §2.1, §4.2–§4.5; story S3, #2302):
 * what an adapter may ask the core before it hands over a batch, so its preview can show the refusals
 * {@link BankTransactionIntake#accept} would answer — without the adapter reaching the core's
 * repositories or services (the {@code ..bankfeed..} wall). Nothing here commits a statement or a
 * transaction; {@link #saveDefaultColumnMapping} is the one write, on an existing profile.
 */
public interface BankIntakeLookup {

    /**
     * A bank account as the intake sees it (D5, D18 as amended by ADR-0067).
     *
     * @param currency the profile's currency, or the ledger currency while the account has no profile
     * @param fractionDigits decimal places of {@link #currency}'s minor unit
     * @param defaultColumnMapping the profile's saved column mapping; null when none
     * @param profileExists whether the account has a {@code bank_account_profile} row
     */
    record BankAccountTerms(
            @NonNull UUID glAccountId,
            @NonNull String accountCode,
            @NonNull String accountName,
            @NonNull String currency,
            int fractionDigits,
            @Nullable Map<String, Object> defaultColumnMapping,
            boolean profileExists) {

        /** One minor unit: the E1 tolerance. */
        public @NonNull BigDecimal tolerance() {
            return BigDecimal.ONE.movePointLeft(fractionDigits);
        }

        /** An amount for a message: at least the currency's decimals, never scientific notation. */
        public @NonNull String display(@NonNull BigDecimal amount) {
            int scale = Math.max(fractionDigits, amount.stripTrailingZeros().scale());
            return amount.setScale(scale, RoundingMode.UNNECESSARY).toPlainString();
        }
    }

    /**
     * The outcome of the header checks when none refuses.
     *
     * @param previousStatementId the COMMITTED statement this one follows; null for the account's first
     * @param contiguous whether the header continues that statement (E2)
     * @param gapAcknowledgement the trimmed acknowledgement; non-null exactly when not contiguous
     */
    record HeaderCheck(
            @Nullable UUID previousStatementId,
            boolean contiguous,
            @Nullable String gapAcknowledgement) {}

    /** A GL account's display values (ADR-0064: shown beside the id). */
    record AccountDisplay(
            @NonNull UUID glAccountId,
            @NonNull String accountCode,
            @NonNull String accountName) {}

    /** Display values for the given ids, whatever their eligibility; ids the tenant does not hold are absent. */
    @NonNull
    Map<UUID, AccountDisplay> accountDisplay(@NonNull Collection<UUID> glAccountIds);

    /**
     * The account a statement would be written to.
     *
     * @throws BankRecException {@code VALIDATION_ERROR} (field {@code glAccountId}) for an unknown
     *     account; {@code ACCOUNT_NOT_RECONCILABLE} for one that is not a reconcilable {@code BANK_CASH}
     *     account
     */
    @NonNull
    BankAccountTerms requireAccount(@NonNull UUID glAccountId);

    /**
     * Runs the statement-header checks of §4.2 in the intake's order — end date, acknowledgement shape,
     * U1, U2, contiguity with the acknowledgement — against the account's COMMITTED statements now.
     *
     * @throws BankRecException the first refusal, with the code {@link BankTransactionIntake#accept}
     *     would answer
     */
    @NonNull
    HeaderCheck checkHeader(
            @NonNull UUID glAccountId, @NonNull StatementHeader header, @Nullable String gapAcknowledgement);

    /**
     * R1 against the account's stored transactions: for each fingerprint that collides, the earliest
     * transaction it collides with ({@code EXCLUDED} and {@code REMOVED_BY_SOURCE} rows never collide).
     */
    @NonNull
    Map<String, UUID> collidingFingerprints(@NonNull UUID glAccountId, @NonNull Collection<String> fingerprints);

    /**
     * Saves a column mapping as the account's default (§3.1, §4.4 {@code saveAsAccountDefault}) when the
     * account has a profile, audited as {@code BANK_ACCOUNT_PROFILE_SET}. An account without one gets
     * the mapping when the intake creates its profile at commit ({@link IntakeContext#defaultColumnMapping}).
     *
     * @return whether a profile was updated
     */
    boolean saveDefaultColumnMapping(
            @NonNull UUID glAccountId, @NonNull Map<String, Object> mapping, @NonNull String actor);
}
