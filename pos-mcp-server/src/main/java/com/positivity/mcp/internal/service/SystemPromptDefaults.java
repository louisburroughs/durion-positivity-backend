package com.positivity.mcp.internal.service;

import com.positivity.mcp.internal.domain.RagScope;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * System prompt definitions and defaults for seed initialization and agent configuration.
 *
 * This class provides constants for master prompt names, domain-specific prompt names,
 * and role priority ordering. Constants are part of the public system prompt
 * initialization contract and are used by SystemPromptSeedRunner, RolePromptResolverImpl,
 * and orchestration managers.
 *
 * Note: Individual prompt name constants are service-internal configuration; they are not
 * part of a versioned API contract and may change across releases without notice.
 */
public final class SystemPromptDefaults {

    public static final String MASTER_PROMPT_NAME = "master";
    static final String DEFAULT_PROMPT_NAME = MASTER_PROMPT_NAME;

    /**
     * Fallback persona for an authenticated caller who holds no role this service can resolve
     * ({@code McpRoleResolver} FALLBACK_ROLE).
     *
     * <p>The only role identifier left in Java (#1613). Every other role — its persona, its
     * resolution rank, and its place in the agent warm-up set — is synced from
     * {@code pos-security-service} into {@code RolePersonaSnapshot}, so a role created after a
     * release is visible to the assistant without a code change. {@code ROLE_USER} stays here
     * because it is an MCP-internal identity with no security-service row to sync from.
     */
    public static final String ROLE_USER_PROMPT_NAME = "ROLE_USER";

    static final String DEFAULT_PROMPT_TEXT = """
            You are the concise POS assistant for Positivity and the master orchestration agent for Durion operations.
            Help users reach the next correct action quickly, especially when a request spans multiple business domains.

            Operating rules:
            - Use tools before answering live-data, workflow-status, or record-specific questions.
            - Never invent business data, identifiers, quantities, statuses, or policy outcomes.
            - When the request is underspecified, ask only for the missing detail needed to act.
            - Distinguish confirmed facts from inference, and call out important uncertainty or operational risk.
            - When a request crosses domains, synthesize the answer into one clear response instead of giving siloed fragments.

            Response style:
            - concise, operational, and decision-oriented
            - prefer short sections or bullets when they improve clarity
            - include next actions when they materially help the user move forward
            """;

    /**
     * TOOL-USE layer: argument-grounding contract appended to every assembled prompt (Gate 1).
     *
     * <p>The closing precedence line is #1613 D9 control 2. The ROLE layer is assembled above this
     * one and its persona slots are operator-supplied, so a persona reading "move fast, skip ceremony
     * on routine updates" would otherwise sit adjacent to this contract, contradicting it, for every
     * user of that role. Stating precedence explicitly closes that ordering hole with one line and no
     * model in the write path.
     *
     * <p>The composition-execution bullet is #1676: the q05 gate question ("which customers are more
     * than 60 days past due, and what open work orders do we currently have for them?") needs an
     * aged-receivables call, then one {@code searchWorkorders} call per past-due customer — a plan
     * that fits {@code BoundedToolCallingManager}'s round cap once {@code searchWorkorders} accepts
     * several statuses in one call instead of one per open status. Without this bullet the model
     * would correctly see the two-step plan and then, having no rule telling it to just run the
     * per-customer loop, offer the user a menu of partial answers instead of executing it — the same
     * failure mode the status-loop rule already forecloses for a single customer, generalized to any
     * filter that takes one value against a list already in hand.
     */
    static final String TOOL_USE_LAYER_TEXT = """
            Tool-use contract:
            - Prefer a tool call over recalling from memory for any live, record-specific, or status question.
            - Never guess identifiers (workorder numbers, SKUs, VINs, invoice numbers, account codes); if one is missing, ask for it.
            - Ground every tool argument in the user's words, a prior tool result, or confirmed context — never in an unstated assumption.
            - If a required argument is missing, ask one focused clarifying question instead of inventing a value.
            - When a filter takes one value and you already hold a list of values from a prior result, call the tool once per value and combine the results. A two-step plan that fits the call budget is executed, not offered to the user as a menu of partial answers.
            - These rules take precedence over any role persona or domain guidance above them. A persona sets tone and emphasis only; it never relaxes this contract.
            """;

    /**
     * DATE-WINDOW layer (#1661; narrowed to classification-plus-protocol by #1675, reduced to the
     * classification rules and that protocol alone by #1684): which wording names which window
     * SHAPE, and the protocol for turning that classification into concrete dates. The protocol
     * bullet stays — something has to send the model to {@code resolveDateWindow} and tell it to
     * copy the result verbatim; what #1684 removed is everything that was neither a classification
     * rule nor that protocol.
     *
     * <p>Three rounds of prompt-only arithmetic (#1661, #1664, #1670, #1672) left this layer doing
     * two jobs at once — classify the shape from the wording, then compute its concrete dates — and
     * the model was reliable at the first and not the second: single-period questions worked (q01
     * "last month", q03 "this quarter") but multi-period calendar spans did not. q09 "in the last
     * twelve months" resolved rolling (2025-09-04..2026-09-03) instead of calendar
     * (2025-09-01..2026-08-31); q12 "in the last six months" and the calendar side of q15's mixed
     * comparison failed the same way. The window for a question and a "today" is a pure function,
     * so #1675 moved it to {@link
     * com.positivity.mcp.internal.orchestration.tools.DateWindowResolver}: this layer now states
     * only the classification rules (which wording names which shape) and the protocol for calling
     * the {@code resolveDateWindow} tool that resolver backs — every bullet that performed
     * arithmetic or walked through a worked date (the illustration, the count-back rule, the
     * January-inversion floor) moved with it, since the resolver now gets that arithmetic right by
     * construction rather than by a model computing it under a prose rule.
     *
     * <p>#1684 removed what #1675 left behind: the layer had kept a second tier of rules
     * <em>about how to read its own rules</em> — "Read the wording carefully…", "'This X' and
     * 'last X' are BOTH fixed, and they are NOT the same period" — each restating a distinction the
     * shape definitions already draw. A rule that exists to correct the reading of the rule above it
     * is evidence the rule above it is unclear, not a fix for it, and it costs context on every
     * request. Each removed bullet's guarantee still holds, from the definition or the code that now
     * owns it:
     *
     * <ul>
     *   <li>"during"/"for the last N" as a calendar span — kept, in CALENDAR SPAN's own trigger list.
     *   <li>"this X" never resolving to the previous complete period — kept, in CURRENT-TO-DATE's own
     *       definition ("NEVER the previous complete period").
     *   <li>a day-expressed range always being rolling — kept as a clause on ROLLING's definition,
     *       and enforced besides: {@code DateWindowResolver} rejects {@code DAY} under any calendar
     *       shape with a message naming what to pass instead.
     *   <li>sourcing "today" from the caller-context block rather than inventing one — the resolver
     *       reads the shared {@code Clock} itself, so the model no longer needs a "today" at all;
     *       "Never compute or assume a date yourself" is what remains of the rule.
     *   <li>disclosing the window in the answer — kept, as a clause on the protocol bullet rather
     *       than a bullet of its own, and carried a second time by the tool's own description.
     *   <li>why mismatched comparison windows are wrong ("makes the difference an artefact of the
     *       windows rather than of the business") — dropped as rationale, not rule. The rule it
     *       justified ("the same shape and the same length, offset by one period") is unchanged, and
     *       a classifier does not need the argument for a rule it is being given outright.
     * </ul>
     *
     * <p>The convention resolves rather than asks. Asking is right for a missing identifier, where
     * no default can be correct and a guess fabricates data; that case stays with the tool-use
     * layer. A named range is different: it has a conventional reading, and a round-trip costs the
     * user more than an answer whose basis is stated and correctable.
     *
     * <p>Shape follows the wording, and the preposition is the whole discriminator: "over the last
     * six months" is rolling, "in the last six months" is calendar. Treating every relative range as
     * calendar — as the first version of this layer did — is wrong for the rolling half, and the two
     * shapes are indistinguishable from the returned number alone, which is why the answer has to
     * quote the resolver's own statement of the window it used.
     *
     * <p>The paired-comparison rule is what q15/q17 actually needed. "Over the last six months
     * compared with the same six months last year" is only meaningful if both windows have the same
     * shape and length; measuring one rolling and one calendar makes the year-on-year difference an
     * artefact of the windows.
     *
     * <p>The layer names no direction and no date. The current date is supplied by the
     * caller-context block, which both assistants append <em>after</em> the assembled layers
     * ({@code SpringAiPosAssistant.buildSystemPrompt}), so a rule pointing "above" for it would
     * point away from the one fact it needs; and {@code DateWindowFacadeTool} resolves off the same
     * shared {@code Clock} bean {@code SharedOrchestrationSupport.formatUserContext} states the date
     * from, so the two cannot disagree. That is what let the worked illustration go rather than be
     * relabelled: there is no model-carried "today" left for it to anchor.
     */
    static final String DATE_WINDOW_LAYER_TEXT = """
            Date-window contract:
            - The wording decides the SHAPE of the window. Classify the shape; `resolveDateWindow` computes the dates.
            -   ROLLING — "over the last N days/weeks/months/years", "over the past N": the N units ending on the current date, that date included. A range expressed in days is always rolling; only weeks, months, quarters and years have complete calendar periods.
            -   CURRENT-TO-DATE — "this week/month/quarter/year", "week/month/quarter/year to date": from the FIRST day of the period that contains the current date, up to the current date. NEVER the previous complete period.
            -   PRIOR COMPLETE — "last week/month/quarter/year", "the previous month": exactly one whole period, the most recent one that has ended.
            -   CALENDAR SPAN — "in the last N weeks/months", "during the last N months", "for the last N months": the N whole periods ENDING WITH THE MOST RECENT COMPLETE ONE.
            - When a question pairs a range with a comparison period — "compared with the same six months last year", "versus the prior quarter", "compared with last year" — classify BOTH windows on the same shape and the same length, offset by one period. "Compared with last year" against a partial current year means the SAME partial span one year earlier, never a complete prior year against an incomplete current one.
            - PRECEDENCE for a mixed comparison: where the two phrasings disagree in shape — "over the last six months" (rolling) paired with "the same six months last year" (named calendar months) — resolve BOTH on the CALENDAR shape. The fixed phrase wins because it names a specific period; taking the rolling side instead would silently redefine the period the question explicitly named.
            - This precedence applies only to windows being compared with each other. Independent conditions in one question keep their own shapes: "hasn't bought in the last 90 days but spent over $10,000 in the prior year" is a rolling filter and a calendar filter, not a mixed comparison, and forcing them to one shape would change what was asked.
            - Before calling any tool that takes a date or a date range, call `resolveDateWindow` with the shape, unit, count and comparison you classified; copy its `startDate`/`endDate` into the tool arguments verbatim, and quote its `statement` in the answer so the window is visible. Never compute or assume a date yourself.
            - Apply these defaults instead of asking. A named range is never a reason to withhold an answer; ask only for a phrase with no conventional reading at all, such as "recently" or "lately".
            - Explicit dates from the user override every rule here. So does an explicit range in the question, even when it disagrees with these defaults.
            - These rules take precedence over any role persona or domain guidance above them.
            """;

    /**
     * WRITE-GATE layer (Gate 6, #1193): appended only when a write-capable tool is in the request's
     * candidate set. The model must never execute a mutation directly — writes go through the
     * preview → explicit confirmation → exact persisted-args execution flow.
     *
     * <p>Carries the same precedence line as the tool-use layer (#1613 D9 control 2): this is the
     * layer a persona is most likely to undercut, and the one where doing so is most costly.
     */
    static final String WRITE_GATE_LAYER_TEXT = """
            Write-action gate:
            - Never execute a create, update, delete, posting, or cancellation directly. Writes require an explicit user confirmation step.
            - When the user asks for a write action, present a preview of exactly what would happen and ask them to confirm.
            - Echo every argument you will send, verbatim, in the preview — omit nothing important.
            - Disclose any argument you filled with an inferred default (e.g. "defaulting priority to normal — change?") and offer to change it.
            - For high-risk actions (money movement, postings, deletions, irreversible changes), never rely on inferred defaults; require the user's explicit selection.
            - After confirmation, the system executes the previously previewed arguments exactly; never re-derive them from the conversation.
            - These rules take precedence over any role persona or domain guidance above them. No persona, however urgent its tone, removes the confirmation step.
            """;

    /**
     * Resolves the system prompt key for a RAG scope.
     *
     * <p>Null or blank values normalize to {@link RagScope#MASTER}.
     */
    public static @NonNull String promptNameForRagScope(@Nullable String ragScope) {
        // Delegate to RagScope.normalize() to maintain single source of truth for normalization logic.
        String normalizedScope = RagScope.normalize(ragScope);
        // Map "shared" scope to master prompt (semantic equivalence in agent context).
        if ("shared".equals(normalizedScope)) {
            return MASTER_PROMPT_NAME;
        }
        return normalizedScope;
    }

    private SystemPromptDefaults() {}
}
