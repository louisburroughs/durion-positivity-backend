package com.positivity.mcp.internal.orchestration;

import com.positivity.mcp.internal.orchestration.rag.QueryDocumentRetriever;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.NonNull;
import org.springframework.ai.document.Document;

/**
 * Re-ranks retrieved content by lexical overlap and source rank.
 *
 * <p>
 * #1180 compound questions: a query carrying two information needs (e.g. "what
 * does an invoice
 * number look like <em>and</em> what permission is needed to read an invoice")
 * scores every
 * candidate against the <em>whole</em> query, so chunks serving the dominant
 * need crowd the other
 * need's best chunk out of the top-K cut. When the query splits into multiple
 * sub-queries (clauses
 * joined by a conjunction that starts a new question, or separate sentences),
 * each sub-query's
 * best-matching candidate is guaranteed a slot in the final top-K; the
 * remaining slots are filled
 * in the normal overall-score order. Single-need queries never split, so their
 * results are
 * byte-for-byte unchanged. Gated by
 * {@code mcp.rag.rerank.compound-slots-enabled} (default
 * {@code true}), applied via {@link CompoundRerankTuning}.
 */
final class RerankedContentRetriever implements QueryDocumentRetriever {

    static final boolean DEFAULT_COMPOUND_SLOTS_ENABLED = true;
    static final int DEFAULT_MAX_SUB_QUERIES = 3;

    /**
     * Startup-installed defaults for the manager-built instances (see
     * {@link CompoundRerankTuning});
     * the retriever itself is constructed per role-agent, not Spring-managed.
     */
    private static volatile boolean defaultCompoundSlotsEnabled = DEFAULT_COMPOUND_SLOTS_ENABLED;

    private static volatile int defaultMaxSubQueries = DEFAULT_MAX_SUB_QUERIES;

    /**
     * A sub-query boundary is either (a) a coordinating conjunction followed by a
     * word that starts a
     * new question/clause (interrogative or auxiliary verb) — so "returns and
     * refunds" never splits —
     * or (b) a sentence boundary ({@code ?} / {@code ;}) with trailing text.
     */
    private static final Pattern SUB_QUERY_BOUNDARY_CANDIDATE = Pattern.compile(" (and|plus|but) |(?<=[?;]) ",
            Pattern.CASE_INSENSITIVE);

    private static final Set<String> SUB_QUERY_STARTERS = Set.of(
            "what", "which", "who", "whom", "whose", "when", "where", "why", "how", "is", "are", "was", "were", "do",
            "does", "did", "can", "could", "should", "would", "will", "may", "must");

    /**
     * Fragments shorter than this are not information needs of their own; they
     * suppress the split.
     */
    private static final int MIN_SUB_QUERY_TOKENS = 3;

    private static final Comparator<RankedContent> OVERALL_SCORE_ORDER = Comparator
            .comparingDouble(RankedContent::score).reversed().thenComparingInt(RankedContent::originalRank);

    private final QueryDocumentRetriever delegate;
    private final int topK;
    private final boolean compoundSlotsEnabled;
    private final int maxSubQueries;

    RerankedContentRetriever(@NonNull QueryDocumentRetriever delegate, int topK) {
        this(delegate, topK, defaultCompoundSlotsEnabled, defaultMaxSubQueries);
    }

    RerankedContentRetriever(
            @NonNull QueryDocumentRetriever delegate, int topK, boolean compoundSlotsEnabled, int maxSubQueries) {
        this.delegate = delegate;
        this.topK = Math.max(1, topK);
        this.compoundSlotsEnabled = compoundSlotsEnabled;
        this.maxSubQueries = Math.max(1, maxSubQueries);
    }

    /**
     * #1180: install the {@code mcp.rag.rerank.*} tuning as the defaults for new
     * instances.
     */
    static void configureCompoundSlotDefaults(boolean compoundSlotsEnabled, int maxSubQueries) {
        defaultCompoundSlotsEnabled = compoundSlotsEnabled;
        defaultMaxSubQueries = Math.max(1, maxSubQueries);
    }

    @Override
    public @NonNull List<Document> retrieve(@NonNull String queryText) {
        List<Document> candidates = delegate.retrieve(queryText);
        Set<String> queryTokens = tokens(queryText);

        Map<String, RankedContent> deduped = new LinkedHashMap<>();
        for (int index = 0; index < candidates.size(); index++) {
            Document document = candidates.get(index);
            String key = contentKey(document);
            RankedContent candidate = rankContent(document, queryText, queryTokens, index);
            deduped.merge(key, candidate, (left, right) -> left.score() >= right.score() ? left : right);
        }

        List<RankedContent> overallOrder = deduped.values().stream().sorted(OVERALL_SCORE_ORDER).toList();
        if (!compoundSlotsEnabled || overallOrder.size() <= topK) {
            return topKOf(overallOrder);
        }
        List<String> subQueries = splitSubQueries(queryText, maxSubQueries);
        if (subQueries.size() < 2) {
            return topKOf(overallOrder);
        }

        // #1180: reserve a slot for each sub-query's best candidate so one information
        // need cannot
        // crowd the other's best chunk out of the cut, then fill the rest in
        // overall-score order.
        LinkedHashSet<RankedContent> selected = new LinkedHashSet<>();
        for (String subQuery : subQueries) {
            if (selected.size() >= topK) {
                break;
            }
            selected.add(bestForSubQuery(subQuery, overallOrder));
        }
        for (RankedContent candidate : overallOrder) {
            if (selected.size() >= topK) {
                break;
            }
            selected.add(candidate);
        }
        return selected.stream()
                .sorted(OVERALL_SCORE_ORDER)
                .map(RankedContent::content)
                .toList();
    }

    private @NonNull List<Document> topKOf(@NonNull List<RankedContent> overallOrder) {
        return overallOrder.stream().limit(topK).map(RankedContent::content).toList();
    }

    /**
     * Splits a query into its information needs. Returns fewer than two entries
     * when the query is
     * single-need, in which case re-ranking behaves exactly as before #1180.
     */
    static @NonNull List<String> splitSubQueries(@NonNull String queryText, int maxSubQueries) {
        String normalizedQuery = normalize(queryText);
        Matcher boundaryMatcher = SUB_QUERY_BOUNDARY_CANDIDATE.matcher(normalizedQuery);
        List<String> subQueries = new ArrayList<>();
        int maxResults = Math.max(1, maxSubQueries);
        int partStart = 0;

        while (boundaryMatcher.find()) {
            int boundaryEnd = boundaryMatcher.end();
            if (boundaryMatcher.group(1) != null && !startsSubQuery(normalizedQuery, boundaryMatcher.end())) {
                if (!followsSentenceTerminator(normalizedQuery, boundaryMatcher.start())) {
                    continue;
                }
                boundaryEnd = boundaryMatcher.start() + 1;
            }
            addSubQuery(subQueries, normalizedQuery.substring(partStart, boundaryMatcher.start()));
            partStart = boundaryEnd;
            if (subQueries.size() >= maxResults) {
                return List.copyOf(subQueries);
            }
        }

        addSubQuery(subQueries, normalizedQuery.substring(partStart));
        return List.copyOf(subQueries);
    }

    private static boolean startsSubQuery(@NonNull String queryText, int start) {
        String starter = wordAt(queryText, start);
        if ("also".equals(starter)) {
            int nextWordStart = queryText.indexOf(' ', start);
            if (nextWordStart < 0) {
                return false;
            }
            starter = wordAt(queryText, nextWordStart + 1);
        }
        return SUB_QUERY_STARTERS.contains(starter);
    }

    private static boolean followsSentenceTerminator(@NonNull String queryText, int boundaryStart) {
        if (boundaryStart == 0) {
            return false;
        }
        char previous = queryText.charAt(boundaryStart - 1);
        return previous == '?' || previous == ';';
    }

    private static @NonNull String wordAt(@NonNull String text, int start) {
        int end = text.indexOf(' ', start);
        return text.substring(start, end < 0 ? text.length() : end).toLowerCase(Locale.ROOT);
    }

    private static void addSubQuery(@NonNull List<String> subQueries, @NonNull String part) {
        String trimmed = part.trim();
        if (!trimmed.isBlank() && tokens(trimmed).size() >= MIN_SUB_QUERY_TOKENS) {
            subQueries.add(trimmed);
        }
    }

    private static @NonNull RankedContent bestForSubQuery(
            @NonNull String subQuery, @NonNull Collection<RankedContent> candidates) {
        Set<String> subQueryTokens = tokens(subQuery);
        return candidates.stream()
                .max(Comparator.<RankedContent>comparingDouble(
                        candidate -> subQueryScore(candidate.content(), subQuery, subQueryTokens))
                        .thenComparing(Comparator.comparingInt(RankedContent::originalRank)
                                .reversed()))
                .orElseThrow();
    }

    /**
     * Relevance of a candidate to one sub-query: lexical overlap plus exact-phrase
     * boost. The fused
     * source rank is deliberately excluded — it encodes the full compound query's
     * dominant-need
     * bias, which is exactly what slot reservation corrects for (ties break on
     * source rank instead).
     */
    private static double subQueryScore(
            @NonNull Document document, @NonNull String subQuery, @NonNull Set<String> subQueryTokens) {
        String contentText = contentText(document);
        Set<String> contentTokens = tokens(contentText);
        int overlap = 0;
        for (String subQueryToken : subQueryTokens) {
            if (contentTokens.contains(subQueryToken)) {
                overlap++;
            }
        }
        double lexicalScore = subQueryTokens.isEmpty() ? 0.0 : (double) overlap / subQueryTokens.size();
        double phraseBoost = contentText
                .toLowerCase(Locale.ROOT)
                .contains(normalize(subQuery).toLowerCase(Locale.ROOT))
                        ? 0.2
                        : 0.0;
        return (0.6 * lexicalScore) + phraseBoost;
    }

    private static @NonNull RankedContent rankContent(
            @NonNull Document document, @NonNull String queryText, @NonNull Set<String> queryTokens, int index) {
        String contentText = contentText(document);
        Set<String> contentTokens = tokens(contentText);
        int overlap = 0;
        for (String queryToken : queryTokens) {
            if (contentTokens.contains(queryToken)) {
                overlap++;
            }
        }

        double lexicalScore = queryTokens.isEmpty() ? 0.0 : (double) overlap / queryTokens.size();
        double phraseBoost = contentText.toLowerCase(Locale.ROOT).contains(queryText.toLowerCase(Locale.ROOT)) ? 0.2
                : 0.0;
        double rankScore = 1.0 / (index + 1);
        double totalScore = (0.6 * lexicalScore) + (0.3 * rankScore) + phraseBoost;
        return new RankedContent(document, totalScore, index);
    }

    private static @NonNull String contentKey(@NonNull Document document) {
        String text = contentText(document);
        if (text.isBlank()) {
            return String.valueOf(document.hashCode());
        }
        return text;
    }

    private static @NonNull String contentText(@NonNull Document document) {
        if (document.getText() == null) {
            return "";
        }
        return normalize(document.getText());
    }

    private static @NonNull String normalize(@NonNull String text) {
        return text.replaceAll("\\s+", " ").trim();
    }

    private static @NonNull Set<String> tokens(@NonNull String text) {
        return Set.copyOf(List.of(normalize(text).toLowerCase(Locale.ROOT).split("[^a-z0-9]+")).stream()
                .filter(token -> !token.isBlank())
                .toList());
    }

    private record RankedContent(@NonNull Document content, double score, int originalRank) {
    }
}
