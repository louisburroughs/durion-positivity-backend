package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.TaxLiabilityReconciliation;
import com.positivity.accounting.internal.dto.TaxLiabilityReport;
import com.positivity.accounting.internal.dto.TaxLiabilityRow;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Canonical serialization + SHA-256 hashing of a {@link TaxLiabilityReport} for the auditable
 * freeze (issue #998, Phase-2 scope item 2).
 *
 * <p>The canonical form covers everything deterministic about the report — date range, ordered
 * jurisdiction rows, totals, and the GL reconciliation block — and deliberately excludes
 * {@code generatedAt} (wall-clock noise). {@link BigDecimal}s are normalized via
 * {@code stripTrailingZeros().toPlainString()} so numerically equal amounts hash identically
 * regardless of accumulated scale; row order is taken as produced by the report, which is already
 * deterministic (state → county → city → special, then code). A re-derived report over the same
 * underlying data therefore yields the same hash, which is what the snapshot verify endpoint
 * checks.
 *
 * <p><strong>Injectivity:</strong> free-text fields (jurisdiction type/code/name, exemption reason
 * codes, account code) arrive verbatim from upstream events and may contain the separator
 * characters, so every such field is backslash-escaped ({@code \} {@code |} {@code ,} and newline)
 * before concatenation. Without this, two semantically different reports (e.g. reasons
 * {@code ["A,B"]} vs {@code ["A","B"]}) would canonicalize identically and verify would return a
 * false {@code consistent=true}. The same escaping backs the {@link #joinReasons}/
 * {@link #splitReasons} pair used to persist snapshot-row reason lists losslessly.
 */
public final class TaxLiabilityCanonicalizer {

    private static final String FIELD_SEP = "|";
    private static final String ROW_SEP = "\n";
    private static final char ESCAPE = '\\';

    private TaxLiabilityCanonicalizer() {
        // Utility class
    }

    /** SHA-256 (lowercase hex) of {@link #canonicalize(TaxLiabilityReport)}. */
    @NonNull
    public static String contentHash(@NonNull TaxLiabilityReport report) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonicalize(report).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Deterministic, injective canonical string of the report (excluding {@code generatedAt}). */
    @NonNull
    public static String canonicalize(@NonNull TaxLiabilityReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("v1").append(FIELD_SEP);
        sb.append(report.getStartDate()).append(FIELD_SEP);
        sb.append(report.getEndDate()).append(ROW_SEP);

        for (TaxLiabilityRow row : report.getRows()) {
            sb.append(escape(row.getJurisdictionType()))
                    .append(FIELD_SEP)
                    .append(escape(row.getJurisdictionCode()))
                    .append(FIELD_SEP)
                    .append(escape(nullSafe(row.getJurisdictionName())))
                    .append(FIELD_SEP)
                    .append(decimal(row.getTaxableBase()))
                    .append(FIELD_SEP)
                    .append(decimal(row.getExemptBase()))
                    .append(FIELD_SEP)
                    .append(joinReasons(row.getExemptionReasons()))
                    .append(FIELD_SEP)
                    .append(decimal(row.getTaxCollectedGross()))
                    .append(FIELD_SEP)
                    .append(decimal(row.getCreditsNetted()))
                    .append(FIELD_SEP)
                    .append(decimal(row.getNetTax()))
                    .append(ROW_SEP);
        }

        sb.append("totals")
                .append(FIELD_SEP)
                .append(decimal(report.getTotalTaxableBase()))
                .append(FIELD_SEP)
                .append(decimal(report.getTotalExemptBase()))
                .append(FIELD_SEP)
                .append(decimal(report.getTotalTaxCollectedGross()))
                .append(FIELD_SEP)
                .append(decimal(report.getTotalCreditsNetted()))
                .append(FIELD_SEP)
                .append(decimal(report.getTotalNetTax()))
                .append(ROW_SEP);

        TaxLiabilityReconciliation rec = report.getReconciliation();
        sb.append("reconciliation")
                .append(FIELD_SEP)
                .append(escape(rec.getTaxPayableAccountCode()))
                .append(FIELD_SEP)
                .append(decimal(rec.getGlNetActivity()))
                .append(FIELD_SEP)
                .append(decimal(rec.getReportNetTax()))
                .append(FIELD_SEP)
                .append(decimal(rec.getUnattributedCredits()))
                .append(FIELD_SEP)
                .append(decimal(rec.getDrift()))
                .append(FIELD_SEP)
                .append(rec.getReconciled());
        return sb.toString();
    }

    /**
     * Comma-join reason codes with each element escaped, so codes containing commas (or any
     * separator character) survive losslessly. Inverse of {@link #splitReasons}. The report
     * already emits reasons distinct + ascending, so the join is deterministic.
     */
    @NonNull
    public static String joinReasons(@Nullable List<String> exemptionReasons) {
        if (exemptionReasons == null || exemptionReasons.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < exemptionReasons.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(escape(exemptionReasons.get(i)));
        }
        return sb.toString();
    }

    /** Inverse of {@link #joinReasons}: split on unescaped commas and unescape each element. */
    @NonNull
    public static List<String> splitReasons(@NonNull String joined) {
        if (joined.isEmpty()) {
            return List.of();
        }
        List<String> reasons = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < joined.length(); i++) {
            char c = joined.charAt(i);
            if (c == ESCAPE && i + 1 < joined.length()) {
                char next = joined.charAt(++i);
                current.append(next == 'n' ? '\n' : next);
            } else if (c == ',') {
                reasons.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        reasons.add(current.toString());
        return List.copyOf(reasons);
    }

    /** Backslash-escape the canonical separator characters so field concatenation is injective. */
    @NonNull
    private static String escape(@NonNull String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case ESCAPE -> sb.append(ESCAPE).append(ESCAPE);
                case '|' -> sb.append(ESCAPE).append('|');
                case ',' -> sb.append(ESCAPE).append(',');
                case '\n' -> sb.append(ESCAPE).append('n');
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    @NonNull
    private static String decimal(@Nullable BigDecimal value) {
        if (value == null) {
            return "";
        }
        BigDecimal stripped = value.stripTrailingZeros();
        // stripTrailingZeros() leaves 0E-2 style representations for zero; normalize to "0".
        return stripped.compareTo(BigDecimal.ZERO) == 0 ? "0" : stripped.toPlainString();
    }

    private static String nullSafe(@Nullable String value) {
        return value == null ? "" : value;
    }
}
