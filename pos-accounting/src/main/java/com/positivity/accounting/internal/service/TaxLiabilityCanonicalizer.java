package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.TaxLiabilityReconciliation;
import com.positivity.accounting.internal.dto.TaxLiabilityReport;
import com.positivity.accounting.internal.dto.TaxLiabilityRow;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
 */
public final class TaxLiabilityCanonicalizer {

    private static final String FIELD_SEP = "|";
    private static final String ROW_SEP = "\n";

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

    /** Deterministic canonical string of the report (excluding {@code generatedAt}). */
    @NonNull
    public static String canonicalize(@NonNull TaxLiabilityReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("v1").append(FIELD_SEP);
        sb.append(report.getStartDate()).append(FIELD_SEP);
        sb.append(report.getEndDate()).append(ROW_SEP);

        for (TaxLiabilityRow row : report.getRows()) {
            sb.append(row.getJurisdictionType())
                    .append(FIELD_SEP)
                    .append(row.getJurisdictionCode())
                    .append(FIELD_SEP)
                    .append(nullSafe(row.getJurisdictionName()))
                    .append(FIELD_SEP)
                    .append(decimal(row.getTaxableBase()))
                    .append(FIELD_SEP)
                    .append(decimal(row.getExemptBase()))
                    .append(FIELD_SEP)
                    .append(reasons(row.getExemptionReasons()))
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
                .append(rec.getTaxPayableAccountCode())
                .append(FIELD_SEP)
                .append(decimal(rec.getGlNetActivity()))
                .append(FIELD_SEP)
                .append(decimal(rec.getReportNetTax()))
                .append(FIELD_SEP)
                .append(decimal(rec.getDrift()))
                .append(FIELD_SEP)
                .append(rec.getReconciled());
        return sb.toString();
    }

    /** Comma-joined reason codes; the report already emits them distinct + ascending. */
    @NonNull
    public static String reasons(@Nullable List<String> exemptionReasons) {
        return exemptionReasons == null ? "" : String.join(",", exemptionReasons);
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
