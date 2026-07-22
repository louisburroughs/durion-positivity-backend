package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TaxCreditAllocator} (story T8, issue #966): the exact-sum
 * pro-rata split of a credit memo's reversed tax across jurisdictions, and its
 * deterministic residual rule (largest-weight-gets-the-remainder, ties by key).
 */
class TaxCreditAllocatorTest {

    private static Map<String, BigDecimal> weights(Object... kv) {
        Map<String, BigDecimal> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], new BigDecimal(kv[i + 1].toString()));
        }
        return map;
    }

    private static BigDecimal sum(Map<String, BigDecimal> m) {
        return m.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    @DisplayName("Splits pro-rata to weights with no residual when shares are exact")
    void exactShares() {
        Map<String, BigDecimal> out =
                TaxCreditAllocator.allocate(new BigDecimal("25.00"), weights("WA", 65, "KING", 35, "SEATTLE", 25));

        assertThat(out.get("WA")).isEqualByComparingTo("13.00");
        assertThat(out.get("KING")).isEqualByComparingTo("7.00");
        assertThat(out.get("SEATTLE")).isEqualByComparingTo("5.00");
        assertThat(sum(out)).isEqualByComparingTo("25.00");
    }

    @Test
    @DisplayName("Equal weights: positive residual goes to the smallest key (deterministic tie-break)")
    void equalWeightsResidualToSmallestKey() {
        Map<String, BigDecimal> out =
                TaxCreditAllocator.allocate(new BigDecimal("1.00"), weights("A", 1, "B", 1, "C", 1));

        // 0.33 each -> 0.99, residual 0.01 to smallest key A.
        assertThat(out.get("A")).isEqualByComparingTo("0.34");
        assertThat(out.get("B")).isEqualByComparingTo("0.33");
        assertThat(out.get("C")).isEqualByComparingTo("0.33");
        assertThat(sum(out)).isEqualByComparingTo("1.00");
    }

    @Test
    @DisplayName("Distinct largest weight absorbs a negative residual")
    void distinctLargestWeightAbsorbsResidual() {
        Map<String, BigDecimal> out =
                TaxCreditAllocator.allocate(new BigDecimal("1.00"), weights("A", 1, "B", 1, "D", 4));

        // A=0.17, B=0.17, D=0.67 -> 1.01, residual -0.01 to largest weight D.
        assertThat(out.get("A")).isEqualByComparingTo("0.17");
        assertThat(out.get("B")).isEqualByComparingTo("0.17");
        assertThat(out.get("D")).isEqualByComparingTo("0.66");
        assertThat(sum(out)).isEqualByComparingTo("1.00");
    }

    @Test
    @DisplayName("Allocation is independent of input map iteration order")
    void orderIndependent() {
        Map<String, BigDecimal> forward =
                TaxCreditAllocator.allocate(new BigDecimal("1.00"), weights("A", 1, "B", 1, "D", 4));
        Map<String, BigDecimal> reversed =
                TaxCreditAllocator.allocate(new BigDecimal("1.00"), weights("D", 4, "B", 1, "A", 1));

        assertThat(reversed.get("A")).isEqualByComparingTo(forward.get("A"));
        assertThat(reversed.get("B")).isEqualByComparingTo(forward.get("B"));
        assertThat(reversed.get("D")).isEqualByComparingTo(forward.get("D"));
    }

    @Test
    @DisplayName("Empty weights yield an empty allocation (caller treats amount as unattributable)")
    void emptyWeights() {
        assertThat(TaxCreditAllocator.allocate(new BigDecimal("10.00"), weights()))
                .isEmpty();
    }

    @Test
    @DisplayName("All-zero weights yield an empty allocation (reversal left unattributed, like empty weights)")
    void zeroWeights() {
        // Every jurisdiction collected zero tax: there is no collected-tax share to net against,
        // so the reversal is left unattributed (caller logs a WARN and surfaces it as GL drift).
        assertThat(TaxCreditAllocator.allocate(new BigDecimal("25.00"), weights("Z", 0, "A", 0)))
                .isEmpty();
    }

    @Test
    @DisplayName("Never emits a negative share when many jurisdictions each round up (issue #996 regression)")
    void manyRoundingUps_produceNoNegativeShare() {
        // Six equal weights sharing 0.03: each exact share is 0.005. Rounding HALF_UP gave every
        // jurisdiction 0.01 (sum 0.06) and dumped the -0.03 residual on the anchor, leaving -0.02.
        // Harmless at report time, but once #996 persisted this the CHECK
        // chk_credit_memo_tax_reversed_non_negative aborted the whole credit-memo transaction.
        Map<String, BigDecimal> weights =
                weights("A", "1.00", "B", "1.00", "C", "1.00", "D", "1.00", "E", "1.00", "F", "1.00");

        Map<String, BigDecimal> allocated = TaxCreditAllocator.allocate(new BigDecimal("0.03"), weights);

        assertThat(allocated.values()).allSatisfy(v -> assertThat(v.signum()).isGreaterThanOrEqualTo(0));
        assertThat(allocated.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("0.03");
        // Largest-remainder: exactly three jurisdictions get the cent.
        assertThat(allocated.values().stream().filter(v -> v.signum() > 0).count())
                .isEqualTo(3);
    }

    @Test
    @DisplayName("Exact-sum and non-negativity hold across many awkward amount/jurisdiction combinations")
    void exactSumAndNonNegativeAcrossCombinations() {
        for (int jurisdictions = 1; jurisdictions <= 12; jurisdictions++) {
            Map<String, BigDecimal> weights = new LinkedHashMap<>();
            for (int j = 0; j < jurisdictions; j++) {
                weights.put("J" + j, new BigDecimal(j + 1).movePointLeft(1).add(new BigDecimal("1.00")));
            }
            for (int cents = 0; cents <= 40; cents++) {
                BigDecimal amount = BigDecimal.valueOf(cents).movePointLeft(2);
                Map<String, BigDecimal> allocated = TaxCreditAllocator.allocate(amount, weights);
                assertThat(allocated.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add))
                        .as("sum for %s across %d jurisdictions", amount, jurisdictions)
                        .isEqualByComparingTo(amount);
                assertThat(allocated.values())
                        .as("no negative share for %s across %d jurisdictions", amount, jurisdictions)
                        .allSatisfy(v -> assertThat(v.signum()).isGreaterThanOrEqualTo(0));
            }
        }
    }
}
