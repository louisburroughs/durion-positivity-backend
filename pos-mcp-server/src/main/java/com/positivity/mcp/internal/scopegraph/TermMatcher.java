package com.positivity.mcp.internal.scopegraph;

import com.positivity.mcp.internal.scopegraph.ScopeSet.Seed;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * ADR-0069 §5.1 (spec §2.7): links entities from a message by lexicon term and identifier pattern,
 * with no model call.
 *
 * <p>Built once per graph snapshot and immutable afterwards: every pattern is compiled here, never
 * per turn. Matching always goes through {@link Matcher#find()} or {@link Matcher#lookingAt()} on a
 * region, never {@link String#matches}, which anchors the whole input and fails on the line breaks
 * an ordinary pasted question carries.
 *
 * <p>Two passes over the message share one coordinate space, so their matches can be compared:
 *
 * <ul>
 *   <li><em>exact</em>: the term as written in the lexicon, on word boundaries, case-insensitively;
 *   <li><em>folded</em>: diacritics stripped on both sides, and a trailing plural {@code s}, {@code
 *       es} or {@code x} removed on either side.
 * </ul>
 *
 * <p>Longest match wins: a match lying inside a longer one does not seed ({@code work order} does
 * not also seed {@code order}), and where both passes match the same text the exact one stands.
 */
final class TermMatcher {

    /**
     * Only this much of a message is scanned. A chat question is far shorter; the bound keeps a
     * pasted document from turning a sub-millisecond step into a long one.
     */
    static final int MAX_SCANNED_CHARS = 8_000;

    /** A stripped plural variant shorter than this is not a word any more ({@code prix} is not {@code pri}). */
    private static final int MIN_STRIPPED_LENGTH = 4;
    /** A word shorter than this takes no plural suffix ({@code po} must not match {@code pos}). */
    private static final int MIN_SUFFIXED_LENGTH = 3;

    private static final String NOT_AFTER_WORD = "(?<![\\p{L}\\p{N}])";
    private static final String NOT_BEFORE_WORD = "(?![\\p{L}\\p{N}])";
    private static final String PLURAL_SUFFIX = "(?:s|es|x)?";
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern LETTERS = Pattern.compile("\\p{L}+");
    private static final Comparator<String> LONGEST_FIRST =
            Comparator.comparingInt(String::length).reversed().thenComparing(Comparator.naturalOrder());

    /** What one phrase denotes. */
    private record Entry(@NonNull Set<String> entities, boolean glossaryOnly) {}

    private record IdentifierRule(
            @NonNull Pattern pattern, @NonNull Set<String> entities) {}

    private record Hit(
            int start, int end, boolean exact, @NonNull Entry entry) {
        int length() {
            return end - start;
        }
    }

    /** Null when the graph has no term that denotes an entity. */
    @Nullable
    private final Pattern exactPattern;

    private final List<Entry> exactEntries;

    @Nullable
    private final Pattern foldedPattern;

    private final List<Entry> foldedEntries;
    private final List<IdentifierRule> identifiers;

    private TermMatcher(
            @Nullable Pattern exactPattern,
            List<Entry> exactEntries,
            @Nullable Pattern foldedPattern,
            List<Entry> foldedEntries,
            List<IdentifierRule> identifiers) {
        this.exactPattern = exactPattern;
        this.exactEntries = exactEntries;
        this.foldedPattern = foldedPattern;
        this.foldedEntries = foldedEntries;
        this.identifiers = identifiers;
    }

    static @NonNull TermMatcher of(@NonNull ScopeGraph graph) {
        // Phrase -> what it denotes. The same phrase in two languages is one entry.
        Map<String, Set<String>> exactEntities = new TreeMap<>(LONGEST_FIRST);
        Map<String, Boolean> lexiconPhrase = new TreeMap<>();
        Map<String, Set<String>> foldedEntities = new TreeMap<>(LONGEST_FIRST);
        for (ScopeNode node : graph.nodesOfType(NodeType.TERM)) {
            if (!(node.attributes() instanceof NodeAttributes.Term term)) {
                continue;
            }
            Set<String> entities = targets(graph, node.id(), EdgeType.DENOTES);
            if (entities.isEmpty()) {
                // A glossary phrase that names no entity is a term that seeds nothing.
                continue;
            }
            String phrase = normalize(term.phrase());
            if (phrase.isEmpty()) {
                continue;
            }
            exactEntities.computeIfAbsent(phrase, ignored -> new TreeSet<>()).addAll(entities);
            lexiconPhrase.merge(phrase, !term.glossary(), Boolean::logicalOr);
            foldedEntities
                    .computeIfAbsent(fold(phrase), ignored -> new TreeSet<>())
                    .addAll(entities);
        }

        List<Entry> exactEntries = new ArrayList<>();
        List<String> exactAlternatives = new ArrayList<>();
        exactEntities.forEach((phrase, entities) -> {
            exactEntries.add(new Entry(Set.copyOf(entities), !lexiconPhrase.get(phrase)));
            exactAlternatives.add(exactRegex(phrase));
        });
        List<Entry> foldedEntries = new ArrayList<>();
        List<String> foldedAlternatives = new ArrayList<>();
        foldedEntities.forEach((phrase, entities) -> {
            foldedEntries.add(new Entry(Set.copyOf(entities), false));
            foldedAlternatives.add(foldedRegex(phrase));
        });

        List<IdentifierRule> identifiers = new ArrayList<>();
        for (ScopeNode node : graph.nodesOfType(NodeType.IDENTIFIER_PATTERN)) {
            Set<String> entities = targets(graph, node.id(), EdgeType.IDENTIFIES);
            if (entities.isEmpty() || !(node.attributes() instanceof NodeAttributes.IdentifierPattern identifier)) {
                continue;
            }
            try {
                identifiers.add(new IdentifierRule(Pattern.compile(identifier.pattern()), Set.copyOf(entities)));
            } catch (PatternSyntaxException invalid) {
                // The lexicon loader rejects a pattern that does not compile; a graph built another
                // way simply has one identifier fewer.
            }
        }
        return new TermMatcher(
                alternation(exactAlternatives, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE),
                List.copyOf(exactEntries),
                alternation(foldedAlternatives, 0),
                List.copyOf(foldedEntries),
                List.copyOf(identifiers));
    }

    /** The entities {@code message} names, each with the strongest way it was recognised, by entity key. */
    @NonNull
    List<Seed> match(@NonNull String message) {
        String scanned = message.length() > MAX_SCANNED_CHARS ? message.substring(0, MAX_SCANNED_CHARS) : message;
        Map<String, MatchKind> kinds = new TreeMap<>();

        for (IdentifierRule rule : identifiers) {
            if (rule.pattern().matcher(scanned).find()) {
                rule.entities().forEach(entity -> strongest(kinds, entity, MatchKind.IDENTIFIER));
            }
        }

        // Both passes run over strings of the same length, so a span means the same text in each.
        String composed = Normalizer.normalize(scanned, Normalizer.Form.NFC);
        List<Hit> hits = new ArrayList<>();
        collect(exactPattern, exactEntries, composed, true, hits);
        collect(foldedPattern, foldedEntries, fold(composed), false, hits);
        for (Hit hit : hits) {
            if (shadowed(hit, hits)) {
                continue;
            }
            MatchKind kind = kindOf(hit);
            hit.entry().entities().forEach(entity -> strongest(kinds, entity, kind));
        }

        List<Seed> seeds = new ArrayList<>(kinds.size());
        kinds.forEach((entity, kind) -> seeds.add(new Seed(entity, kind)));
        return seeds;
    }

    private static MatchKind kindOf(Hit hit) {
        if (!hit.exact()) {
            return MatchKind.FOLDED_TERM;
        }
        if (hit.entry().glossaryOnly()) {
            return MatchKind.GLOSSARY_TERM;
        }
        return hit.entry().entities().size() > 1 ? MatchKind.AMBIGUOUS_TERM : MatchKind.EXACT_TERM;
    }

    private static void strongest(Map<String, MatchKind> kinds, String entity, MatchKind kind) {
        kinds.merge(entity, kind, (known, candidate) -> candidate.compareTo(known) < 0 ? candidate : known);
    }

    /** A hit inside a longer one does not seed; on the same text the exact hit stands. */
    private static boolean shadowed(Hit hit, List<Hit> hits) {
        for (Hit other : hits) {
            if (other == hit || other.start() > hit.start() || other.end() < hit.end()) {
                continue;
            }
            if (other.length() > hit.length() || (other.exact() && !hit.exact())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Tries the alternation at every word start. The alternatives are ordered longest phrase first,
     * so the hit at one position is the longest term that begins there.
     */
    private static void collect(
            @Nullable Pattern pattern, List<Entry> entries, String text, boolean exact, List<Hit> hits) {
        if (pattern == null) {
            return;
        }
        Matcher matcher = pattern.matcher(text);
        // The boundary look-arounds must see the characters outside the region being tried.
        matcher.useTransparentBounds(true);
        int length = text.length();
        for (int start = 0; start < length; start++) {
            char current = text.charAt(start);
            if (Character.isWhitespace(current) || (start > 0 && Character.isLetterOrDigit(text.charAt(start - 1)))) {
                continue;
            }
            matcher.region(start, length);
            if (!matcher.lookingAt()) {
                continue;
            }
            for (int group = 1; group <= entries.size(); group++) {
                if (matcher.start(group) >= 0) {
                    hits.add(new Hit(matcher.start(), matcher.end(), exact, entries.get(group - 1)));
                    break;
                }
            }
        }
    }

    private static @Nullable Pattern alternation(List<String> alternatives, int flags) {
        if (alternatives.isEmpty()) {
            return null;
        }
        StringBuilder regex = new StringBuilder(NOT_AFTER_WORD).append("(?:");
        for (int index = 0; index < alternatives.size(); index++) {
            if (index > 0) {
                regex.append('|');
            }
            regex.append('(').append(alternatives.get(index)).append(')');
        }
        return Pattern.compile(regex.append(')').append(NOT_BEFORE_WORD).toString(), flags);
    }

    /** The phrase as written: each word quoted, any run of whitespace between words. */
    private static String exactRegex(String phrase) {
        StringBuilder regex = new StringBuilder();
        for (String word : WHITESPACE.split(phrase)) {
            if (!regex.isEmpty()) {
                regex.append("\\s+");
            }
            regex.append(Pattern.quote(word));
        }
        return regex.toString();
    }

    /** The folded phrase with a plural removed or added on each word. */
    private static String foldedRegex(String foldedPhrase) {
        StringBuilder regex = new StringBuilder();
        for (String word : WHITESPACE.split(foldedPhrase)) {
            if (!regex.isEmpty()) {
                regex.append("\\s+");
            }
            regex.append(foldedWordRegex(word));
        }
        return regex.toString();
    }

    private static String foldedWordRegex(String word) {
        if (!LETTERS.matcher(word).matches()) {
            return Pattern.quote(word);
        }
        Set<String> variants = new LinkedHashSet<>();
        variants.add(word);
        for (String suffix : List.of("es", "s", "x")) {
            if (word.endsWith(suffix) && word.length() - suffix.length() >= MIN_STRIPPED_LENGTH) {
                variants.add(word.substring(0, word.length() - suffix.length()));
            }
        }
        StringBuilder regex = new StringBuilder("(?:");
        boolean first = true;
        for (String variant : variants) {
            if (!first) {
                regex.append('|');
            }
            first = false;
            regex.append(Pattern.quote(variant));
            if (variant.length() >= MIN_SUFFIXED_LENGTH) {
                regex.append(PLURAL_SUFFIX);
            }
        }
        return regex.append(')').toString();
    }

    /** Composed, lower-cased, trimmed, inner whitespace collapsed: the form a phrase is compared in. */
    private static String normalize(String phrase) {
        return WHITESPACE
                .matcher(
                        Normalizer.normalize(phrase, Normalizer.Form.NFC).trim().toLowerCase(Locale.ROOT))
                .replaceAll(" ");
    }

    /**
     * Lower-cases and strips diacritics one character at a time, so the result has the length of its
     * input and a position in one is the same position in the other.
     */
    static @NonNull String fold(@NonNull String text) {
        char[] folded = new char[text.length()];
        for (int index = 0; index < folded.length; index++) {
            char current = text.charAt(index);
            if (current > '\u007f' && !Character.isSurrogate(current)) {
                String decomposed = Normalizer.normalize(String.valueOf(current), Normalizer.Form.NFD);
                if (Character.isLetter(decomposed.charAt(0))) {
                    current = decomposed.charAt(0);
                }
            }
            folded[index] = Character.toLowerCase(current);
        }
        return new String(folded);
    }

    private static Set<String> targets(ScopeGraph graph, NodeId from, EdgeType type) {
        Set<String> targets = new TreeSet<>();
        graph.outgoing(from, type).forEach(edge -> targets.add(edge.to().key()));
        return targets;
    }
}
