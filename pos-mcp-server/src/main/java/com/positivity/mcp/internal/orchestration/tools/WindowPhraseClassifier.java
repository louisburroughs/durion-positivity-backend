package com.positivity.mcp.internal.orchestration.tools;

import com.positivity.mcp.internal.orchestration.tools.DateWindowResolver.Comparison;
import com.positivity.mcp.internal.orchestration.tools.DateWindowResolver.Shape;
import com.positivity.mcp.internal.orchestration.tools.DateWindowResolver.Unit;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Decides a window's SHAPE from the question's own wording, in code.
 *
 * <p>Why this exists rather than another line of prompt text: the shape rule has been stated three
 * times — #1664, #1670, #1672 — and lives today in both the {@code DATE_WINDOW} prompt layer and
 * {@code DateWindowFacadeTool}'s own tool description. The 2026-09-05 gate run read the arguments
 * the model actually sent and found it still choosing {@code ROLLING} for q09 ("in the last twelve
 * months"), q12 ("in the last six months") and q15 (a mixed comparison the precedence rule assigns
 * to the calendar). The rule was never the missing part; asking a model to apply it on every turn
 * was. #1675 is titled "compute date windows in code, not prompt text", and shape selection was the
 * half still left to the model.
 *
 * <p>This classifier is deliberately narrow. It recognises the forms the rule actually names and
 * returns {@link Optional#empty()} for everything else, because a wrong confident answer here is
 * worse than no answer: {@code DateWindowFacadeTool} falls back to the model's own choice when this
 * abstains, so abstaining costs nothing and over-reaching would silently redefine a window the
 * caller asked for. It reads only wording, never the clock.
 */
final class WindowPhraseClassifier {

    /** What the wording decided, when it decided anything. */
    record Classification(
            @NonNull Shape shape,
            @NonNull Unit unit,
            int count,
            @NonNull Comparison comparison) {}

    private static final Map<String, Integer> NUMBER_WORDS = Map.ofEntries(
            Map.entry("one", 1),
            Map.entry("two", 2),
            Map.entry("three", 3),
            Map.entry("four", 4),
            Map.entry("five", 5),
            Map.entry("six", 6),
            Map.entry("seven", 7),
            Map.entry("eight", 8),
            Map.entry("nine", 9),
            Map.entry("ten", 10),
            Map.entry("eleven", 11),
            Map.entry("twelve", 12),
            Map.entry("eighteen", 18),
            Map.entry("twenty", 20),
            Map.entry("twenty-four", 24));

    private static final String UNIT_ALTERNATION = "day|days|week|weeks|month|months|quarter|quarters|year|years";

    /**
     * "in/over/during/for/past the last N <unit>". The preposition is captured because it is the
     * whole discriminator between rolling and calendar (#1670).
     */
    private static final Pattern MULTI_PERIOD = Pattern.compile(
            "\\b(in|over|during|for|within)\\s+the\\s+(?:last|past|previous|trailing)\\s+" + "([a-z-]+|\\d+)\\s+("
                    + UNIT_ALTERNATION + ")\\b",
            Pattern.CASE_INSENSITIVE);

    /** "last month", "the previous quarter" — exactly one whole period that has ended. */
    private static final Pattern PRIOR_COMPLETE_PHRASE = Pattern.compile(
            "\\b(?:last|previous|prior)\\s+(week|month|quarter|year)\\b(?!\\s*[,]?\\s*(?:compared|versus|vs))",
            Pattern.CASE_INSENSITIVE);

    /** "this quarter", "year to date". */
    private static final Pattern CURRENT_TO_DATE_PHRASE = Pattern.compile(
            "\\b(?:this\\s+(week|month|quarter|year)|(week|month|quarter|year)\\s+to\\s+date)\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * A comparison phrase that names a specific period rather than a relative one. Under the
     * DATE_WINDOW precedence rule this wins over a rolling primary and pulls BOTH windows onto the
     * calendar, because the fixed phrase names a period the question explicitly identified.
     */
    private static final Pattern NAMED_YEAR_EARLIER = Pattern.compile(
            "\\b(?:the\\s+same\\s+[a-z0-9-]+\\s+(?:" + UNIT_ALTERNATION + ")\\s+(?:last|a)\\s+year"
                    + "|same\\s+period\\s+last\\s+year"
                    + "|year\\s+over\\s+year|year-over-year)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern PRIOR_PERIOD_COMPARISON = Pattern.compile(
            "\\b(?:compared\\s+with|compared\\s+to|versus|vs\\.?)\\s+the\\s+(?:prior|previous)\\s+"
                    + "(?:week|month|quarter|year|period)\\b",
            Pattern.CASE_INSENSITIVE);

    private WindowPhraseClassifier() {}

    /**
     * Classifies the wording, or abstains.
     *
     * @param phrase the user's own utterance; null and blank are safe and abstain
     * @return the shape/unit/count/comparison the wording names, or empty when it names none this
     *     classifier is confident about
     */
    static @NonNull Optional<Classification> classify(@Nullable String phrase) {
        if (phrase == null || phrase.isBlank()) {
            return Optional.empty();
        }
        String text = phrase.toLowerCase(Locale.ROOT);

        Matcher multi = MULTI_PERIOD.matcher(text);
        if (multi.find()) {
            return classifyMultiPeriod(text, multi);
        }
        Matcher current = CURRENT_TO_DATE_PHRASE.matcher(text);
        if (current.find()) {
            String unit = current.group(1) != null ? current.group(1) : current.group(2);
            return Optional.of(new Classification(Shape.CURRENT_TO_DATE, parseUnit(unit), 1, Comparison.NONE));
        }
        Matcher prior = PRIOR_COMPLETE_PHRASE.matcher(text);
        if (prior.find()) {
            return Optional.of(new Classification(Shape.PRIOR_COMPLETE, parseUnit(prior.group(1)), 1, Comparison.NONE));
        }
        return Optional.empty();
    }

    private static @NonNull Optional<Classification> classifyMultiPeriod(@NonNull String text, @NonNull Matcher multi) {
        Integer count = parseCount(multi.group(2));
        if (count == null || count < 1) {
            return Optional.empty();
        }
        Unit unit = parseUnit(multi.group(3));
        Comparison comparison = comparisonIn(text);

        // A range expressed in days has no complete calendar period to span, so it is rolling
        // whatever preposition introduced it (#1670).
        if (unit == Unit.DAY) {
            return Optional.of(new Classification(Shape.ROLLING, unit, count, comparison));
        }

        boolean calendarPreposition = !"over".equalsIgnoreCase(multi.group(1));
        // The mixed-comparison precedence: a comparison phrase naming a specific period pulls a
        // rolling primary onto the calendar, so the question's named period is not redefined.
        boolean namedComparison = NAMED_YEAR_EARLIER.matcher(text).find();

        Shape shape = calendarPreposition || namedComparison ? Shape.CALENDAR_SPAN : Shape.ROLLING;
        return Optional.of(new Classification(shape, unit, count, comparison));
    }

    private static @NonNull Comparison comparisonIn(@NonNull String text) {
        if (NAMED_YEAR_EARLIER.matcher(text).find()) {
            return Comparison.YEAR_EARLIER;
        }
        if (PRIOR_PERIOD_COMPARISON.matcher(text).find()) {
            return Comparison.PRIOR_PERIOD;
        }
        return Comparison.NONE;
    }

    private static @Nullable Integer parseCount(@NonNull String raw) {
        Integer word = NUMBER_WORDS.get(raw);
        if (word != null) {
            return word;
        }
        try {
            return Integer.valueOf(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static @NonNull Unit parseUnit(@NonNull String raw) {
        String singular = raw.endsWith("s") ? raw.substring(0, raw.length() - 1) : raw;
        return switch (singular) {
            case "day" -> Unit.DAY;
            case "week" -> Unit.WEEK;
            case "quarter" -> Unit.QUARTER;
            case "year" -> Unit.YEAR;
            default -> Unit.MONTH;
        };
    }
}
