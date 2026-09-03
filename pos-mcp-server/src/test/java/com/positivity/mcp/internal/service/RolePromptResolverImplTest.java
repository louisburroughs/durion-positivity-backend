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

    // ─── TOOL-USE layer (#1676 composition-execution rule) ──────────────────

    /**
     * q05 ("which customers are more than 60 days past due, and what open work orders do we
     * currently have for them?") needs an aged-receivables call followed by one {@code
     * searchWorkorders} call per past-due customer — a plan that fits the round cap once status
     * filtering is one call, not six. Without this bullet the model correctly saw that two-step plan
     * and then offered the user a menu of partial answers instead of just running it. Pinned as a
     * literal substring, matching the WRITE_GATE layer's pin style (see
     * {@code WriteGatePromptLayerTest}), so a future edit that quietly drops the rule fails a test.
     */
    @Test
    @DisplayName("assemble includes the TOOL_USE per-value-loop composition rule (#1676)")
    void assemble_includesToolUseCompositionRule() {
        when(systemPromptRepository.findByName(MASTER_NAME))
                .thenReturn(Optional.of(buildPrompt(MASTER_NAME, "master content")));

        var assembled = resolver.assemble("ROLE_TECHNICIAN", "master", false);

        assertThat(assembled.layers()).contains("TOOL_USE");
        assertThat(assembled.text())
                .contains("call the tool once per value and combine the results")
                .contains("not offered to the user as a menu of partial answers");
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
     * A calendar span is defined as ending with the last complete period, and the answer has to
     * disclose the window it used — the number alone cannot show which window produced it. Which
     * dates that period actually is (and dropping the current partial one) is #1675 resolver
     * arithmetic now, not a prompt rule.
     */
    @Test
    @DisplayName(
            "DATE_WINDOW layer defines calendar spans as ending with the last complete period and requires disclosure")
    void dateWindowLayer_calendarSpanEndsAtTheLastCompletePeriodAndDisclosesTheWindow() {
        assertThat(SystemPromptDefaults.DATE_WINDOW_LAYER_TEXT)
                .contains("ENDING WITH THE MOST RECENT COMPLETE ONE")
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
        // ROLLING ends on the current date, that date included — no separate exclusion rule needed.
        assertThat(SystemPromptDefaults.DATE_WINDOW_LAYER_TEXT)
                .contains("the N units ending on the current date, that date included");
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
                .contains("classify BOTH windows on the same shape and the same length, offset by one period");
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
                .contains("NEVER the previous complete period");
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
     * #1675: the counting-back arithmetic this bullet used to spell out now lives in
     * DateWindowResolver; the layer states only the shape's definition and routes the arithmetic
     * through the resolver tool.
     */
    @Test
    @DisplayName(
            "DATE_WINDOW layer anchors a calendar span to the most recent complete period, by definition not by counting")
    void dateWindowLayer_spanEndsAtTheMostRecentCompletePeriod() {
        assertThat(SystemPromptDefaults.DATE_WINDOW_LAYER_TEXT)
                .contains("ENDING WITH THE MOST RECENT COMPLETE ONE")
                .contains("call `resolveDateWindow`")
                .contains("Never compute a date yourself.");
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

    /**
     * Only spans meant to be whole drop the partial period; CURRENT-TO-DATE stays partial by
     * definition. #1675 moved the separate "exclude the partial period" rule this test used to pin
     * into DateWindowResolver's construction (CURRENT-TO-DATE's own definition already runs up to
     * today, never past it), so the layer states it once, in the shape's own definition, rather
     * than as a second rule that could drift from it.
     */
    @Test
    @DisplayName("DATE_WINDOW layer keeps CURRENT-TO-DATE partial by definition, with no separate exclusion rule")
    void dateWindowLayer_currentToDateStaysPartialByDefinition() {
        assertThat(SystemPromptDefaults.DATE_WINDOW_LAYER_TEXT)
                .contains("up to the current date")
                .doesNotContain("Exclude the current partial period ONLY from");
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
     * #1675: the worked illustration used to embed a concrete "today" the model could carry into
     * its answer, reintroducing exactly the invented date this layer exists to remove. Now that the
     * arithmetic lives in DateWindowResolver, the layer needs no worked example at all — asserting
     * that it carries no concrete calendar date is the regression guard the old illustration test
     * pinned the opposite way (that the illustration existed and was labelled).
     */
    @Test
    @DisplayName("DATE_WINDOW layer carries no concrete calendar date of its own")
    void dateWindowLayer_carriesNoConcreteCalendarDateOfItsOwn() {
        assertThat(SystemPromptDefaults.DATE_WINDOW_LAYER_TEXT).doesNotContainPattern("[0-9]{4}-[0-9]{2}-[0-9]{2}");
    }

    /**
     * #1675: the January-inversion floor this test used to pin — "exclude the partial period would
     * invert a range, so fall back to the partial period instead" — performed arithmetic the model
     * was not reliable at. DateWindowResolver's CALENDAR_SPAN construction (subtract whole periods
     * from the current period's start) cannot invert by construction, so the floor moved there
     * rather than staying here as a rule to apply. The layer's remaining job is to route every date
     * computation through the resolver tool, never to compute or guard one itself.
     */
    @Test
    @DisplayName("DATE_WINDOW layer names resolveDateWindow and routes every date computation through it")
    void dateWindowLayer_routesEveryDateComputationThroughResolveDateWindow() {
        assertThat(SystemPromptDefaults.DATE_WINDOW_LAYER_TEXT)
                .contains("call `resolveDateWindow`")
                .contains("copy its `startDate`/`endDate`")
                .contains("Never compute a date yourself.");
    }

    private double fallbackCount(String reason, String requested) {
        return meterRegistry
                .counter(RolePromptResolverImpl.METRIC_PROMPT_FALLBACK, "reason", reason, "requested", requested)
                .count();
    }
}
