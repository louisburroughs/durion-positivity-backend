package com.positivity.accounting.internal.bankfeed.file.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.accounting.internal.bankfeed.file.entity.BankImportRow;
import com.positivity.accounting.internal.bankfeed.file.enums.BankImportRowStatus;
import com.positivity.accounting.internal.bankfeed.file.parser.CsvStatementFileParser;
import com.positivity.accounting.internal.bankfeed.file.parser.ParserOptions;
import com.positivity.accounting.internal.bankfeed.file.service.ImportEvaluator.Segment;
import com.positivity.accounting.internal.bankfeed.file.service.ImportEvaluator.SplitPoint;
import com.positivity.accounting.internal.bankrec.intake.BankIntakeLookup.BankAccountTerms;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The format-neutral row rules of an import (SPEC-manual-bank-reconciliation §3.8, §4.4, §4.5, §5.7,
 * §8.1–§8.2; story S3, #2302).
 */
@DisplayName("ImportEvaluator (#2302)")
class ImportEvaluatorTest {

    private static final UUID ACCOUNT = UUID.fromString("5eed0acc-0000-4000-8000-000000001000");
    private static final UUID IMPORT = UUID.fromString("01990000-0000-7000-8000-0000000000aa");
    private static final LocalDate START = LocalDate.of(2026, 9, 1);
    private static final LocalDate END = LocalDate.of(2026, 9, 30);
    private static final BankAccountTerms USD = new BankAccountTerms(ACCOUNT, "1000", "Cash", "USD", 2, null, true);

    private final CsvStatementFileParser parser = new CsvStatementFileParser();

    private List<BankImportRow> rows(String csv) {
        return rows(csv, "USD");
    }

    private List<BankImportRow> rows(String csv, String currency) {
        List<BankImportRow> rows = ImportEvaluator.fromParse(
                IMPORT,
                parser.parse(csv.getBytes(StandardCharsets.UTF_8), ParserOptions.defaults(), null)
                        .rows(),
                currency);
        List<BankImportRow> mutable = new ArrayList<>(rows);
        for (int i = 0; i < mutable.size(); i++) {
            mutable.get(i).setRowId(UUID.fromString(String.format("01990000-0000-7000-8000-%012d", i)));
        }
        return mutable;
    }

    private static void evaluate(List<BankImportRow> rows, Map<String, UUID> stored) {
        ImportEvaluator.evaluate(rows, ACCOUNT, START, END, fingerprints -> stored);
    }

    private static List<Segment> whole(String opening, String closing) {
        return ImportEvaluator.segments(START, END, new BigDecimal(opening), new BigDecimal(closing), List.of());
    }

    @Nested
    @DisplayName("amount precision against the import currency (#2336)")
    class AmountPrecision {

        @Test
        void aRowFinerThanTheMinorUnitIsRejectedAndTheOthersStayValid() {
            List<BankImportRow> rows = rows("2026-09-02,DEP,100.00\n2026-09-03,ODD,12.345\n2026-09-04,FEE,-5.00");
            evaluate(rows, Map.of());

            assertThat(rows)
                    .extracting(BankImportRow::getRowStatus)
                    .containsExactly(
                            BankImportRowStatus.PARSED, BankImportRowStatus.REJECTED, BankImportRowStatus.PARSED);
            assertThat(rows.get(1).getRejectionCode()).isEqualTo("AMOUNT_PRECISION_EXCEEDS_CURRENCY");
            assertThat(rows.get(1).getRejectionDetail()).contains("12.345").contains("at most 2 decimal places");
            assertThat(rows.get(1).getSignedAmount()).isEqualByComparingTo("12.345");
            assertThat(rows.get(0).getRejectionCode()).isNull();
        }

        @Test
        void trailingZerosDoNotCount() {
            List<BankImportRow> rows = rows("2026-09-02,DEP,12.340\n2026-09-03,DEP,10.0000");
            evaluate(rows, Map.of());

            assertThat(rows).extracting(BankImportRow::getRowStatus).containsOnly(BankImportRowStatus.PARSED);
        }

        @Test
        void aZeroDecimalCurrencyRejectsAFraction() {
            List<BankImportRow> rows = rows("2026-09-02,DEP,12.5\n2026-09-03,DEP,12", "JPY");
            evaluate(rows, Map.of());

            assertThat(rows.get(0).getRowStatus()).isEqualTo(BankImportRowStatus.REJECTED);
            assertThat(rows.get(0).getRejectionCode()).isEqualTo("AMOUNT_PRECISION_EXCEEDS_CURRENCY");
            assertThat(rows.get(0).getRejectionDetail()).contains("at most 0 decimal places for JPY");
            assertThat(rows.get(1).getRowStatus()).isEqualTo(BankImportRowStatus.PARSED);
        }

        @Test
        void aThreeDecimalCurrencyAcceptsThousandths() {
            List<BankImportRow> rows = rows("2026-09-02,DEP,12.345\n2026-09-03,DEP,1.2345", "KWD");
            evaluate(rows, Map.of());

            assertThat(rows.get(0).getRowStatus()).isEqualTo(BankImportRowStatus.PARSED);
            assertThat(rows.get(1).getRowStatus()).isEqualTo(BankImportRowStatus.REJECTED);
        }

        @Test
        void aParseRejectionTakesPrecedenceOverPrecision() {
            List<BankImportRow> rows = rows("2026-13-45,BAD,12.345");
            evaluate(rows, Map.of());

            // The parser's rejection names the first failing column; the amount is kept on the row and
            // is precision-checked when the date is corrected (BankImportServiceImpl#correct).
            assertThat(rows.get(0).getRowStatus()).isEqualTo(BankImportRowStatus.REJECTED);
            assertThat(rows.get(0).getRejectionCode()).isEqualTo("DATE_UNPARSEABLE");
            assertThat(rows.get(0).getSignedAmount()).isEqualByComparingTo("12.345");
        }

        @Test
        void evaluateDoesNotClearThePrecisionRejection() {
            List<BankImportRow> rows = rows("2026-09-03,ODD,12.345");
            evaluate(rows, Map.of());
            evaluate(rows, Map.of());

            assertThat(rows.get(0).getRowStatus()).isEqualTo(BankImportRowStatus.REJECTED);
            assertThat(rows.get(0).getRejectionCode()).isEqualTo("AMOUNT_PRECISION_EXCEEDS_CURRENCY");
        }
    }

    @Nested
    @DisplayName("row states (§3.8)")
    class States {

        @Test
        void parseRejectionsStayRejectedAndGoodRowsAreParsed() {
            List<BankImportRow> rows = rows("2026-09-02,DEP,100.00\n2026-13-45,BAD,1.00\n2026-09-03,FEE,-5.00");
            evaluate(rows, Map.of());

            assertThat(rows)
                    .extracting(BankImportRow::getRowStatus)
                    .containsExactly(
                            BankImportRowStatus.PARSED, BankImportRowStatus.REJECTED, BankImportRowStatus.PARSED);
            assertThat(rows.get(1).getRejectionCode()).isEqualTo("DATE_UNPARSEABLE");
            assertThat(rows.get(1).getRawValues()).containsEntry("column 1", "2026-13-45");
            assertThat(rows.get(0).getFingerprint()).hasSize(64);
            assertThat(rows.get(0).getSignedAmount().scale()).isEqualTo(4);
        }

        @Test
        void aRowOutsideTheWindowIsOutOfWindowAndReturnsWhenTheWindowWidens() {
            // §4.5: a September re-download that includes the last days of August.
            List<BankImportRow> rows = rows("2026-08-30,AUG,10.00\n2026-09-02,SEP,20.00");
            evaluate(rows, Map.of());
            assertThat(rows.get(0).getRowStatus()).isEqualTo(BankImportRowStatus.OUT_OF_WINDOW);

            ImportEvaluator.evaluate(rows, ACCOUNT, LocalDate.of(2026, 8, 29), END, f -> Map.of());
            assertThat(rows.get(0).getRowStatus()).isEqualTo(BankImportRowStatus.PARSED);
        }

        @Test
        void aCorrectedDateOutsideTheWindowIsRejectedDateOutsideStatement() {
            List<BankImportRow> rows = rows("2026-09-02,SEP,20.00");
            rows.get(0).setCorrectedValues(Map.of("date", "2026-10-02"));
            rows.get(0).setTransactionDate(LocalDate.of(2026, 10, 2));
            evaluate(rows, Map.of());

            assertThat(rows.get(0).getRowStatus()).isEqualTo(BankImportRowStatus.REJECTED);
            assertThat(rows.get(0).getRejectionCode()).isEqualTo("DATE_OUTSIDE_STATEMENT");

            rows.get(0).setTransactionDate(LocalDate.of(2026, 9, 3));
            evaluate(rows, Map.of());
            assertThat(rows.get(0).getRowStatus()).isEqualTo(BankImportRowStatus.CORRECTED);
        }

        @Test
        void twoIdenticalRowsOfOneFileAreBothPossibleDuplicatesPointingAtEachOther() {
            // §8.2: two genuine $5.00 fees on one day.
            List<BankImportRow> rows = rows("2026-09-02,FEE,-5.00\n2026-09-02,FEE,-5.00\n2026-09-03,OTHER,1.00");
            evaluate(rows, Map.of());

            assertThat(rows.get(0).getRowStatus()).isEqualTo(BankImportRowStatus.POSSIBLE_DUPLICATE);
            assertThat(rows.get(1).getRowStatus()).isEqualTo(BankImportRowStatus.POSSIBLE_DUPLICATE);
            assertThat(rows.get(0).getDuplicateOfRowNumber()).isEqualTo(2);
            assertThat(rows.get(1).getDuplicateOfRowNumber()).isEqualTo(1);
            assertThat(rows.get(2).getRowStatus()).isEqualTo(BankImportRowStatus.PARSED);
        }

        @Test
        void rowsThatAllCarryASourceIdNeverCollideWithEachOther() {
            List<BankImportRow> rows = rows("2026-09-02,FEE,-5.00\n2026-09-02,FEE,-5.00");
            rows.get(0).setSourceTransactionId("T1");
            rows.get(1).setSourceTransactionId("T2");
            evaluate(rows, Map.of());
            assertThat(rows).allMatch(r -> r.getRowStatus() == BankImportRowStatus.PARSED);
        }

        @Test
        void aCollisionWithAStoredTransactionNamesItAndADistinctDecisionClearsTheFlag() {
            List<BankImportRow> rows = rows("2026-09-02,FEE,-5.00");
            UUID stored = UUID.fromString("01980000-0000-7000-8000-0000000000cc");
            String fingerprint = ImportEvaluator.fingerprint(ACCOUNT, rows.get(0));
            evaluate(rows, Map.of(fingerprint, stored));
            assertThat(rows.get(0).getRowStatus()).isEqualTo(BankImportRowStatus.POSSIBLE_DUPLICATE);
            assertThat(rows.get(0).getDuplicateOfBankTransactionId()).isEqualTo(stored);

            rows.get(0).setDuplicateDecision(ImportEvaluator.DISTINCT);
            evaluate(rows, Map.of(fingerprint, stored));
            assertThat(rows.get(0).getRowStatus()).isEqualTo(BankImportRowStatus.PARSED);
            assertThat(rows.get(0).getDuplicateOfBankTransactionId()).isEqualTo(stored);
        }

        @Test
        void aSkippedRowTakesNoPartInCollisionsAndKeepsItsState() {
            List<BankImportRow> rows = rows("2026-09-02,FEE,-5.00\n2026-09-02,FEE,-5.00");
            rows.get(1).setRowStatus(BankImportRowStatus.SKIPPED);
            evaluate(rows, Map.of());
            assertThat(rows.get(0).getRowStatus()).isEqualTo(BankImportRowStatus.PARSED);
            assertThat(rows.get(1).getRowStatus()).isEqualTo(BankImportRowStatus.SKIPPED);
        }

        @Test
        void theFingerprintIsTheIntakes() {
            List<BankImportRow> rows = rows("2026-09-02,Monthly fee.,-5,F1");
            assertThat(ImportEvaluator.fingerprint(ACCOUNT, rows.get(0)))
                    .isEqualTo(com.positivity.accounting.internal.bankrec.intake.TransactionNormalizer.fingerprint(
                            ACCOUNT, LocalDate.of(2026, 9, 2), new BigDecimal("-5.00"), "MONTHLY FEE", "F1", null));
        }
    }

    @Nested
    @DisplayName("counts and preview (§3.3, §4.4)")
    class CountsAndPreview {

        @Test
        void countsCoverEveryStatus() {
            List<BankImportRow> rows = rows("2026-09-02,A,1\n2026-09-02,B,2\n2026-13-45,C,3\n2026-08-01,D,4\n"
                    + "2026-09-05,E,5\n2026-09-05,E,5");
            rows.get(1).setRowStatus(BankImportRowStatus.SKIPPED);
            evaluate(rows, Map.of());

            ImportEvaluator.Counts counts = ImportEvaluator.counts(rows);
            assertThat(counts).isEqualTo(new ImportEvaluator.Counts(6, 1, 1, 2, 1, 1));
        }

        @Test
        void thePreviewShowsTheFirstFiveRowsWithARunningBalanceOverCountedRowsOnly() {
            List<BankImportRow> rows = rows("2026-09-02,A,100\n2026-13-45,B,1\n2026-09-03,C,-30\n2026-09-04,D,5\n"
                    + "2026-09-05,E,5.5\n2026-09-06,F,1000");
            evaluate(rows, Map.of());

            ImportEvaluator.Preview preview = ImportEvaluator.preview(rows, whole("1000", "2081.5"), USD);
            assertThat(preview.firstRows()).hasSize(5);
            assertThat(preview.firstRows())
                    .extracting(ImportEvaluator.PreviewRow::runningBalance)
                    .usingElementComparator((a, b) -> a == null || b == null ? (a == b ? 0 : 1) : a.compareTo(b))
                    .containsExactly(
                            new BigDecimal("1100"),
                            null,
                            new BigDecimal("1070"),
                            new BigDecimal("1075"),
                            new BigDecimal("1080.5"));
            assertThat(preview.segments()).singleElement().satisfies(total -> {
                assertThat(total.activityTotal()).isEqualByComparingTo("1080.5");
                assertThat(total.expectedClosing()).isEqualByComparingTo("2080.5");
                assertThat(total.ties()).isFalse();
                assertThat(total.transactionCount()).isEqualTo(5);
            });
        }
    }

    @Nested
    @DisplayName("commit preconditions (§4.4, E1 [M])")
    class Blockers {

        @Test
        void aRejectedAndAnOutOfWindowRowAreNamedByRowNumber() {
            List<BankImportRow> rows = rows("2026-09-02,A,10\n2026-13-45,B,1\n2026-08-31,C,5");
            evaluate(rows, Map.of());

            Map<String, String> blockers = ImportEvaluator.blockers(rows, whole("0", "10"), USD);
            assertThat(blockers).containsOnlyKeys("rows[2]", "rows[3]");
            assertThat(blockers.get("rows[2]")).startsWith("REJECTED DATE_UNPARSEABLE");
            assertThat(blockers.get("rows[3]")).startsWith("OUT_OF_WINDOW");
        }

        @Test
        void e1FailureNamesBothSides() {
            // §4.4: header closing 10,000.00, rows sum from opening to 9,985.00.
            List<BankImportRow> rows = rows("2026-09-02,A,985.00");
            evaluate(rows, Map.of());

            assertThat(ImportEvaluator.blockers(rows, whole("9000", "10000"), USD))
                    .containsExactly(Map.entry("activityTotal", "opening + activity = 9985.00, closing = 10000.00"));
        }

        @Test
        void e1ToleratesExactlyOneMinorUnitAndNotMore() {
            List<BankImportRow> rows = rows("2026-09-02,A,99.99");
            evaluate(rows, Map.of());
            assertThat(ImportEvaluator.blockers(rows, whole("0", "100.00"), USD))
                    .isEmpty();

            List<BankImportRow> beyond = rows("2026-09-02,A,99.98");
            evaluate(beyond, Map.of());
            assertThat(ImportEvaluator.blockers(beyond, whole("0", "100.00"), USD))
                    .containsKey("activityTotal");
        }

        @Test
        void possibleDuplicatesDoNotBlockAndCountTowardE1() {
            List<BankImportRow> rows = rows("2026-09-02,FEE,-5\n2026-09-02,FEE,-5");
            evaluate(rows, Map.of());
            assertThat(ImportEvaluator.blockers(rows, whole("10", "0"), USD)).isEmpty();
        }

        @Test
        void aFileWithEveryRowSkippedHasNothingToCommit() {
            List<BankImportRow> rows = rows("2026-09-02,A,10");
            rows.getFirst().setRowStatus(BankImportRowStatus.SKIPPED);
            assertThat(ImportEvaluator.blockers(rows, whole("0", "0"), USD)).containsOnlyKeys("rows");
        }

        @Test
        void aSplitFileChecksE1PerSegment() {
            // §5.7: 09-15..10-14 split at 09-30 with the keyed September close.
            List<BankImportRow> rows = rows("2026-09-20,SEP,100\n2026-10-05,OCT,50");
            ImportEvaluator.evaluate(
                    rows, ACCOUNT, LocalDate.of(2026, 9, 15), LocalDate.of(2026, 10, 14), f -> Map.of());
            List<Segment> segments = ImportEvaluator.segments(
                    LocalDate.of(2026, 9, 15),
                    LocalDate.of(2026, 10, 14),
                    new BigDecimal("1000"),
                    new BigDecimal("1150"),
                    List.of(new SplitPoint(LocalDate.of(2026, 9, 30), new BigDecimal("1100"))));

            assertThat(segments)
                    .extracting(Segment::startDate, Segment::endDate)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple(LocalDate.of(2026, 9, 15), LocalDate.of(2026, 9, 30)),
                            org.assertj.core.groups.Tuple.tuple(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 14)));
            assertThat(segments.get(1).openingBalance()).isEqualByComparingTo("1100");
            assertThat(ImportEvaluator.blockers(rows, segments, USD)).isEmpty();

            List<Segment> wrongKey = ImportEvaluator.segments(
                    LocalDate.of(2026, 9, 15),
                    LocalDate.of(2026, 10, 14),
                    new BigDecimal("1000"),
                    new BigDecimal("1150"),
                    List.of(new SplitPoint(LocalDate.of(2026, 9, 30), new BigDecimal("1090"))));
            assertThat(ImportEvaluator.blockers(rows, wrongKey, USD))
                    .containsOnlyKeys("segments[0].activityTotal", "segments[1].activityTotal");
        }

        @Test
        void aSplitSegmentWithoutRowsIsBlocked() {
            List<BankImportRow> rows = rows("2026-09-20,SEP,100");
            evaluate(rows, Map.of());
            List<Segment> segments = ImportEvaluator.segments(
                    START,
                    END,
                    BigDecimal.ZERO,
                    new BigDecimal("100"),
                    List.of(new SplitPoint(LocalDate.of(2026, 9, 10), BigDecimal.ZERO)));
            assertThat(ImportEvaluator.blockers(rows, segments, USD)).containsOnlyKeys("segments[0].rows");
        }
    }
}
