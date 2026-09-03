package com.positivity.mcp.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.positivity.mcp.internal.domain.RolePersona;
import com.positivity.mcp.internal.entity.SystemPrompt;
import com.positivity.mcp.internal.repository.SystemPromptRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link RolePromptResolverImpl}.
 */
@ExtendWith(MockitoExtension.class)
class RolePromptResolverImplTest {

    private static final String AGENT_NAME = "inventory";
    private static final String MASTER_NAME = "master";

    @Mock
    private SystemPromptRepository systemPromptRepository;

    private SimpleMeterRegistry meterRegistry;
    private RolePromptResolverImpl resolver;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        resolver = TestSnapshots.resolver(systemPromptRepository, meterRegistry);
    }

    // ─── Helpers ────────────────────────────────────────────────────────────

    private static SystemPrompt buildPrompt(String name, String content) {
        var p = new SystemPrompt();
        p.setName(name);
        p.setContent(content);
        return p;
    }

    // ─── resolvePrompt ──────────────────────────────────────────────────────

    @Test
    @DisplayName("resolvePrompt returns agent content when agent prompt exists")
    void resolvePrompt_agentPromptExists_returnsAgentContent() {
        SystemPrompt agentPrompt = buildPrompt(AGENT_NAME, "You are the inventory domain agent.");
        when(systemPromptRepository.findByName(AGENT_NAME)).thenReturn(Optional.of(agentPrompt));

        String result = resolver.resolvePrompt(AGENT_NAME);

        assertThat(result).isEqualTo("You are the inventory domain agent.");
    }

    @Test
    @DisplayName("resolvePrompt falls back to master prompt when agent prompt is missing")
    void resolvePrompt_agentPromptMissing_masterExists_returnsMasterContent() {
        SystemPrompt masterPrompt = buildPrompt(MASTER_NAME, "You are the master orchestration agent.");
        when(systemPromptRepository.findByName(AGENT_NAME)).thenReturn(Optional.empty());
        when(systemPromptRepository.findByName(MASTER_NAME)).thenReturn(Optional.of(masterPrompt));

        String result = resolver.resolvePrompt(AGENT_NAME);

        assertThat(result).isEqualTo("You are the master orchestration agent.");
    }

    @Test
    @DisplayName("resolvePrompt returns built-in prompt when neither agent nor master prompt exists")
    void resolvePrompt_neitherFound_returnsBuiltIn() {
        when(systemPromptRepository.findByName(AGENT_NAME)).thenReturn(Optional.empty());
        when(systemPromptRepository.findByName(MASTER_NAME)).thenReturn(Optional.empty());

        String result = resolver.resolvePrompt(AGENT_NAME);

        assertThat(result).contains("concise POS assistant");
    }

    @Test
    @DisplayName("resolvePrompt returns shared default text when neither agent nor master prompt exists")
    void resolvePrompt_noPromptFoundForAgentOrMaster_returnsSharedDefaultText() {
        when(systemPromptRepository.findByName(AGENT_NAME)).thenReturn(Optional.empty());
        when(systemPromptRepository.findByName(MASTER_NAME)).thenReturn(Optional.empty());

        String result = resolver.resolvePrompt(AGENT_NAME);

        assertThat(result).isEqualTo(SystemPromptDefaults.DEFAULT_PROMPT_TEXT);
    }

    // ─── fallback observability (#639) ──────────────────────────────────────

    @Test
    @DisplayName("resolvePrompt increments no fallback counter when the requested prompt exists")
    void resolvePrompt_promptExists_recordsNoFallback() {
        when(systemPromptRepository.findByName(AGENT_NAME)).thenReturn(Optional.of(buildPrompt(AGENT_NAME, "content")));

        resolver.resolvePrompt(AGENT_NAME);

        assertThat(meterRegistry
                        .find(RolePromptResolverImpl.METRIC_PROMPT_FALLBACK)
                        .counters())
                .isEmpty();
    }

    @Test
    @DisplayName("resolvePrompt counts a master-prompt fallback when the requested prompt is missing")
    void resolvePrompt_agentPromptMissing_recordsMasterFallbackMetric() {
        when(systemPromptRepository.findByName(AGENT_NAME)).thenReturn(Optional.empty());
        when(systemPromptRepository.findByName(MASTER_NAME))
                .thenReturn(Optional.of(buildPrompt(MASTER_NAME, "master content")));

        resolver.resolvePrompt(AGENT_NAME);

        assertThat(meterRegistry
                        .counter(
                                RolePromptResolverImpl.METRIC_PROMPT_FALLBACK,
                                "reason",
                                RolePromptResolverImpl.REASON_MASTER_PROMPT,
                                "requested",
                                AGENT_NAME)
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("resolvePrompt counts a built-in fallback when neither prompt exists")
    void resolvePrompt_neitherFound_recordsBuiltInFallbackMetric() {
        when(systemPromptRepository.findByName(AGENT_NAME)).thenReturn(Optional.empty());
        when(systemPromptRepository.findByName(MASTER_NAME)).thenReturn(Optional.empty());

        resolver.resolvePrompt(AGENT_NAME);

        assertThat(meterRegistry
                        .counter(
                                RolePromptResolverImpl.METRIC_PROMPT_FALLBACK,
                                "reason",
                                RolePromptResolverImpl.REASON_BUILT_IN,
                                "requested",
                                AGENT_NAME)
                        .count())
                .isEqualTo(1.0);
    }

    /**
     * #1613 split the old {@code missing-role-layer} reason. A role the sync has never delivered,
     * whose on-miss fetch also fails, is a genuine sync gap and should alert.
     */
    @Test
    @DisplayName("assemble counts an unknown-role fallback when the role is neither seeded nor fetchable")
    void assemble_rolePersonaMissing_recordsUnknownRoleMetric() {
        when(systemPromptRepository.findByName(MASTER_NAME))
                .thenReturn(Optional.of(buildPrompt(MASTER_NAME, "master content")));
        when(systemPromptRepository.findByName("ROLE_TECHNICIAN")).thenReturn(Optional.empty());

        resolver.assemble("ROLE_TECHNICIAN", "master");

        assertThat(fallbackCount(RolePromptResolverImpl.REASON_UNKNOWN_ROLE, "ROLE_TECHNICIAN"))
                .isEqualTo(1.0);
    }

    /**
     * The other half of the split, and the reason it was needed: CUSTOMER and SELF_SERVICE_CUSTOMER
     * produced a fallback on every external-facing request, so the counter could not distinguish a
     * designed exclusion from a defect. An ineligible role must not be counted as unknown, and must
     * not trigger a pointless fetch for a persona that will never exist.
     */
    @Test
    @DisplayName("assemble counts a persona-ineligible fallback for a role excluded by design")
    void assemble_ineligibleRole_recordsPersonaIneligibleMetric() {
        when(systemPromptRepository.findByName(MASTER_NAME))
                .thenReturn(Optional.of(buildPrompt(MASTER_NAME, "master content")));
        // No stub for ROLE_CUSTOMER on purpose: an ineligible role must be recognised before the
        // persisted row is consulted, so that lookup never happens. A stub here would be flagged as
        // unnecessary — which is exactly the signal that the ordering is right.
        RolePromptResolverImpl ineligibleAware = TestSnapshots.resolver(
                systemPromptRepository,
                meterRegistry,
                TestSnapshots.holderWith(new RolePersona("CUSTOMER", null, null, null, null, null, false)));

        ineligibleAware.assemble("ROLE_CUSTOMER", "master");

        assertThat(fallbackCount(RolePromptResolverImpl.REASON_PERSONA_INELIGIBLE, "ROLE_CUSTOMER"))
                .isEqualTo(1.0);
        assertThat(fallbackCount(RolePromptResolverImpl.REASON_UNKNOWN_ROLE, "ROLE_CUSTOMER"))
                .isZero();
    }

    // ─── DATE-WINDOW layer (#1661) ──────────────────────────────────────────

    /**
     * The layer is unconditional. Four of the twelve Wave 2 gate questions failed because the
     * assistant resolved a relative range against a window it chose silently, so a layer that is
     * present only for some scopes or roles would leave the same hole open for the rest.
     */
    @Test
    @DisplayName("assemble always appends the DATE_WINDOW layer")
    void assemble_alwaysIncludesDateWindowLayer() {
        when(systemPromptRepository.findByName(MASTER_NAME))
                .thenReturn(Optional.of(buildPrompt(MASTER_NAME, "master content")));

        var assembled = resolver.assemble("ROLE_TECHNICIAN", "master", false);

        assertThat(assembled.layers()).contains("DATE_WINDOW");
        assertThat(assembled.text()).contains(SystemPromptDefaults.DATE_WINDOW_LAYER_TEXT);
    }

    /**
     * Ordering is load-bearing rather than cosmetic: the TOOL-USE layer says to ask when an argument
     * is missing, and the DATE-WINDOW layer narrows that by supplying a default for a named range.
     * Read in the other order the narrowing reads as the thing being overridden.
     */
    @Test
    @DisplayName("assemble places DATE_WINDOW after TOOL_USE so it narrows the ask-when-missing rule")
    void assemble_placesDateWindowAfterToolUse() {
        when(systemPromptRepository.findByName(MASTER_NAME))
                .thenReturn(Optional.of(buildPrompt(MASTER_NAME, "master content")));

        var assembled = resolver.assemble("ROLE_TECHNICIAN", "master", false);

        assertThat(assembled.layers().indexOf("DATE_WINDOW"))
                .isGreaterThan(assembled.layers().indexOf("TOOL_USE"));
        assertThat(assembled.text().indexOf(SystemPromptDefaults.DATE_WINDOW_LAYER_TEXT))
                .isGreaterThan(assembled.text().indexOf(SystemPromptDefaults.TOOL_USE_LAYER_TEXT));
    }

    /**
     * A calendar range drops the current partial period, and the answer has to disclose the window
     * it used — the number alone cannot show which window produced it.
     */
    @Test
    @DisplayName("DATE_WINDOW layer drops the partial period for calendar ranges and requires disclosure")
    void dateWindowLayer_calendarRangeDropsThePartialPeriodAndDisclosesTheWindow() {
        assertThat(SystemPromptDefaults.DATE_WINDOW_LAYER_TEXT)
                .contains("ENDING WITH THE MOST RECENT COMPLETE ONE")
                .contains("Exclude the current partial period ONLY from a CALENDAR SPAN")
                .contains("State the window you used in the answer itself");
    }

    /**
     * The distinction the first version of this layer got wrong by treating every relative range as
     * calendar. The preposition is the whole discriminator, and the gate corpus contains both forms:
     * "over the last twelve months" (Q9) and "in the last six months" (Q12).
     */
    @Test
    @DisplayName("DATE_WINDOW layer separates rolling from calendar on the preposition")
    void dateWindowLayer_separatesRollingFromCalendar() {
        assertThat(SystemPromptDefaults.DATE_WINDOW_LAYER_TEXT)
                .contains("ROLLING")
                .contains("CALENDAR")
                .contains("\"over the last six months\" is rolling");
        // A rolling window ends today and drops nothing; only the whole-period shapes trim.
        assertThat(SystemPromptDefaults.DATE_WINDOW_LAYER_TEXT)
                .contains("a ROLLING range ends on the current date and excludes nothing");
    }

    /**
     * What q15/q17 actually needed. "Over the last six months compared with the same six months last
     * year" only means anything if both windows share a shape and a length; measuring one rolling
     * and one calendar makes the year-on-year move an artefact of the windows rather than the
     * business.
     */
    @Test
    @DisplayName("DATE_WINDOW layer requires a comparison period to match the shape it is compared against")
    void dateWindowLayer_requiresPairedPeriodsToMatch() {
        assertThat(SystemPromptDefaults.DATE_WINDOW_LAYER_TEXT)
                .contains("measure BOTH on the same shape and the same length, offset by one period");
    }

    /**
     * The rule that settles q15/q17 without touching the question or the ground truth. Its wording
     * names both shapes at once — "over the last six months" (rolling) paired with "the same six
     * months last year" (named calendar months) — which the shape rule alone cannot resolve.
     * Calendar wins, so both periods are Mar–Aug, and `EXPECTED.md`'s +12.00 % stands as written.
     */
    @Test
    @DisplayName("DATE_WINDOW layer resolves a mixed comparison on the calendar shape")
    void dateWindowLayer_mixedComparisonResolvesToCalendar() {
        assertThat(SystemPromptDefaults.DATE_WINDOW_LAYER_TEXT)
                .contains("PRECEDENCE for a mixed comparison")
                .contains("resolve BOTH on the CALENDAR shape");
    }

    /**
     * Precedence must not swallow independent conditions. "Hasn't bought in the last 90 days but
     * spent over $10,000 in the prior year" is a rolling filter beside a calendar one, not a
     * comparison; forcing both to one shape would change the question rather than disambiguate it.
     */
    @Test
    @DisplayName("DATE_WINDOW precedence is scoped to compared windows, not independent conditions")
    void dateWindowLayer_precedenceDoesNotCollapseIndependentConditions() {
        assertThat(SystemPromptDefaults.DATE_WINDOW_LAYER_TEXT)
                .contains("applies only to windows being compared with each other")
                .contains("not a mixed comparison");
    }

    /** A 90-day range has no complete-calendar form, so the calendar branch must not claim it. */
    @Test
    @DisplayName("DATE_WINDOW layer treats a day-expressed range as always rolling")
    void dateWindowLayer_dayRangesAreAlwaysRolling() {
        assertThat(SystemPromptDefaults.DATE_WINDOW_LAYER_TEXT)
                .contains("A range expressed in days has no calendar form and is always rolling");
    }

    /**
     * Live-run regression (2026-09-03 gate). "…reopened within 7 days of completion this quarter?"
     * was answered on the last COMPLETE quarter (Apr-Jun) and returned no rows, where the ground
     * truth measures the current quarter to date. "This X" and "last X" are both fixed periods but
     * they are not the same period, and the earlier text collapsed them.
     */
    @Test
    @DisplayName("DATE_WINDOW layer reads this-X as the current period to date, never the previous one")
    void dateWindowLayer_thisPeriodIsCurrentToDate() {
        assertThat(SystemPromptDefaults.DATE_WINDOW_LAYER_TEXT)
                .contains("CURRENT-TO-DATE")
                .contains("NEVER the previous complete period")
                .contains("is 2026-07-01 to 2026-09-03, not April-June");
        assertThat(SystemPromptDefaults.DATE_WINDOW_LAYER_TEXT)
                .contains("are BOTH fixed, and they are NOT the same period");
    }

    /**
     * Live-run regression: "invoices issued during the last six months" was answered on a rolling
     * window because the calendar trigger list named only "in the last N".
     */
    @Test
    @DisplayName("DATE_WINDOW layer treats during/for the last N as a calendar span")
    void dateWindowLayer_duringAndForAreCalendarSpans() {
        assertThat(SystemPromptDefaults.DATE_WINDOW_LAYER_TEXT)
                .contains("during the last N months")
                .contains("for the last N months")
                .contains("are a calendar span");
    }

    /**
     * Live-run regression: a six-month calendar span resolved to 2026-01-01..2026-06-30 — six whole
     * months, but anchored to the start of the year instead of ending with the last complete one.
     */
    @Test
    @DisplayName("DATE_WINDOW layer anchors a multi-period span to the most recent complete period")
    void dateWindowLayer_spanEndsAtTheMostRecentCompletePeriod() {
        assertThat(SystemPromptDefaults.DATE_WINDOW_LAYER_TEXT)
                .contains("ENDING WITH THE MOST RECENT COMPLETE ONE")
                .contains("it ends in August, not in June")
                .contains("count back N periods from the last complete one");
    }

    /**
     * Live-run regression: "compared with last year" measured a complete 2025 against a partial
     * 2026 — the same-length rule broken by the comparison clause itself.
     */
    @Test
    @DisplayName("DATE_WINDOW layer forbids a complete prior year against a partial current one")
    void dateWindowLayer_comparedWithLastYearMatchesTheSpan() {
        assertThat(SystemPromptDefaults.DATE_WINDOW_LAYER_TEXT)
                .contains("never a complete prior year against an incomplete current one");
    }

    /** Only spans meant to be whole drop the partial period. */
    @Test
    @DisplayName("DATE_WINDOW layer scopes partial-period exclusion away from current-to-date ranges")
    void dateWindowLayer_partialExclusionDoesNotApplyToCurrentToDate() {
        assertThat(SystemPromptDefaults.DATE_WINDOW_LAYER_TEXT)
                .contains("Exclude the current partial period ONLY from a CALENDAR SPAN or a PRIOR COMPLETE period")
                .contains("must keep the current period");
    }

    /**
     * Raised on review of #1672: "over the past N" was listed under ROLLING and again under
     * CALENDAR SPAN, so the text classified one phrase as two shapes. A contract that contradicts
     * itself cannot be followed consistently, and the mixed-comparison precedence rule already
     * covers the case the second listing was reaching for.
     */
    @Test
    @DisplayName("DATE_WINDOW layer classifies each phrase under exactly one shape")
    void dateWindowLayer_doesNotClassifyOnePhraseAsTwoShapes() {
        String[] lines = SystemPromptDefaults.DATE_WINDOW_LAYER_TEXT.split("\n");
        long rolling = java.util.Arrays.stream(lines)
                .filter(l -> l.contains("ROLLING —"))
                .filter(l -> l.contains("over the past N"))
                .count();
        long span = java.util.Arrays.stream(lines)
                .filter(l -> l.contains("CALENDAR SPAN —"))
                .filter(l -> l.contains("over the past N"))
                .count();
        assertThat(rolling).isEqualTo(1);
        assertThat(span).isZero();
    }

    /** The shape is invisible in the figure, so it has to be named alongside the dates. */
    @Test
    @DisplayName("DATE_WINDOW layer requires the answer to name the shape, not only the dates")
    void dateWindowLayer_requiresTheShapeToBeNamed() {
        assertThat(SystemPromptDefaults.DATE_WINDOW_LAYER_TEXT).contains("whether it is rolling or calendar");
    }

    /**
     * Asking still has a place — a missing identifier has no correct default — so the layer must
     * not read as a blanket ban on clarifying questions, only as one for named ranges.
     */
    @Test
    @DisplayName("DATE_WINDOW layer answers named ranges but still allows asking for unreadable ones")
    void dateWindowLayer_answersNamedRangesButKeepsAskingForUnreadableOnes() {
        assertThat(SystemPromptDefaults.DATE_WINDOW_LAYER_TEXT)
                .contains("Apply these defaults instead of asking")
                .contains("\"recently\"");
    }

    /**
     * The caller-context block carrying the current date is appended <em>after</em> the assembled
     * layers, so a layer telling the model to look "above" for it would point away from the one
     * fact this contract exists to supply — and straight back to an invented date.
     */
    @Test
    @DisplayName("DATE_WINDOW layer locates the current date by block, never by direction")
    void dateWindowLayer_doesNotClaimTheCallerContextIsAbove() {
        assertThat(SystemPromptDefaults.DATE_WINDOW_LAYER_TEXT)
                .contains("stated in the authenticated user context block")
                .doesNotContain("caller context above");
    }

    /**
     * An unlabelled concrete date in the prompt is an anchor the model can carry into its answer,
     * which would reintroduce the invented "today" this layer removes.
     */
    @Test
    @DisplayName("DATE_WINDOW layer marks its worked dates as an illustration, not as today")
    void dateWindowLayer_labelsTheWorkedExampleAsIllustrative() {
        assertThat(SystemPromptDefaults.DATE_WINDOW_LAYER_TEXT)
                .contains("Illustration only, not today's dates")
                .contains("Always recompute from the current date you were actually given");
        // The worked example must show both shapes, since it is the only place their difference is
        // made concrete.
        assertThat(SystemPromptDefaults.DATE_WINDOW_LAYER_TEXT)
                .contains("2026-03-04 to 2026-09-03")
                .contains("2026-03-01 to 2026-08-31");
    }

    /**
     * Excluding the partial period can invert a range by itself: "this year" asked in January would
     * otherwise resolve to 1 January through the previous 31 December, which the analytics
     * endpoints reject with a 400.
     */
    @Test
    @DisplayName("DATE_WINDOW layer floors the complete-period rule so it cannot invert a range")
    void dateWindowLayer_guardsAgainstAnInvertedRange() {
        assertThat(SystemPromptDefaults.DATE_WINDOW_LAYER_TEXT)
                .contains("Never emit a range whose start date is after its end date")
                .contains("use the partial period up to the current date instead");
        // "This year" is now CURRENT-TO-DATE, which cannot invert at all; the guard therefore
        // belongs to the span shape, where too few complete periods is the remaining risk.
        assertThat(SystemPromptDefaults.DATE_WINDOW_LAYER_TEXT)
                .contains("asked when fewer than six complete months exist")
                .contains("say in the answer that the period is incomplete");
    }

    private double fallbackCount(String reason, String requested) {
        return meterRegistry
                .counter(RolePromptResolverImpl.METRIC_PROMPT_FALLBACK, "reason", reason, "requested", requested)
                .count();
    }
}
