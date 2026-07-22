package com.positivity.accounting.internal.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

/**
 * Deterministic pro-rata allocator for netting a credit memo's reversed tax
 * across the jurisdictions of its original invoice (story T8, issue #966).
 *
 * <p><b>Why.</b> Historically {@code ext_invoice_tax} was invoice-side only, so a POSTED
 * credit memo's scalar {@code taxAmountReversed} carried no per-jurisdiction attribution
 * and the reconciliation-grade report (R-T4 decision A) had to approximate it here. Since
 * issue #996 the attribution is frozen on the credit itself at creation, so this allocator
 * serves two narrower roles: the initial split for a partial credit, and the fallback for
 * credits issued before {@code credit_memo_tax} existed.
 *
 * <p><b>Exact-sum guarantee, without negative shares.</b> This uses the
 * <em>largest-remainder</em> (Hare quota) method: each share is first rounded {@code DOWN}
 * at currency scale (2), then the leftover — always non-negative, and strictly less than
 * one cent per jurisdiction — is handed out a cent at a time in descending order of the
 * discarded fractional part, ties broken by the natural order of the key. The parts
 * therefore sum <em>exactly</em> to {@code amount} and every part is {@code >= 0}. That
 * exactness is what lets the report's netted credits reconcile to the 2200 GL debits to
 * the cent.
 *
 * <p><b>Why not round-then-correct.</b> The earlier implementation rounded HALF_UP and
 * dumped the residual {@code amount - Σ rounded} onto the largest-weight jurisdiction.
 * When enough jurisdictions each round <em>up</em>, that residual is negative and can
 * exceed the anchor's own share — six equal-weight jurisdictions sharing {@code 0.03} each
 * round {@code 0.005 → 0.01}, summing to {@code 0.06}, leaving the anchor
 * {@code 0.01 + (0.03 - 0.06) = -0.02}. That was merely a skewed report row while this ran
 * only at read time; once issue #996 put the allocator on the write path behind
 * {@code chk_credit_memo_tax_reversed_non_negative}, a negative share aborts the entire
 * credit-memo transaction. Largest-remainder removes the failure mode structurally instead
 * of clamping after the fact.
 *
 * <p>The result is independent of the input map's iteration order.
 */
final class TaxCreditAllocator {

    /** Currency scale (dollars and cents) for allocation rounding (R-T4 decision). */
    static final int CURRENCY_SCALE = 2;

    /** One cent at {@link #CURRENCY_SCALE} — the unit the leftover is distributed in. */
    private static final BigDecimal ONE_CENT = BigDecimal.ONE.movePointLeft(CURRENCY_SCALE);

    private TaxCreditAllocator() {
        // utility
    }

    /**
     * Allocate {@code amount} across {@code weights} pro-rata to each weight, with an
     * exact-sum guarantee and no negative parts.
     *
     * @param amount  the total to distribute (a credit memo's {@code taxAmountReversed});
     *                a non-negative currency amount
     * @param weights per-jurisdiction non-negative weights (each jurisdiction's collected
     *                {@code tax_amount})
     * @param <K>     the (comparable) jurisdiction key type
     * @return a map whose values are all {@code >= 0} and sum exactly to {@code amount}
     *         rounded to currency scale; empty when {@code weights} is empty <em>or</em>
     *         when the total weight is zero (the caller then treats the amount as
     *         unattributable)
     */
    static <K extends Comparable<K>> Map<K, BigDecimal> allocate(BigDecimal amount, Map<K, BigDecimal> weights) {
        Map<K, BigDecimal> result = new LinkedHashMap<>();
        if (weights.isEmpty()) {
            return result; // nothing to attribute to — caller decides how to surface this
        }

        BigDecimal target = amount.setScale(CURRENCY_SCALE, RoundingMode.HALF_UP);
        BigDecimal totalWeight = weights.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);

        // Degenerate weights (all zero): every jurisdiction collected zero tax, so there is no
        // collected-tax share to allocate against. Treat this identically to empty weights —
        // do NOT attribute the reversal to any jurisdiction (assigning it to an arbitrary key
        // would silently net a credit against a jurisdiction that collected no tax and hide the
        // mismatch). Return empty so the caller leaves the reversal unattributed (GL drift).
        if (totalWeight.signum() == 0) {
            return result;
        }

        // Pass 1 — floor every share at currency scale, and remember the discarded fraction.
        List<Remainder<K>> remainders = new ArrayList<>(weights.size());
        BigDecimal running = BigDecimal.ZERO;
        for (Entry<K, BigDecimal> entry : weights.entrySet()) {
            BigDecimal exact =
                    target.multiply(entry.getValue()).divide(totalWeight, CURRENCY_SCALE + 6, RoundingMode.HALF_UP);
            BigDecimal floored = exact.setScale(CURRENCY_SCALE, RoundingMode.DOWN);
            result.put(entry.getKey(), floored);
            remainders.add(new Remainder<>(entry.getKey(), exact.subtract(floored)));
            running = running.add(floored);
        }

        // Pass 2 — hand the leftover out a cent at a time, largest discarded fraction first.
        // leftover >= 0 by construction (every share was floored), so no part can go negative.
        int centsToDistribute =
                target.subtract(running).movePointRight(CURRENCY_SCALE).intValueExact();
        remainders.sort(
                Comparator.comparing((Remainder<K> r) -> r.fraction).reversed().thenComparing(r -> r.key));
        for (int i = 0; i < centsToDistribute; i++) {
            K key = remainders.get(i % remainders.size()).key;
            result.merge(key, ONE_CENT, BigDecimal::add);
        }
        return result;
    }

    /** A jurisdiction and the sub-cent fraction discarded when its share was floored. */
    private record Remainder<K extends Comparable<K>>(K key, BigDecimal fraction) {}
}
