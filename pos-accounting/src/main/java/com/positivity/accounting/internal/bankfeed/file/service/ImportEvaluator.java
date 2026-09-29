package com.positivity.accounting.internal.bankfeed.file.service;

import com.positivity.accounting.internal.bankfeed.file.entity.BankImportRow;
import com.positivity.accounting.internal.bankfeed.file.enums.BankImportRowStatus;
import com.positivity.accounting.internal.bankfeed.file.parser.ParsedRow;
import com.positivity.accounting.internal.bankfeed.file.parser.RejectionCode;
import com.positivity.accounting.internal.bankrec.intake.BankIntakeLookup.BankAccountTerms;
import com.positivity.accounting.internal.bankrec.intake.TransactionNormalizer;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * The format-neutral rules of an import's rows (SPEC-manual-bank-reconciliation §3.3, §3.8, §4.4, §4.5;
 * story S3, #2302): the row states, the §3.2 fingerprint against the account's stored transactions and
 * the other rows of the file, the counts, the preview running total and the commit preconditions. It
 * holds no state and reads nothing itself; the service hands it the stored collisions.
 */
final class ImportEvaluator {

    /** Duplicate decisions a human records on a row (§4.4). */
    static final String DISTINCT = "DISTINCT";

    static final String DUPLICATE = "DUPLICATE";

    /** Rows the commit hands to the intake. */
    static final Set<BankImportRowStatus> COMMITTABLE = EnumSet.of(
            BankImportRowStatus.PARSED, BankImportRowStatus.CORRECTED, BankImportRowStatus.POSSIBLE_DUPLICATE);

    /** How many rows the preview lists (§4.4: "the first five rows"). */
    static final int PREVIEW_ROWS = 5;

    private ImportEvaluator() {}

    /** One statement a commit creates: the whole header window, or one {@code splitAt} segment of it (§5.7). */
    record Segment(
            @NonNull LocalDate startDate,
            @NonNull LocalDate endDate,
            @NonNull BigDecimal openingBalance,
            @NonNull BigDecimal closingBalance) {

        boolean contains(@NonNull LocalDate date) {
            return !date.isBefore(startDate) && !date.isAfter(endDate);
        }
    }

    /** A {@code splitAt} point: {@code date} is the last day of a segment, {@code closingBalance} its keyed close. */
    record SplitPoint(@NonNull LocalDate date, @NonNull BigDecimal closingBalance) {}

    /** The import's counts (§3.3), recomputed on every parse and change. */
    record Counts(int rowCount, int accepted, int rejected, int possibleDuplicates, int skipped, int outOfWindow) {}

    /** One preview line: the row with its resulting sign and the running balance from the opening (§4.4). */
    record PreviewRow(
            int rowNumber,
            @Nullable LocalDate date,
            @Nullable String description,
            @Nullable BigDecimal signedAmount,
            @NonNull BankImportRowStatus rowStatus,
            @Nullable BigDecimal runningBalance) {}

    /** E1 of one segment: {@code opening + Σ committable rows} against the keyed closing balance. */
    record SegmentTotal(
            @NonNull Segment segment,
            @NonNull BigDecimal activityTotal,
            @NonNull BigDecimal expectedClosing,
            @NonNull BigDecimal difference,
            boolean ties,
            int transactionCount) {}

    /** The preview of §4.4: first rows with running balance, and E1 per segment. */
    record Preview(
            @NonNull List<PreviewRow> firstRows, @NonNull List<SegmentTotal> segments) {}

    // ---- segments ------------------------------------------------------------------------------

    /** The segments of a header window cut at the split points (sorted by date; none = one segment). */
    static @NonNull List<Segment> segments(
            @NonNull LocalDate start,
            @NonNull LocalDate end,
            @NonNull BigDecimal opening,
            @NonNull BigDecimal closing,
            @NonNull List<SplitPoint> splitAt) {
        List<SplitPoint> points = new ArrayList<>(splitAt);
        points.sort((a, b) -> a.date().compareTo(b.date()));
        List<Segment> segments = new ArrayList<>();
        LocalDate from = start;
        BigDecimal open = opening;
        for (SplitPoint point : points) {
            segments.add(new Segment(from, point.date(), open, point.closingBalance()));
            from = point.date().plusDays(1);
            open = point.closingBalance();
        }
        segments.add(new Segment(from, end, open, closing));
        return segments;
    }

    // ---- rows ----------------------------------------------------------------------------------

    /** The staging rows of a freshly parsed file: parse rejections {@code REJECTED}, the rest to be evaluated. */
    static @NonNull List<BankImportRow> fromParse(@NonNull UUID importId, @NonNull List<ParsedRow> parsed) {
        List<BankImportRow> rows = new ArrayList<>(parsed.size());
        for (ParsedRow p : parsed) {
            BankImportRow row = new BankImportRow();
            row.setImportId(importId);
            row.setRowNumber(p.rowNumber());
            row.setRawValues(new LinkedHashMap<>(p.rawValues()));
            row.setTransactionDate(p.date());
            row.setSignedAmount(p.signedAmount() == null ? null : TransactionNormalizer.scaleAmount(p.signedAmount()));
            row.setDescription(p.description());
            row.setReference(p.reference());
            row.setCheckNumber(p.checkNumber());
            row.setSourceTransactionId(p.sourceTransactionId());
            if (p.rejected()) {
                row.setRowStatus(BankImportRowStatus.REJECTED);
                row.setRejectionCode(p.rejection().name());
                row.setRejectionDetail(p.rejectionDetail());
            } else {
                row.setRowStatus(BankImportRowStatus.PARSED);
            }
            rows.add(row);
        }
        return rows;
    }

    /**
     * Recomputes every open row's state against the header window and R1 (§3.8): a row dated outside the
     * window is {@code OUT_OF_WINDOW} (a corrected one {@code REJECTED DATE_OUTSIDE_STATEMENT}); a row
     * whose fingerprint collides with a stored transaction or another row of the file is {@code
     * POSSIBLE_DUPLICATE} unless a human confirmed it distinct; the rest are {@code PARSED} or {@code
     * CORRECTED}. {@code SKIPPED}, {@code COMMITTED} and parse-rejected rows keep their state.
     *
     * @param stored R1 against the account: fingerprint → the earliest stored transaction it collides with
     */
    static void evaluate(
            @NonNull List<BankImportRow> rows,
            @NonNull UUID glAccountId,
            @NonNull LocalDate start,
            @NonNull LocalDate end,
            @NonNull Function<Collection<String>, Map<String, UUID>> stored) {
        List<BankImportRow> candidates = new ArrayList<>();
        for (BankImportRow row : rows) {
            if (!isOpen(row)) {
                continue;
            }
            row.setFingerprint(fingerprint(glAccountId, row));
            row.setDuplicateOfBankTransactionId(null);
            row.setDuplicateOfRowNumber(null);
            LocalDate date = row.getTransactionDate();
            if (date.isBefore(start) || date.isAfter(end)) {
                if (row.getCorrectedValues() != null) {
                    reject(
                            row,
                            RejectionCode.DATE_OUTSIDE_STATEMENT,
                            "Row " + row.getRowNumber() + ": corrected date " + date + " is outside " + start + ".."
                                    + end);
                } else {
                    row.setRowStatus(BankImportRowStatus.OUT_OF_WINDOW);
                    row.setRejectionCode(null);
                    row.setRejectionDetail(null);
                }
                continue;
            }
            candidates.add(row);
        }

        Map<String, List<BankImportRow>> byFingerprint = new HashMap<>();
        for (BankImportRow row : candidates) {
            byFingerprint
                    .computeIfAbsent(row.getFingerprint(), k -> new ArrayList<>())
                    .add(row);
        }
        Map<String, UUID> collisions = stored.apply(byFingerprint.keySet());
        for (BankImportRow row : candidates) {
            row.setRejectionCode(null);
            row.setRejectionDetail(null);
            List<BankImportRow> same = byFingerprint.get(row.getFingerprint());
            // Rows of one file collide unless every one of them carries a source id (then the bank itself
            // says they differ), exactly as the intake's R1 does.
            boolean inFile = same.size() > 1 && same.stream().anyMatch(r -> r.getSourceTransactionId() == null);
            UUID storedOriginal = collisions.get(row.getFingerprint());
            if (inFile) {
                same.stream()
                        .filter(other -> other != row)
                        .findFirst()
                        .ifPresent(other -> row.setDuplicateOfRowNumber(other.getRowNumber()));
            }
            row.setDuplicateOfBankTransactionId(storedOriginal);
            boolean collides = inFile || storedOriginal != null;
            if (collides && !DISTINCT.equals(row.getDuplicateDecision())) {
                row.setRowStatus(BankImportRowStatus.POSSIBLE_DUPLICATE);
            } else {
                row.setRowStatus(
                        row.getCorrectedValues() != null ? BankImportRowStatus.CORRECTED : BankImportRowStatus.PARSED);
            }
        }
    }

    /**
     * A row whose state {@link #evaluate} recomputes: every row with usable values that is not skipped
     * or committed. A rejected row stays rejected until corrected — except one rejected only for a
     * corrected date outside the window, which a widened header can bring back.
     */
    private static boolean isOpen(BankImportRow row) {
        return switch (row.getRowStatus()) {
            case SKIPPED, COMMITTED -> false;
            case REJECTED -> RejectionCode.DATE_OUTSIDE_STATEMENT.name().equals(row.getRejectionCode());
            default ->
                row.getTransactionDate() != null && row.getSignedAmount() != null && row.getDescription() != null;
        };
    }

    static void reject(@NonNull BankImportRow row, @NonNull RejectionCode code, @NonNull String detail) {
        row.setRowStatus(BankImportRowStatus.REJECTED);
        row.setRejectionCode(code.name());
        row.setRejectionDetail(detail.length() > 1000 ? detail.substring(0, 1000) : detail);
    }

    /** The §3.2 fingerprint of a row's effective values, exactly as the intake will compute it. */
    static @NonNull String fingerprint(@NonNull UUID glAccountId, @NonNull BankImportRow row) {
        return TransactionNormalizer.fingerprint(
                glAccountId,
                row.getTransactionDate(),
                row.getSignedAmount(),
                TransactionNormalizer.normalizeDescription(row.getDescription()),
                row.getReference(),
                row.getCheckNumber());
    }

    // ---- counts and preview --------------------------------------------------------------------

    static @NonNull Counts counts(@NonNull List<BankImportRow> rows) {
        int accepted = 0;
        int rejected = 0;
        int duplicates = 0;
        int skipped = 0;
        int outOfWindow = 0;
        for (BankImportRow row : rows) {
            switch (row.getRowStatus()) {
                case PARSED, CORRECTED, COMMITTED -> accepted++;
                case REJECTED -> rejected++;
                case POSSIBLE_DUPLICATE -> duplicates++;
                case SKIPPED -> skipped++;
                case OUT_OF_WINDOW -> outOfWindow++;
            }
        }
        return new Counts(rows.size(), accepted, rejected, duplicates, skipped, outOfWindow);
    }

    /** Whether a row's amount counts toward the activity total: it is committed or would be. */
    private static boolean counts(BankImportRow row) {
        return COMMITTABLE.contains(row.getRowStatus()) || row.getRowStatus() == BankImportRowStatus.COMMITTED;
    }

    static @NonNull Preview preview(
            @NonNull List<BankImportRow> rows, @NonNull List<Segment> segments, @NonNull BankAccountTerms terms) {
        List<PreviewRow> first = new ArrayList<>();
        BigDecimal running = segments.getFirst().openingBalance();
        for (BankImportRow row : rows) {
            if (first.size() == PREVIEW_ROWS) {
                break;
            }
            boolean counted = counts(row);
            if (counted) {
                running = running.add(row.getSignedAmount());
            }
            first.add(new PreviewRow(
                    row.getRowNumber(),
                    row.getTransactionDate(),
                    row.getDescription(),
                    row.getSignedAmount(),
                    row.getRowStatus(),
                    counted ? running : null));
        }
        return new Preview(first, totals(rows, segments, terms));
    }

    static @NonNull List<SegmentTotal> totals(
            @NonNull List<BankImportRow> rows, @NonNull List<Segment> segments, @NonNull BankAccountTerms terms) {
        List<SegmentTotal> totals = new ArrayList<>(segments.size());
        for (Segment segment : segments) {
            BigDecimal activity = BigDecimal.ZERO;
            int count = 0;
            for (BankImportRow row : rows) {
                if (counts(row) && segment.contains(row.getTransactionDate())) {
                    activity = activity.add(row.getSignedAmount());
                    count++;
                }
            }
            BigDecimal expected = segment.openingBalance().add(activity);
            BigDecimal difference = expected.subtract(segment.closingBalance());
            totals.add(new SegmentTotal(
                    segment,
                    activity,
                    expected,
                    difference,
                    difference.abs().compareTo(terms.tolerance()) <= 0,
                    count));
        }
        return totals;
    }

    // ---- commit preconditions ------------------------------------------------------------------

    /**
     * The §4.4 commit preconditions as {@code fieldErrors}: every {@code REJECTED} and un-skipped {@code
     * OUT_OF_WINDOW} row under {@code rows[<rowNumber>]}, and E1 under {@code activityTotal} (or {@code
     * segments[<i>].activityTotal} for a split file). Empty when the import may be committed.
     */
    static @NonNull Map<String, String> blockers(
            @NonNull List<BankImportRow> rows, @NonNull List<Segment> segments, @NonNull BankAccountTerms terms) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (BankImportRow row : rows) {
            String key = "rows[" + row.getRowNumber() + "]";
            if (row.getRowStatus() == BankImportRowStatus.REJECTED) {
                errors.put(key, "REJECTED " + row.getRejectionCode() + ": " + row.getRejectionDetail());
            } else if (row.getRowStatus() == BankImportRowStatus.OUT_OF_WINDOW) {
                errors.put(
                        key,
                        "OUT_OF_WINDOW: dated " + row.getTransactionDate()
                                + " outside the statement window; skip the row or widen the header");
            }
        }
        if (rows.stream().noneMatch(r -> COMMITTABLE.contains(r.getRowStatus()))) {
            errors.put("rows", "no rows to commit");
            return errors;
        }
        List<SegmentTotal> totals = totals(rows, segments, terms);
        boolean split = totals.size() > 1;
        for (int i = 0; i < totals.size(); i++) {
            SegmentTotal total = totals.get(i);
            String prefix = split ? "segments[" + i + "]." : "";
            if (split && total.transactionCount() == 0) {
                errors.put(
                        prefix + "rows",
                        "no transactions dated " + total.segment().startDate() + ".."
                                + total.segment().endDate());
            }
            if (!total.ties()) {
                errors.put(
                        prefix + "activityTotal",
                        "opening + activity = " + terms.display(total.expectedClosing()) + ", closing = "
                                + terms.display(total.segment().closingBalance()));
            }
        }
        return errors;
    }
}
