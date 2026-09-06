package com.positivity.mcp.internal.orchestration.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.positivity.mcp.internal.exception.InvalidToolArgumentException;
import com.positivity.mcp.internal.service.RequestScopedUserContext;
import com.positivity.security.common.LogSanitizer;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Facade over {@link DateWindowResolver} (#1675): a no-HTTP resolver round that turns a
 * classified relative date range into concrete dates. Three rounds of prompt-only arithmetic
 * (#1664, #1670, #1672) left multi-period calendar spans unreliable — q09 "in the last twelve
 * months" resolved rolling instead of calendar, q12/q15 likewise — while the model classified the
 * shape correctly every time. This tool narrows the model's job to exactly that classification;
 * {@link DateWindowResolver} does the arithmetic deterministically from the shared {@link Clock}
 * bean, the same one {@code SharedOrchestrationSupport.formatUserContext} uses to state "today" in
 * every caller-context block, so the two can never disagree.
 *
 * <p>#1684: every resolution is logged at INFO as {@code shape}/{@code unit}/{@code count}/{@code
 * comparison} plus the dates they produced. Shape is the stage that fails — the arithmetic has been
 * right since #1675, and a window resolved under the wrong shape is byte-indistinguishable from a
 * correct one once it is a pair of dates in a downstream tool's arguments. Naming it in a log line
 * is what turns "which stage was wrong?" from a human reading answer prose for the word "rolling"
 * into an assertion the per-stage eval (#1682) can make. The line carries no customer identifier —
 * a shape, a unit, a count and two calendar dates — which is why it logs where {@code
 * ToolInvocationRecorder} deliberately declines to log tool arguments at all.
 */
@Component
public class DateWindowFacadeTool {

    private static final Logger LOGGER = LoggerFactory.getLogger(DateWindowFacadeTool.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Clock clock;

    private final @Nullable RequestScopedUserContext requestScopedUserContext;

    public DateWindowFacadeTool(@NonNull Clock clock) {
        this(clock, null);
    }

    @Autowired
    public DateWindowFacadeTool(@NonNull Clock clock, @Nullable RequestScopedUserContext requestScopedUserContext) {
        this.clock = clock;
        this.requestScopedUserContext = requestScopedUserContext;
    }

    @Tool(
            description = "Resolve a relative date range (\"last month\", \"in the last six months\", \"this "
                    + "quarter\") to concrete calendar dates. Call this BEFORE any tool argument that takes a "
                    + "date or a date range, and copy the returned startDate/endDate verbatim into that tool's "
                    + "arguments — never compute a date yourself. Quote the returned statement in your final "
                    + "answer so the window is visible to the user. Shapes: ROLLING — N units ending today, "
                    + "inclusive (\"over the last N days/weeks/months/years\"). CURRENT_TO_DATE — the current "
                    + "period so far, from its first day through today (\"this week/month/quarter/year\"); "
                    + "count must be 1. PRIOR_COMPLETE — the one whole period most recently ended (\"last "
                    + "month\", \"the previous quarter\"); count must be 1. CALENDAR_SPAN — N whole periods "
                    + "ending with the last complete one (\"in/during/for the last N months\"). FORWARD — N "
                    + "units starting today and ending in the FUTURE, today included (\"in the next 14 days\", "
                    + "\"the next three months\"); use this for anything upcoming — bills due, appointments "
                    + "scheduled, warranties expiring — and never ask the caller for explicit dates for a "
                    + "forward range. Units: DAY, "
                    + "WEEK (ISO Monday-Sunday), MONTH, QUARTER, YEAR — DAY is only valid with ROLLING or FORWARD. "
                    + "Optional comparison, for a question that pairs the window with another one: "
                    + "PRIOR_PERIOD (the same shape and length immediately before the primary window) or "
                    + "YEAR_EARLIER (the primary window's exact span, one year earlier). Returns JSON: "
                    + "startDate, endDate, shape, statement (a human-readable sentence to quote verbatim), and "
                    + "— only when a comparison was requested — comparison.startDate/endDate/statement. Always pass "
                    + "phrase with the user's own wording for the range: where that wording names a shape "
                    + "outright the server resolves on it, correcting shape, unit and count, and filling "
                    + "in a comparison if you passed NONE.")
    public String resolveDateWindow(
            @ToolParam(
                            description =
                                    "ROLLING, CURRENT_TO_DATE, PRIOR_COMPLETE, CALENDAR_SPAN, or FORWARD — see the tool "
                                            + "description for what each means and which wording maps to it")
                    @Nullable
                    String shape,
            @ToolParam(
                            description = "DAY, WEEK, MONTH, QUARTER, or YEAR. DAY is only valid with shape=ROLLING "
                                    + "or shape=FORWARD")
                    @Nullable
                    String unit,
            @ToolParam(
                            description = "Number of units/periods, e.g. 6 for \"the last six months\". Must be 1 "
                                    + "for CURRENT_TO_DATE and PRIOR_COMPLETE")
                    @Nullable
                    Integer count,
            @ToolParam(
                            description = "Comparison window to also resolve: NONE (default), PRIOR_PERIOD, or "
                                    + "YEAR_EARLIER",
                            required = false)
                    @Nullable
                    String comparison,
            @ToolParam(
                            description = "The user's own wording for the range, copied verbatim from their "
                                    + "question (e.g. \"in the last six months\"). Supply this whenever the "
                                    + "question states a range in words; it lets the server confirm the shape "
                                    + "against the wording.",
                            required = false)
                    @Nullable
                    String phrase) {
        // #1829: a small model sends this tool with no arguments now and then. A primitive count made
        // this module's ReflectiveToolCallback binder hand Method.invoke a null, which unboxed as a
        // NullPointerException — a JVM stack trace the model cannot act on — where every other bad
        // argument gets a message it can correct. The binder now names a missing primitive argument
        // generically; this check adds the tool-specific hint, and covers the String parameters too.
        DateWindowResolver.Shape parsedShape = parseShape(required("shape", shape, DATE_WINDOW_HINT));
        DateWindowResolver.Unit parsedUnit = parseUnit(required("unit", unit, DATE_WINDOW_HINT));
        DateWindowResolver.Comparison parsedComparison = parseComparison(comparison);
        int resolvedCount = required("count", count, DATE_WINDOW_HINT);

        // #1675: the wording decides the shape, and it decides it here rather than in the model.
        // Three rounds of prompt text did not make the classification stick, so where the phrase
        // names a shape unambiguously it wins over the model's own argument. The classifier
        // abstains on anything it is not sure of, and an abstention leaves the model's choice
        // untouched — so this can correct a known-wrong reading without inventing a new one.
        // Prefer the request's own user message over the model's copy of it. Asked for the
        // wording of a range the model sends a normalised snippet — "in the last six months" and
        // "over the last six months" both arrive as "last six months" — and the preposition it
        // drops is the whole discriminator (#1675, proved on the 2026-09-05 gate traces). The
        // model-supplied phrase stays as a fallback for callers with no request context.
        Optional<String> requestWording =
                requestScopedUserContext == null ? Optional.empty() : requestScopedUserContext.currentUserMessage();
        String wording = requestWording.orElse(phrase);
        String wordingSource = requestWording.isPresent() ? "request" : "toolArgument";
        Optional<WindowPhraseClassifier.Classification> fromPhrase = WindowPhraseClassifier.classify(wording);
        if (fromPhrase.isPresent()) {
            WindowPhraseClassifier.Classification c = fromPhrase.get();
            if (c.shape() != parsedShape || c.unit() != parsedUnit || c.count() != resolvedCount) {
                // Deliberately logs the model's own range fragment ("last six months") and never
                // `wording`, which since #1675 may be the caller's entire message. The class
                // contract above is that this line carries no customer identifier, and the whole
                // utterance would break it — the fragment plus a source tag is enough to tell what
                // was corrected and where the deciding wording came from.
                LOGGER.info(
                        "MCP date window shape corrected from wording: modelShape={} modelUnit={} modelCount={} "
                                + "-> shape={} unit={} count={} source={} fragment=\"{}\"",
                        parsedShape,
                        parsedUnit,
                        count,
                        c.shape(),
                        c.unit(),
                        c.count(),
                        wordingSource,
                        LogSanitizer.forLog(phrase));
            }
            parsedShape = c.shape();
            parsedUnit = c.unit();
            resolvedCount = c.count();
            // #1774: a comparison the wording names outright wins over the model's argument, the
            // same way the shape does. #1675 only filled in a comparison the model left NONE and
            // never corrected one it supplied — deliberate caution then, for lack of evidence about
            // comparison wording. q15 is the evidence: "compared with the same six months last
            // year" names a specific period, the model sent PRIOR_PERIOD, and the year-on-year
            // figure was computed against 2025-09..2026-02 instead of 2025-03..2025-08.
            // The classifier still abstains on wording it does not recognise, so an unnamed
            // comparison leaves the model's choice untouched.
            if (c.comparison() != DateWindowResolver.Comparison.NONE
                    || parsedComparison == DateWindowResolver.Comparison.NONE) {
                parsedComparison = c.comparison();
            }
        }

        DateWindowResolver.ResolvedWindow resolved = DateWindowResolver.resolve(
                LocalDate.now(clock), parsedShape, parsedUnit, resolvedCount, parsedComparison);
        logResolution(parsedShape, parsedUnit, resolvedCount, parsedComparison, resolved);
        return write(resolved);
    }

    @Tool(
            description = "Resolve a period the question NAMES OUTRIGHT — \"in 2025\", \"July 2026\", \"Q3 "
                    + "2026\" — to that period's whole calendar span. Pass period as YYYY (a calendar year), "
                    + "YYYY-MM (a calendar month) or YYYY-Qn (a calendar quarter). Copy the returned "
                    + "startDate/endDate verbatim into the tool that needs them and quote the statement in "
                    + "your answer, exactly as with resolveDateWindow. Use this ONLY when the question names "
                    + "the period itself; for anything positioned relative to today (\"this year\", \"last "
                    + "month\", \"in the last six months\") call resolveDateWindow instead — \"2026\" and "
                    + "\"this year\" are different windows and only resolveDateWindow can tell them apart. The "
                    + "span is never clipped to today, so a named period that has not finished yet returns its "
                    + "full calendar extent.")
    public String resolveNamedPeriod(
            @ToolParam(
                            description = "The named calendar period: YYYY (e.g. 2025), YYYY-MM (e.g. 2026-07), "
                                    + "or YYYY-Qn (e.g. 2026-Q3)")
                    @Nullable
                    String period) {
        DateWindowResolver.ResolvedWindow resolved =
                DateWindowResolver.resolveNamed(required("period", period, NAMED_PERIOD_HINT));
        LOGGER.info(
                "MCP date window resolved shape={} period={} startDate={} endDate={}",
                resolved.shape(),
                period,
                resolved.startDate(),
                resolved.endDate());
        return write(resolved);
    }

    /**
     * The assertable record of what the model classified and what that resolved to (#1684, consumed
     * by #1682's per-stage grading).
     *
     * <p>The comparison window's dates are logged too, not just the comparison mode. The questions
     * this whole effort targets — q09, q12 and q15's mixed comparison — are paired-comparison
     * questions, so the window a grader has to check is frequently the comparison one; logging only
     * {@code comparison=YEAR_EARLIER} would name the mode and omit the value, leaving exactly the
     * half that gets graded out of the trace.
     */
    private static void logResolution(
            DateWindowResolver.@NonNull Shape shape,
            DateWindowResolver.@NonNull Unit unit,
            int count,
            DateWindowResolver.@NonNull Comparison comparison,
            DateWindowResolver.@NonNull ResolvedWindow resolved) {
        DateWindowResolver.Window comparisonWindow = resolved.comparison();
        if (comparisonWindow == null) {
            LOGGER.info(
                    "MCP date window resolved shape={} unit={} count={} comparison={} startDate={} endDate={}",
                    shape,
                    unit,
                    count,
                    comparison,
                    resolved.startDate(),
                    resolved.endDate());
            return;
        }
        LOGGER.info(
                "MCP date window resolved shape={} unit={} count={} comparison={} startDate={} endDate={} "
                        + "comparisonStartDate={} comparisonEndDate={}",
                shape,
                unit,
                count,
                comparison,
                resolved.startDate(),
                resolved.endDate(),
                comparisonWindow.startDate(),
                comparisonWindow.endDate());
    }

    private static final String DATE_WINDOW_HINT =
            "resolveDateWindow needs shape, unit and count — e.g. shape=CALENDAR_SPAN, unit=MONTH, count=6 for "
                    + "\"the last six months\"; count is a whole number ≥ 1";
    private static final String NAMED_PERIOD_HINT =
            "resolveNamedPeriod needs period — a calendar year YYYY, month YYYY-MM or quarter YYYY-Qn";

    /**
     * The argument, or an {@link InvalidToolArgumentException} that names it and says what a complete
     * call looks like. Tool arguments arrive from the model, so a missing one is a correctable model
     * mistake, not a programming error — and the message is what the model gets to correct it with.
     */
    private static <T> @NonNull T required(@NonNull String name, @Nullable T value, @NonNull String hint) {
        if (value == null || (value instanceof String text && text.isBlank())) {
            throw new InvalidToolArgumentException("Missing argument '" + name + "': " + hint);
        }
        return value;
    }

    private static DateWindowResolver.Shape parseShape(@NonNull String raw) {
        try {
            return DateWindowResolver.Shape.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            throw new InvalidToolArgumentException(
                    "Unsupported shape '" + raw
                            + "': pass one of ROLLING, CURRENT_TO_DATE, PRIOR_COMPLETE, CALENDAR_SPAN, FORWARD",
                    invalid);
        }
    }

    private static DateWindowResolver.Unit parseUnit(@NonNull String raw) {
        try {
            return DateWindowResolver.Unit.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            throw new InvalidToolArgumentException(
                    "Unsupported unit '" + raw + "': pass one of DAY, WEEK, MONTH, QUARTER, YEAR", invalid);
        }
    }

    private static DateWindowResolver.Comparison parseComparison(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return DateWindowResolver.Comparison.NONE;
        }
        try {
            return DateWindowResolver.Comparison.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            throw new InvalidToolArgumentException(
                    "Unsupported comparison '" + raw + "': pass one of NONE, PRIOR_PERIOD, YEAR_EARLIER", invalid);
        }
    }

    private static String write(DateWindowResolver.@NonNull ResolvedWindow resolved) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("startDate", resolved.startDate().toString());
        root.put("endDate", resolved.endDate().toString());
        root.put("shape", resolved.shape().name());
        root.put("statement", resolved.statement());
        DateWindowResolver.Window comparison = resolved.comparison();
        if (comparison != null) {
            ObjectNode comparisonNode = MAPPER.createObjectNode();
            comparisonNode.put("startDate", comparison.startDate().toString());
            comparisonNode.put("endDate", comparison.endDate().toString());
            comparisonNode.put("statement", comparison.statement());
            root.set("comparison", comparisonNode);
        }
        try {
            return MAPPER.writeValueAsString(root);
        } catch (JsonProcessingException impossible) {
            // ObjectNode of string fields only; writeValueAsString cannot fail here.
            throw new IllegalStateException(impossible);
        }
    }
}
