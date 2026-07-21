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
    @DisplayName("All-zero weights assign the full amount to the smallest key")
    void zeroWeights() {
        Map<String, BigDecimal> out = TaxCreditAllocator.allocate(new BigDecimal("10.00"), weights("Z", 0, "A", 0));

        assertThat(out.get("A")).isEqualByComparingTo("10.00");
        assertThat(out.get("Z")).isEqualByComparingTo("0.00");
        assertThat(sum(out)).isEqualByComparingTo("10.00");
    }
}
