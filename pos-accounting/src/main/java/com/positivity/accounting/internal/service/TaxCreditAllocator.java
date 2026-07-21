package com.positivity.accounting.internal.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;

/**
 * Deterministic pro-rata allocator for netting a credit memo's reversed tax
 * across the jurisdictions of its original invoice (story T8, issue #966).
 *
 * <p><b>Why.</b> {@code ext_invoice_tax} is invoice-side only, so a POSTED credit
 * memo's scalar {@code taxAmountReversed} carries no per-jurisdiction attribution.
 * The reconciliation-grade report (R-T4 decision A) approximates that attribution
 * by allocating the reversed tax across the original invoice's jurisdictions
 * <em>pro-rata to each jurisdiction's collected tax</em> ({@code tax_amount}). True
 * per-jurisdiction credit attribution (carrying the breakdown on the credit itself)
 * is a Phase-2 concern.
 *
 * <p><b>Exact-sum guarantee.</b> Each jurisdiction's share is rounded HALF_UP at
 * currency scale (2). The rounding residual ({@code amount - Σ rounded}) is then
 * assigned to a single jurisdiction under a deterministic rule —
 * <em>largest-weight-gets-the-remainder</em>, ties broken by the natural order of
 * the key — so the allocated parts always sum <em>exactly</em> to {@code amount}.
 * This is what lets the report's netted credits reconcile to the 2200 GL debits to
 * the cent.
 *
 * <p>The result is independent of the input map's iteration order.
 */
final class TaxCreditAllocator {

    /** Currency scale (dollars and cents) for allocation rounding (R-T4 decision). */
    static final int CURRENCY_SCALE = 2;

    private TaxCreditAllocator() {
        // utility
    }

    /**
     * Allocate {@code amount} across {@code weights} pro-rata to each weight, with an
     * exact-sum residual correction.
     *
     * @param amount  the total to distribute (a credit memo's {@code taxAmountReversed});
     *                a non-negative currency amount
     * @param weights per-jurisdiction non-negative weights (each jurisdiction's collected
     *                {@code tax_amount})
     * @param <K>     the (comparable) jurisdiction key type
     * @return a map whose values sum exactly to {@code amount} rounded to currency scale;
     *         empty when {@code weights} is empty (the caller then treats the amount as
     *         unattributable)
     */
    static <K extends Comparable<K>> Map<K, BigDecimal> allocate(BigDecimal amount, Map<K, BigDecimal> weights) {
        Map<K, BigDecimal> result = new LinkedHashMap<>();
        if (weights.isEmpty()) {
            return result; // nothing to attribute to — caller decides how to surface this
        }

        BigDecimal target = amount.setScale(CURRENCY_SCALE, RoundingMode.HALF_UP);
        BigDecimal totalWeight = weights.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);

        // Degenerate weights (all zero): the whole amount cannot be split by share, so
        // assign it entirely to the deterministic winner (smallest key) and zero the rest.
        if (totalWeight.signum() == 0) {
            K winner = weights.keySet().stream().min(K::compareTo).orElseThrow();
            for (K key : weights.keySet()) {
                result.put(key, winner.equals(key) ? target : zero());
            }
            return result;
        }

        BigDecimal running = BigDecimal.ZERO;
        for (Entry<K, BigDecimal> entry : weights.entrySet()) {
            BigDecimal share =
                    target.multiply(entry.getValue()).divide(totalWeight, CURRENCY_SCALE, RoundingMode.HALF_UP);
            result.put(entry.getKey(), share);
            running = running.add(share);
        }

        BigDecimal residual = target.subtract(running);
        if (residual.signum() != 0) {
            result.merge(largestWeightKey(weights), residual, BigDecimal::add);
        }
        return result;
    }

    /**
     * Key of the largest weight; ties broken by the smallest (natural-order) key so the
     * choice is deterministic and reproducible across runs.
     */
    private static <K extends Comparable<K>> K largestWeightKey(Map<K, BigDecimal> weights) {
        K winner = null;
        BigDecimal best = null;
        for (Entry<K, BigDecimal> entry : weights.entrySet()) {
            BigDecimal weight = entry.getValue();
            if (best == null
                    || weight.compareTo(best) > 0
                    || (weight.compareTo(best) == 0 && entry.getKey().compareTo(winner) < 0)) {
                best = weight;
                winner = entry.getKey();
            }
        }
        return winner;
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(CURRENCY_SCALE);
    }
}
