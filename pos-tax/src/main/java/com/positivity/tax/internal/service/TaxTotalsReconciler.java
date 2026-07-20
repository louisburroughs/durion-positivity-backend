package com.positivity.tax.internal.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;

/**
 * Single-stage rounding reconciler over a per-line x per-jurisdiction RAW
 * (unrounded, full-precision) tax matrix.
 * <p>
 * Enforces the money invariant
 * {@code Sum(lineAmounts) == totalTax == Sum(jurisdictionAmounts)} and, within
 * each line, {@code Sum(cellAmounts[line]) == lineAmounts[line]}.
 * <p>
 * All money math uses {@link BigDecimal}, {@link RoundingMode#HALF_UP} and
 * currency scale 2 (see ADR-0044 money policy). Residual cents left over after
 * independent per-item rounding are distributed one cent at a time using a
 * deterministic largest-raw-first rule (ties broken by larger fractional
 * remainder, then by ascending stable index), so identical input yields
 * byte-identical output on every run.
 * <p>
 * This is a pure helper: it holds no Spring state and no mutable fields. The
 * {@link #distributeResidual(List, BigDecimal)} and
 * {@link #reconcileAmountsToTarget(List, BigDecimal)} entry points are intended
 * for reuse by later slices (T4/T6) that validate and correct an external
 * provider's response against the same invariants.
 */
@Slf4j
class TaxTotalsReconciler {

    /** Currency scale for all monetary amounts. */
    private static final int MONEY_SCALE = 2;

    /** A single cent expressed at {@link #MONEY_SCALE}. */
    private static final BigDecimal ONE_CENT = BigDecimal.valueOf(1, MONEY_SCALE);

    /**
     * Result of reconciling a raw tax matrix.
     *
     * @param totalTax            the single source-of-truth grand total (rounded)
     * @param jurisdictionAmounts rounded per-jurisdiction column totals; sum == totalTax
     * @param lineAmounts         rounded per-line row totals; sum == totalTax
     * @param cellAmounts         rounded per-line x per-jurisdiction amounts; each row sums to
     *                            the matching {@code lineAmounts} entry
     */
    record ReconciledTax(
            @NonNull BigDecimal totalTax,
            @NonNull List<BigDecimal> jurisdictionAmounts,
            @NonNull List<BigDecimal> lineAmounts,
            @NonNull List<List<BigDecimal>> cellAmounts) {}

    /**
     * Reconcile a raw (line x jurisdiction) tax matrix into rounded, internally
     * consistent totals.
     *
     * @param lineTaxableAmounts full-precision taxable base per non-exempt line (row order preserved)
     * @param jurisdictionRates  decimal-fraction rate per jurisdiction (column order preserved)
     * @return the reconciled totals satisfying all money invariants
     */
    @NonNull
    ReconciledTax reconcile(
            @NonNull List<BigDecimal> lineTaxableAmounts, @NonNull List<BigDecimal> jurisdictionRates) {
        int rowCount = lineTaxableAmounts.size();
        int colCount = jurisdictionRates.size();

        // Raw cells: rawCell[line][jur] = lineTaxable * rate (full precision, no rounding).
        BigDecimal[][] rawCells = new BigDecimal[rowCount][colCount];
        BigDecimal rawTotal = BigDecimal.ZERO;
        for (int i = 0; i < rowCount; i++) {
            for (int j = 0; j < colCount; j++) {
                BigDecimal cell = lineTaxableAmounts.get(i).multiply(jurisdictionRates.get(j));
                rawCells[i][j] = cell;
                rawTotal = rawTotal.add(cell);
            }
        }

        // Single source of truth for the grand total.
        BigDecimal totalTax = round(rawTotal);

        // Jurisdiction (column) totals reconciled to totalTax.
        List<BigDecimal> jurisdictionRaw = new ArrayList<>(colCount);
        for (int j = 0; j < colCount; j++) {
            BigDecimal columnRaw = BigDecimal.ZERO;
            for (int i = 0; i < rowCount; i++) {
                columnRaw = columnRaw.add(rawCells[i][j]);
            }
            jurisdictionRaw.add(columnRaw);
        }
        List<BigDecimal> jurisdictionAmounts = distributeResidual(jurisdictionRaw, totalTax);

        // Line (row) totals reconciled to totalTax.
        List<BigDecimal> lineRaw = new ArrayList<>(rowCount);
        for (int i = 0; i < rowCount; i++) {
            BigDecimal rowRaw = BigDecimal.ZERO;
            for (int j = 0; j < colCount; j++) {
                rowRaw = rowRaw.add(rawCells[i][j]);
            }
            lineRaw.add(rowRaw);
        }
        List<BigDecimal> lineAmounts = distributeResidual(lineRaw, totalTax);

        // Per-line cell amounts reconciled to that line's rounded total.
        List<List<BigDecimal>> cellAmounts = new ArrayList<>(rowCount);
        for (int i = 0; i < rowCount; i++) {
            List<BigDecimal> rowCellsRaw = new ArrayList<>(colCount);
            for (int j = 0; j < colCount; j++) {
                rowCellsRaw.add(rawCells[i][j]);
            }
            cellAmounts.add(distributeResidual(rowCellsRaw, lineAmounts.get(i)));
        }

        return new ReconciledTax(totalTax, jurisdictionAmounts, lineAmounts, cellAmounts);
    }

    /**
     * Round each raw value to currency scale then distribute the residual cents
     * (relative to {@code target}) one cent at a time so the returned amounts sum
     * exactly to {@code target}.
     * <p>
     * Distribution order (deterministic): largest raw value first; ties broken by
     * larger fractional remainder (the sub-cent part discarded by rounding), then
     * by ascending original index. A positive residual adds cents; a negative
     * residual subtracts cents. This is the reusable core of the reconciler.
     *
     * @param rawValues the per-item raw (full-precision) values
     * @param target    the exact scale-2 total the rounded amounts must sum to
     * @return rounded amounts (scale 2) summing exactly to {@code target}
     */
    @NonNull
    List<BigDecimal> distributeResidual(@NonNull List<BigDecimal> rawValues, @NonNull BigDecimal target) {
        int n = rawValues.size();
        if (n == 0) {
            return new ArrayList<>();
        }

        List<BigDecimal> rounded = new ArrayList<>(n);
        BigDecimal roundedSum = BigDecimal.ZERO;
        for (BigDecimal raw : rawValues) {
            BigDecimal r = round(raw);
            rounded.add(r);
            roundedSum = roundedSum.add(r);
        }

        // Residual in whole cents (exact: target and roundedSum are both scale 2).
        int residualCents = target.subtract(roundedSum)
                .movePointRight(MONEY_SCALE)
                .setScale(0, RoundingMode.HALF_UP)
                .intValueExact();
        if (residualCents == 0) {
            return rounded;
        }

        List<Integer> order = distributionOrder(rawValues);
        BigDecimal step = residualCents > 0 ? ONE_CENT : ONE_CENT.negate();
        int steps = Math.abs(residualCents);
        for (int k = 0; k < steps; k++) {
            int idx = order.get(k % n);
            rounded.set(idx, rounded.get(idx).add(step));
        }
        return rounded;
    }

    /**
     * Validate and, if drifted, correct an already-computed set of amounts so it
     * sums exactly to {@code target}, using the same deterministic delta rule.
     * <p>
     * Intended for later slices (T4/T6) that reconcile an external provider's
     * response. Any drift is logged and corrected a cent at a time.
     *
     * @param amounts the amounts to validate (e.g. a provider's per-jurisdiction figures)
     * @param target  the authoritative scale-2 total they must sum to
     * @return amounts (scale 2) summing exactly to {@code target}
     */
    @NonNull
    List<BigDecimal> reconcileAmountsToTarget(@NonNull List<BigDecimal> amounts, @NonNull BigDecimal target) {
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal amount : amounts) {
            sum = sum.add(round(amount));
        }
        BigDecimal drift = target.subtract(sum);
        if (drift.signum() != 0) {
            log.warn("Correcting tax reconciliation drift of {} against target {}", drift, target);
        }
        return distributeResidual(amounts, target);
    }

    /**
     * Whether {@code amounts} already sum exactly to {@code target} at currency scale.
     *
     * @param amounts the amounts to check
     * @param target  the expected total
     * @return {@code true} when the rounded sum equals {@code target}
     */
    boolean sumsTo(@NonNull List<BigDecimal> amounts, @NonNull BigDecimal target) {
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal amount : amounts) {
            sum = sum.add(round(amount));
        }
        return sum.compareTo(target) == 0;
    }

    /**
     * Deterministic index order for residual distribution: largest raw value
     * first, ties broken by larger fractional remainder, then ascending index.
     */
    @NonNull
    private List<Integer> distributionOrder(@NonNull List<BigDecimal> rawValues) {
        List<Integer> order = new ArrayList<>(rawValues.size());
        for (int i = 0; i < rawValues.size(); i++) {
            order.add(i);
        }
        order.sort(Comparator.<Integer, BigDecimal>comparing(rawValues::get, Comparator.reverseOrder())
                .thenComparing(i -> fractionalRemainder(rawValues.get(i)), Comparator.reverseOrder())
                .thenComparingInt(i -> i));
        return order;
    }

    /**
     * Sub-cent fractional remainder of a raw value in {@code [0, 1)}: the part of
     * the amount below currency resolution that rounding discards.
     */
    @NonNull
    private BigDecimal fractionalRemainder(@NonNull BigDecimal rawValue) {
        return rawValue.abs().movePointRight(MONEY_SCALE).remainder(BigDecimal.ONE);
    }

    /**
     * Round a value to currency scale (scale 2, HALF_UP).
     */
    @NonNull
    private BigDecimal round(@NonNull BigDecimal value) {
        return value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
