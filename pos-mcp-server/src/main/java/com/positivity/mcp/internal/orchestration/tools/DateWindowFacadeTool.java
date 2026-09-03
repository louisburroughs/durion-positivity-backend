package com.positivity.mcp.internal.orchestration.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Locale;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
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
 */
@Component
public class DateWindowFacadeTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Clock clock;

    public DateWindowFacadeTool(@NonNull Clock clock) {
        this.clock = clock;
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
                    + "ending with the last complete one (\"in/during/for the last N months\"). Units: DAY, "
                    + "WEEK (ISO Monday-Sunday), MONTH, QUARTER, YEAR — DAY is only valid with ROLLING. "
                    + "Optional comparison, for a question that pairs the window with another one: "
                    + "PRIOR_PERIOD (the same shape and length immediately before the primary window) or "
                    + "YEAR_EARLIER (the primary window's exact span, one year earlier). Returns JSON: "
                    + "startDate, endDate, shape, statement (a human-readable sentence to quote verbatim), and "
                    + "— only when a comparison was requested — comparison.startDate/endDate/statement.")
    public String resolveDateWindow(
            @ToolParam(
                            description = "ROLLING, CURRENT_TO_DATE, PRIOR_COMPLETE, or CALENDAR_SPAN — see the tool "
                                    + "description for what each means and which wording maps to it")
                    @NonNull
                    String shape,
            @ToolParam(description = "DAY, WEEK, MONTH, QUARTER, or YEAR. DAY is only valid with shape=ROLLING")
                    @NonNull
                    String unit,
            @ToolParam(
                            description = "Number of units/periods, e.g. 6 for \"the last six months\". Must be 1 "
                                    + "for CURRENT_TO_DATE and PRIOR_COMPLETE")
                    int count,
            @ToolParam(
                            description = "Comparison window to also resolve: NONE (default), PRIOR_PERIOD, or "
                                    + "YEAR_EARLIER",
                            required = false)
                    @Nullable
                    String comparison) {
        DateWindowResolver.ResolvedWindow resolved = DateWindowResolver.resolve(
                LocalDate.now(clock), parseShape(shape), parseUnit(unit), count, parseComparison(comparison));
        return write(resolved);
    }

    private static DateWindowResolver.Shape parseShape(@NonNull String raw) {
        try {
            return DateWindowResolver.Shape.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(
                    "Unsupported shape '" + raw
                            + "': pass one of ROLLING, CURRENT_TO_DATE, PRIOR_COMPLETE, CALENDAR_SPAN",
                    invalid);
        }
    }

    private static DateWindowResolver.Unit parseUnit(@NonNull String raw) {
        try {
            return DateWindowResolver.Unit.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(
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
            throw new IllegalArgumentException(
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
