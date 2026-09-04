package com.positivity.mcp.internal.orchestration.tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.NonNull;

/**
 * The versioned business-term glossary (#1688) — the agreed metric behind each analytical phrase
 * a question can use, so the assistant applies a decided definition instead of inventing one.
 *
 * <p>The rule this exists to serve: <strong>ask when the metric is undefined, answer when only
 * the range is unstated.</strong> "Who owes us the most money?" names a metric that maps onto
 * accounts receivable, so it should be answered; "who are our best customers?" does not, because
 * revenue, margin, payment behaviour and growth are all defensible readings of <em>best</em> and
 * silently picking one produces a confident, plausible, unfalsifiable answer. The existing
 * {@code TOOL_USE} rule ("ask when an argument is missing") is a mechanical trigger on a missing
 * identifier; this is a semantic one, and the two are not the same test.
 *
 * <p>Every definition below was decided by the owning domain agents, not by this module — these
 * are business decisions, and the assistant's job is to read an agreed answer rather than to
 * reach one. The distinctions are deliberate where the language differs: <b>best</b> measures
 * profitability while <b>largest</b> measures revenue; "payment problems" measures currently
 * overdue exposure rather than a speculative behavioural score; "backed up" is an operational
 * lateness measure, not a capacity sentiment.
 *
 * <p>{@link #VERSION} is returned with every lookup and recorded in the tool trace, so a graded
 * fixture (#1682) can assert both the outcome and <em>which</em> definition produced it. Bump it
 * whenever a definition changes, or a fixture that passed under the old wording will keep passing
 * for the wrong reason.
 */
final class BusinessGlossary {

    /** Bump on any change to a definition, a default window, or the term set. */
    static final String VERSION = "2026-09-04.1";

    /**
     * One decided term.
     *
     * @param term the canonical phrase
     * @param definition the metric, stated so a reader can compute it
     * @param defaultWindow the default scope and window to apply when the question omits one
     * @param aliases other wordings that resolve to this same definition
     */
    record Definition(
            @NonNull String term,
            @NonNull String definition,
            @NonNull String defaultWindow,
            @NonNull Set<String> aliases) {}

    private static final List<Definition> DEFINITIONS = List.of(
            new Definition(
                    "best customers",
                    "Customers ranked by net contribution margin: recognized revenue less directly "
                            + "attributable parts and labor cost, net of credit memos and refunds.",
                    "Trailing 12 complete calendar months; current organization/location scope. Tie-break by "
                            + "net recognized revenue, then most recent completed workorder.",
                    Set.of("best customer", "our best customers", "top customers by value")),
            new Definition(
                    "who owes us the most money",
                    "Customers ranked by the sum of positive outstanding A/R on issued, unpaid or partially "
                            + "paid invoices, after applied payments and credits.",
                    "Current balance as of now. Group by the AR customer identity; do not roll up CRM "
                            + "hierarchies.",
                    Set.of("owes us the most", "owes the most money", "who owes us money", "largest balances")),
            new Definition(
                    "payment problems",
                    "Customers with one or more open invoices past dueDate, ranked by overdue balance and "
                            + "then oldest days past due. A customer with no overdue balance has no payment "
                            + "problem.",
                    "Current A/R as of now.",
                    Set.of(
                            "isn't paying on time",
                            "isnt paying on time",
                            "not paying on time",
                            "becoming payment problems",
                            "payment problem")),
            new Definition(
                    "most productive technicians",
                    "Technicians ranked by recognized labor revenue per completed logged labor hour. Revenue "
                            + "is attributed to a technician in proportion to their completed logged hours on "
                            + "the invoiced workorder.",
                    "Trailing 30 complete calendar days; exclude technicians with zero completed logged hours.",
                    Set.of("productive technicians", "most productive", "technician productivity")),
            new Definition(
                    "backed up in the shop",
                    "The count of open workorders whose promisedAt is before now, with a supporting breakdown "
                            + "by work status (including AWAITING_PARTS). If promised dates are unavailable, "
                            + "answer that the metric is currently zero-observable, not undefined.",
                    "Current selected location as of now.",
                    Set.of("backed up", "getting backed up", "shop backlog")),
            new Definition(
                    "running low",
                    "A product-location whose available-to-promise quantity is below its active "
                            + "ReplenishmentPolicy.minimumQuantity. Products without an active policy are not "
                            + "classified as low.",
                    "Current selected location as of now.",
                    Set.of("running low on", "low stock", "below reorder point", "low on")),
            new Definition(
                    "haven't been back recently",
                    "Customers with no completed workorder in the preceding six complete calendar months. "
                            + "\"Back\" means a completed service visit, not an invoice or estimate alone.",
                    "Current organization/location scope.",
                    Set.of("havent been back recently", "haven't been back", "havent been back", "lapsed customers")),
            new Definition(
                    "largest customers",
                    "Customers ranked by net recognized revenue after credit memos and refunds.",
                    "Trailing 12 complete calendar months; current organization/location scope.",
                    Set.of("largest customer", "biggest customers", "top customers by revenue")),
            new Definition(
                    "our best month",
                    "The completed calendar month with the highest net recognized revenue after credit memos "
                            + "and refunds. Do not rank an in-progress month.",
                    "Most recent 12 complete calendar months.",
                    Set.of("best month", "our best month ever")),
            new Definition(
                    "what did we spend with",
                    "The sum of posted A/P vendor-bill amounts, net of vendor credits, for the named vendor.",
                    "The window the question requests, resolved through resolveDateWindow or "
                            + "resolveNamedPeriod; a conventional quarter resolves without a clarifying question.",
                    Set.of("spend with vendor", "how much did we spend with", "vendor spend")));

    private static final Map<String, Definition> BY_KEY = index();

    private BusinessGlossary() {}

    /**
     * Builds the lookup index, failing fast if two entries normalize to the same key.
     *
     * <p>Silently keeping the first entry on a collision would make lookups depend on declaration
     * order under the real normalization rules — punctuation stripping and whitespace collapse mean
     * two visibly different aliases can share a key — and that is precisely the "the answer came
     * from whichever definition happened to be declared first" failure this class exists to prevent.
     * A collision is an authoring mistake in {@link #DEFINITIONS}, so it fails at class
     * initialization with both terms named, rather than at some later lookup with neither.
     */
    private static Map<String, Definition> index() {
        Map<String, Definition> index = new LinkedHashMap<>();
        for (Definition definition : DEFINITIONS) {
            put(index, normalize(definition.term()), definition);
            for (String alias : definition.aliases()) {
                put(index, normalize(alias), definition);
            }
        }
        return Map.copyOf(index);
    }

    private static void put(
            @NonNull Map<String, Definition> index, @NonNull String key, @NonNull Definition definition) {
        Definition existing = index.putIfAbsent(key, definition);
        // Same definition twice is harmless: an alias may normalize onto its own canonical term.
        if (existing != null && !existing.term().equals(definition.term())) {
            throw new IllegalStateException("Glossary key '" + key + "' is claimed by both '" + existing.term()
                    + "' and '" + definition.term() + "'; a normalized key must name exactly one definition");
        }
    }

    /** Every decided term, in declaration order. */
    static @NonNull List<Definition> definitions() {
        return DEFINITIONS;
    }

    /**
     * Looks up {@code phrase}. Matches an exact canonical term or alias first, then falls back to
     * the longest key the phrase contains — so "who are our best customers this year" resolves, and
     * a phrase containing both "best customers" and "largest customers" resolves to the longer of
     * the two rather than to whichever was declared first.
     *
     * <p>When two keys of the SAME length both match and they name different definitions, the
     * phrase is genuinely ambiguous and this returns empty. Empty means "ask the user", so an
     * ambiguous phrase produces a clarifying question rather than a coin flip decided by
     * declaration order. That is the same judgement the class makes everywhere else: a silently
     * chosen metric reads as confident and cannot be checked, which is worse than a question.
     */
    static @NonNull Optional<Definition> lookup(@NonNull String phrase) {
        String normalized = normalize(phrase);
        Definition exact = BY_KEY.get(normalized);
        if (exact != null) {
            return Optional.of(exact);
        }
        Definition best = null;
        int bestLength = 0;
        boolean ambiguous = false;
        for (Map.Entry<String, Definition> entry : BY_KEY.entrySet()) {
            String key = entry.getKey();
            if (!normalized.contains(key)) {
                continue;
            }
            if (key.length() > bestLength) {
                best = entry.getValue();
                bestLength = key.length();
                ambiguous = false;
            } else if (key.length() == bestLength
                    && best != null
                    && !best.term().equals(entry.getValue().term())) {
                ambiguous = true;
            }
        }
        return ambiguous ? Optional.empty() : Optional.ofNullable(best);
    }

    /**
     * Lowercases, collapses whitespace, and drops punctuation that does not separate words.
     *
     * <p>Package-private so the uniqueness test can key on the SAME transform the index uses. A
     * test that checked raw lowercase instead would assert a different invariant from the one the
     * index actually holds, and would pass over exactly the collisions that matter.
     */
    static @NonNull String normalize(@NonNull String raw) {
        return raw.toLowerCase(Locale.ROOT)
                .replaceAll("[?.,!:;\"]", "")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
