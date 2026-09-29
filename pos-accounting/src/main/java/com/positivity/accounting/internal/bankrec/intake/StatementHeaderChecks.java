package com.positivity.accounting.internal.bankrec.intake;

import com.positivity.accounting.internal.bankrec.entity.BankStatement;
import com.positivity.accounting.internal.bankrec.enums.BankStatementStatus;
import com.positivity.accounting.internal.bankrec.repository.BankStatementRepository;
import com.positivity.accounting.internal.bankrec.service.FunctionalCurrency;
import com.positivity.domainevents.bankfeed.BankTransactionsObservedV1.StatementHeader;
import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * The statement-header rules of §4.2 in their one order (SPEC-manual-bank-reconciliation §3.1, §4.2,
 * §4.5; stories S2 #2301, S3 #2302): the end date not in the future, the acknowledgement's shape
 * (step 1), U1, U2, then contiguity E2 with the acknowledgement (steps 2–3). The intake runs them at
 * commit; the file adapter runs them through {@link BankIntakeLookup#checkHeader} at upload and again
 * before commit, so the preparer learns of a refusal before mapping rows.
 */
final class StatementHeaderChecks {

    private StatementHeaderChecks() {}

    /** Runs the checks against the account's COMMITTED statements; throws the first refusal. */
    static BankIntakeLookup.@NonNull HeaderCheck check(
            @NonNull BankStatementRepository statements,
            @NonNull FunctionalCurrency functionalCurrency,
            @NonNull Clock clock,
            @NonNull UUID glAccountId,
            @NonNull StatementHeader header,
            @Nullable String gapAcknowledgement) {
        LocalDate today = LocalDate.now(clock);
        if (header.endDate().isAfter(today)) {
            throw BankRecException.field(
                    BankRecErrorCode.VALIDATION_ERROR,
                    "Statement endDate " + header.endDate() + " is in the future",
                    "statement.endDate",
                    "must not be after " + today);
        }
        String acknowledgement = Justification.optional(gapAcknowledgement, "gapAcknowledgement");

        // U1, then U2 (a window equal to a committed one overlaps it too, so U1 answers first).
        statements
                .findFirstByGlAccountIdAndStatusAndStartDateAndEndDate(
                        glAccountId, BankStatementStatus.COMMITTED, header.startDate(), header.endDate())
                .ifPresent(same -> {
                    throw BankRecException.field(
                            BankRecErrorCode.STATEMENT_ALREADY_IMPORTED,
                            "A statement for " + header.startDate() + ".." + header.endDate()
                                    + " is already committed on this account",
                            "statementId",
                            same.getStatementId().toString());
                });
        statements
                .findFirstByGlAccountIdAndStatusAndStartDateLessThanEqualAndEndDateGreaterThanEqualOrderByStartDateAsc(
                        glAccountId, BankStatementStatus.COMMITTED, header.endDate(), header.startDate())
                .ifPresent(overlapping -> {
                    throw BankRecException.field(
                            BankRecErrorCode.STATEMENT_PERIOD_OVERLAP,
                            "The window " + header.startDate() + ".." + header.endDate()
                                    + " overlaps a committed statement on this account",
                            "statementId",
                            overlapping.getStatementId().toString());
                });

        // E2 against the previous COMMITTED statement, with the acknowledgement (§4.2 steps 2–3).
        Optional<BankStatement> previous =
                statements.findFirstByGlAccountIdAndStatusAndEndDateLessThanOrderByEndDateDesc(
                        glAccountId, BankStatementStatus.COMMITTED, header.startDate());
        Map<String, String> discontinuities = discontinuities(previous, header, functionalCurrency);
        if (!discontinuities.isEmpty() && acknowledgement == null) {
            throw new BankRecException(
                    BankRecErrorCode.STATEMENT_NOT_CONTIGUOUS,
                    previous.isEmpty()
                            ? "The account's first statement needs a gapAcknowledgement (at least "
                                    + Justification.MIN_LENGTH + " characters)"
                            : "The statement does not continue the previous statement; correct the header or"
                                    + " commit it with a gapAcknowledgement",
                    discontinuities);
        }
        if (discontinuities.isEmpty() && acknowledgement != null) {
            throw BankRecException.field(
                    BankRecErrorCode.STATEMENT_GAP_ACKNOWLEDGEMENT_NOT_APPLICABLE,
                    "The statement continues the previous statement; a gapAcknowledgement is not accepted",
                    "gapAcknowledgement",
                    "not applicable to a contiguous statement");
        }
        return new BankIntakeLookup.HeaderCheck(
                previous.map(BankStatement::getStatementId).orElse(null), discontinuities.isEmpty(), acknowledgement);
    }

    private static Map<String, String> discontinuities(
            Optional<BankStatement> previous, StatementHeader header, FunctionalCurrency functionalCurrency) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        if (previous.isEmpty()) {
            fieldErrors.put("gapAcknowledgement", "required for the account's first statement");
            return fieldErrors;
        }
        BankStatement before = previous.get();
        if (before.getClosingBalance().compareTo(header.openingBalance()) != 0) {
            fieldErrors.put("openingBalance", "expected " + functionalCurrency.display(before.getClosingBalance()));
        }
        LocalDate expectedStart = before.getEndDate().plusDays(1);
        if (!expectedStart.equals(header.startDate())) {
            fieldErrors.put("startDate", "expected " + expectedStart);
        }
        return fieldErrors;
    }
}
