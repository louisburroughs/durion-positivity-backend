package com.positivity.accounting.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The closed register sessions whose drawer cash has not reached the bank (CAP:550 S18, #2514; SPEC-accounting-workspace
 * §4.5, §5.1.1, §7.1 "Undeposited sessions"), oldest first, and, for a selection, the deposit the server would post
 * (P7: the dialog never sums amounts itself). Every amount is in {@code currencyCode} (ADR-0067 R-1).
 */
@Schema(description = "Undeposited register sessions and, for a selection, the deposit they make")
public record UndepositedSessionsResponse(
        @Schema(description = "Today in the tenant's accounting time zone: the day the ages count to") LocalDate asOf,
        @Schema(description = "ISO 4217 code of every amount: the functional currency", example = "USD")
                String currencyCode,
        @Schema(description = "The undeposited sessions the caller may deposit, oldest close first")
                List<Session> sessions,
        @Schema(description = "The deposit the selected sessions make; null when no sessionId was given") @Nullable
                Selection selection) {

    /** One undeposited session. */
    @Schema(description = "A closed register session whose drawer cash waits to be deposited")
    public record Session(
            @Schema(description = "The register session") UUID sessionId,
            @Schema(description = "The register", example = "T-7") String terminalId,
            @Schema(description = "The session's location; null when it carried none") @Nullable UUID locationId,
            @Schema(description = "When the session closed") Instant closedAt,
            @Schema(description = "The close date in the tenant's accounting time zone") LocalDate closeDate,
            @Schema(description = "Days from the close date to asOf", example = "2") long ageDays,
            @Schema(description = "The drawer's opening float", example = "200.00") BigDecimal openingFloat,
            @Schema(description = "Cash counted at close", example = "200.00") BigDecimal countedCash,
            @Schema(description = "Cash the drawer should have held at close", example = "203.00")
                    BigDecimal theoreticalCash,
            @Schema(description = "Counted minus theoretical: positive over, negative short", example = "-3.00")
                    BigDecimal overShort,
            @Schema(description = "The CASH tender total: what the session's sales put in 1090", example = "1240.00")
                    BigDecimal expectedCash,
            @Schema(
                            description = "The signed sum (debit positive) of the 1095 lines the session's over/short and"
                                    + " drawer movements posted",
                            example = "-43.00")
                    BigDecimal clearingNet,
            @Schema(description = "The total of the session's bank drops", example = "1197.00")
                    BigDecimal depositAmount,
            @Schema(description = "The session's bank drops, in the order recorded") List<Drop> drops) {}

    /** One bank drop of a session. */
    @Schema(description = "A bank drop: the bag and the amount the deposit takes")
    public record Drop(
            @Schema(description = "pos-order's drawer movement") UUID movementId,
            @Schema(description = "The deposit bag; null when none was keyed", example = "B-0912") @Nullable
                    String bagNumber,
            @Schema(description = "The amount dropped", example = "1197.00") BigDecimal amount) {}

    /** The deposit the selected sessions make. */
    @Schema(description = "What recording a deposit of the selected sessions posts; nothing posts here")
    public record Selection(
            @Schema(description = "The selected sessions") List<UUID> sessionIds,
            @Schema(description = "The bank debit: the selected sessions' bank drops", example = "1197.00")
                    BigDecimal depositAmount,
            @Schema(description = "The credit to 1090: the selected sessions' expected cash", example = "1240.00")
                    BigDecimal expectedCash,
            @Schema(description = "The selected sessions' clearing net, debit positive", example = "-43.00")
                    BigDecimal clearingNet,
            @Schema(
                            description = "depositAmount - expectedCash - clearingNet; Record bank deposit refuses any"
                                    + " value but zero (422 DEPOSIT_UNBALANCED)",
                            example = "0.00")
                    BigDecimal difference,
            @Schema(description = "Whether difference is zero, so the deposit can be recorded") boolean balanced,
            @Schema(description = "The entry's lines: bank, 1090 and 1095 (a zero line is left out)")
                    List<PreviewLine> lines) {}

    /** One line of the entry a deposit would post. */
    @Schema(description = "A line of the entry the deposit would post")
    public record PreviewLine(
            @Schema(description = "The GL account; null for the bank line when no bankGlAccountId was given")
                    @Nullable
                    UUID glAccountId,
            @Schema(description = "The account number; null as glAccountId", example = "1095") @Nullable
                    String accountNumber,
            @Schema(description = "The account name; null as glAccountId", example = "Register Cash Clearing")
                    @Nullable
                    String accountName,
            @Schema(description = "DEBIT or CREDIT", example = "DEBIT") Side side,
            @Schema(description = "The line's amount, never negative", example = "43.00") BigDecimal amount) {}

    /** The side of a preview line. */
    public enum Side {
        DEBIT,
        CREDIT
    }
}
