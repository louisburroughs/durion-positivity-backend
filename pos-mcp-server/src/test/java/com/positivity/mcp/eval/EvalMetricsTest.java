package com.positivity.mcp.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies the Gate 0 metric math on synthetic data (no backend required). */
class EvalMetricsTest {

    @Test
    @DisplayName("hit@k respects the cutoff")
    void hitAtK() {
        List<String> ranked = List.of("a", "b", "c", "d", "e", "f");
        assertThat(EvalMetrics.hitAtK(ranked, "a", 5)).isEqualTo(1.0);
        assertThat(EvalMetrics.hitAtK(ranked, "e", 5)).isEqualTo(1.0);
        assertThat(EvalMetrics.hitAtK(ranked, "f", 5)).isEqualTo(0.0); // rank 6, outside k=5
        assertThat(EvalMetrics.hitAtK(ranked, "z", 5)).isEqualTo(0.0); // absent
        assertThat(EvalMetrics.hitAtK(ranked, null, 5)).isEqualTo(0.0);
    }

    @Test
    @DisplayName("reciprocal rank is 1/position, 0 when absent")
    void reciprocalRank() {
        List<String> ranked = List.of("a", "b", "c");
        assertThat(EvalMetrics.reciprocalRank(ranked, "a")).isEqualTo(1.0);
        assertThat(EvalMetrics.reciprocalRank(ranked, "b")).isCloseTo(0.5, within(1e-9));
        assertThat(EvalMetrics.reciprocalRank(ranked, "c")).isCloseTo(1.0 / 3, within(1e-9));
        assertThat(EvalMetrics.reciprocalRank(ranked, "z")).isEqualTo(0.0);
    }

    @Test
    @DisplayName("MRR is the mean of reciprocal ranks")
    void mrr() {
        double rr1 = EvalMetrics.reciprocalRank(List.of("a", "b"), "a"); // 1.0
        double rr2 = EvalMetrics.reciprocalRank(List.of("x", "y", "z"), "z"); // 1/3
        assertThat(EvalMetrics.mean(List.of(rr1, rr2))).isCloseTo((1.0 + 1.0 / 3) / 2, within(1e-9));
        assertThat(EvalMetrics.mean(List.of())).isEqualTo(0.0);
    }

    @Test
    @DisplayName("recall@k is fraction of relevant within top-k; 1.0 when nothing relevant")
    void recallAtK() {
        List<String> retrieved = List.of("d1", "d2", "d3", "d4", "d5", "d6");
        assertThat(EvalMetrics.recallAtK(retrieved, Set.of("d1", "d3"), 5)).isEqualTo(1.0);
        assertThat(EvalMetrics.recallAtK(retrieved, Set.of("d1", "d6"), 5)).isCloseTo(0.5, within(1e-9));
        assertThat(EvalMetrics.recallAtK(retrieved, Set.of("zz"), 5)).isEqualTo(0.0);
        assertThat(EvalMetrics.recallAtK(retrieved, Set.of(), 5)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("forbidden ids must be absent at any rank")
    void forbiddenAbsent() {
        List<String> ranked = List.of("a", "b", "c");
        assertThat(EvalMetrics.forbiddenAbsent(ranked, Set.of("x", "y"))).isTrue();
        assertThat(EvalMetrics.forbiddenAbsent(ranked, Set.of("c"))).isFalse();
        assertThat(EvalMetrics.forbiddenAbsent(ranked, Set.of())).isTrue();
    }
}
