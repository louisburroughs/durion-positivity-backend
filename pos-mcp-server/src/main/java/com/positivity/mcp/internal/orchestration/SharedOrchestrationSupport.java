package com.positivity.mcp.internal.orchestration;

import com.positivity.mcp.internal.config.CurrentUserContext;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

@Component
public class SharedOrchestrationSupport {

    private static final int MAX_LOG_PREVIEW_LENGTH = 160;

    private final Clock clock;

    public SharedOrchestrationSupport(@NonNull Clock clock) {
        this.clock = clock;
    }

    public @NonNull List<Object> mergeTools(
            @NonNull Collection<Object> roleTools, @NonNull Collection<Object> fallbackTools) {
        return ToolSelectionSupport.mergeWithoutDuplicateToolNames(roleTools, fallbackTools.toArray(Object[]::new));
    }

    public @NonNull String toolCacheKey(@NonNull Collection<Object> tools) {
        TreeSet<String> names = new TreeSet<>(Comparator.naturalOrder());
        tools.forEach(tool -> names.add(toolName(tool)));
        return names.isEmpty() ? "none" : String.join("+", names);
    }

    public @NonNull List<String> toolNames(@NonNull Collection<Object> tools) {
        return tools.stream().map(this::toolName).toList();
    }

    public @NonNull String toolName(@NonNull Object tool) {
        return ClassUtils.getUserClass(tool).getSimpleName();
    }

    /**
     * The caller-context suffix appended to system prompts by both managers and the T0 fast path.
     *
     * <p>Carries the current date (#1661). Without it the model has no supplied "today" and resolves
     * every relative range — "last six months", "this year" — from a date it invents, while the
     * TOOL-USE layer forbids exactly that kind of unstated assumption. Supplying it also makes a run
     * reproducible: the same question asked on two days resolved to two different windows, so a gate
     * could change verdict with no code change.
     *
     * <p>#1684: the closing sentence used to read "Resolve every relative date range from these
     * dates", which was correct when the model did the arithmetic and became a contradiction when
     * #1675 moved it into {@code resolveDateWindow}. This block is appended <em>after</em> every
     * assembled layer ({@code SpringAiPosAssistant.buildSystemPrompt}), so it had the last word: the
     * DATE_WINDOW layer said "Never compute or assume a date yourself" and then, in the position
     * recency favours most, this told the model to compute one from two supplied dates. It now
     * routes to the tool instead, so the two agree. The dates themselves stay — they are what makes
     * "is that this year?" answerable without a tool round trip, and the T0 fast path has no tools
     * at all.
     */
    public @NonNull String formatUserContext(@NonNull CurrentUserContext userContext) {
        LocalDate today = LocalDate.now(clock);
        // A period is complete only once it has ended, so on the last day of a month that month is
        // still open; this yields the month before the current one in every case.
        LocalDate lastCompleteMonthEnd = today.withDayOfMonth(1).minusDays(1);
        return "Authenticated user context: username=" + userContext.username()
                + ", userId=" + userContext.userId() + ", primaryRole="
                + userContext.primaryRole() + ", roles="
                + userContext.roles() + ", authorityCount="
                + userContext.authorities().size()
                + ". Interpret references to 'me', 'my', or 'current user' as this authenticated user."
                + " If a question depends on the user's exact permissions, prefer a self-service permissions tool before asking for identifiers."
                + " Today's date is " + today + " (UTC); the last complete calendar month ended "
                + lastCompleteMonthEnd + ". Resolve every relative date range with the resolveDateWindow tool"
                + " rather than by computing dates from these.";
    }

    public @NonNull String preview(@NonNull String text) {
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= MAX_LOG_PREVIEW_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, MAX_LOG_PREVIEW_LENGTH - 3) + "...";
    }
}
