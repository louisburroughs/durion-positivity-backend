package com.positivity.mcp.eval;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure metric-computation for the Gate 0 evaluation harness.
 *
 * <p>These functions are backend-independent: given a ranked tool list (from the live
 * tool-selection path) or a retrieved doc list (from the live RAG path), they compute the
 * scores the gate thresholds are expressed in. They are unit-tested on synthetic data so the
 * math is verified before any live run; the live run only supplies the rankings.
 */
public final class EvalMetrics {

    private EvalMetrics() {}

    /**
     * hit@k for a single fixture: 1.0 if the primary expected id appears within the top {@code k}
     * of the ranked list, else 0.0.
     */
    public static double hitAtK(List<String> ranked, String primaryExpected, int k) {
        if (primaryExpected == null || ranked == null) {
            return 0.0;
        }
        int limit = Math.min(k, ranked.size());
        for (int i = 0; i < limit; i++) {
            if (primaryExpected.equals(ranked.get(i))) {
                return 1.0;
            }
        }
        return 0.0;
    }

    /** Reciprocal rank of {@code target} in {@code ranked} (1-based); 0.0 if absent. */
    public static double reciprocalRank(List<String> ranked, String target) {
        if (target == null || ranked == null) {
            return 0.0;
        }
        for (int i = 0; i < ranked.size(); i++) {
            if (target.equals(ranked.get(i))) {
                return 1.0 / (i + 1);
            }
        }
        return 0.0;
    }

    /** Mean of a list of per-fixture scores; 0.0 for an empty input. */
    public static double mean(List<Double> scores) {
        if (scores == null || scores.isEmpty()) {
            return 0.0;
        }
        double sum = 0.0;
        for (double s : scores) {
            sum += s;
        }
        return sum / scores.size();
    }

    /**
     * recall@k for a single fixture: fraction of the {@code relevant} set present within the top
     * {@code k} of {@code retrieved}. 1.0 when there is nothing relevant to retrieve.
     */
    public static double recallAtK(List<String> retrieved, Collection<String> relevant, int k) {
        if (relevant == null || relevant.isEmpty()) {
            return 1.0;
        }
        if (retrieved == null) {
            return 0.0;
        }
        Set<String> topK = new HashSet<>(retrieved.subList(0, Math.min(k, retrieved.size())));
        int found = 0;
        for (String r : relevant) {
            if (topK.contains(r)) {
                found++;
            }
        }
        return (double) found / relevant.size();
    }

    /**
     * Forbidden-id check: true when none of the {@code forbidden} ids appear anywhere in
     * {@code ranked}. Used for permission-negative (tool) and visibility-negative (doc) fixtures,
     * which are hard-fail regardless of rank.
     */
    public static boolean forbiddenAbsent(List<String> ranked, Collection<String> forbidden) {
        if (forbidden == null || forbidden.isEmpty()) {
            return true;
        }
        if (ranked == null) {
            return true;
        }
        for (String f : forbidden) {
            if (ranked.contains(f)) {
                return false;
            }
        }
        return true;
    }
}
