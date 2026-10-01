package com.positivity.mcp.internal.scopegraph;

/**
 * How a seed entity was recognised in a message (ADR-0069 §5.1, spec §2.7). Declared strongest
 * first: an entity recognised several ways records the first kind that applies.
 *
 * <p>A seed carries its entity key and this kind only, never the text that matched.
 */
public enum MatchKind {
    /** A lexicon identifier regex matched. */
    IDENTIFIER(true),
    /** A lexicon term matched on word boundaries, case-insensitively, as written, and denotes one entity. */
    EXACT_TERM(true),
    /** A term matched as written but denotes more than one entity; all of them are seeded. */
    AMBIGUOUS_TERM(false),
    /** Only a {@code BusinessGlossary} phrase matched; the lexicon itself did not name the entity. */
    GLOSSARY_TERM(false),
    /** A term matched only after folding: diacritics stripped, a trailing plural removed on either side. */
    FOLDED_TERM(false),
    /**
     * ADR-0068 spec §2.7: the decision model's {@code entity_<key>} Noul answered {@code true} at or
     * above threshold in {@code enforce}; no lexicon term matched. {@code LOW} (ADR-0069 §5.4 reserves
     * {@code HIGH} for identifier and exact-term seeds).
     */
    TAG(false);

    private final boolean high;

    MatchKind(boolean high) {
        this.high = high;
    }

    /** True when a seed of this kind makes the scope's confidence {@code HIGH}. */
    public boolean high() {
        return high;
    }
}
