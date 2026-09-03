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
     */
    static final String TOOL_USE_LAYER_TEXT = """
            Tool-use contract:
            - Prefer a tool call over recalling from memory for any live, record-specific, or status question.
            - Never guess identifiers (workorder numbers, SKUs, VINs, invoice numbers, account codes); if one is missing, ask for it.
            - Ground every tool argument in the user's words, a prior tool result, or confirmed context — never in an unstated assumption.
            - If a required argument is missing, ask one focused clarifying question instead of inventing a value.
            - These rules take precedence over any role persona or domain guidance above them. A persona sets tone and emphasis only; it never relaxes this contract.
            """;

    /**
     * DATE-WINDOW layer (#1661): how a relative date range resolves to concrete dates.
     *
     * <p>Four of the twelve Wave 2 gate questions failed on this alone. The model, never told the
     * current date, invented one and measured a rolling window from it, while the ground truth used
     * whole calendar periods. Both were then internally consistent and disagreed anyway — q17 read
     * V1 Evergreen as +7.43 % against an expected +12.00 % purely because a not-yet-due bill sat in
     * the rolling window's tail, and nowhere in either answer was the window visible enough to spot
     * the divergence.
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
     * name the shape as well as the dates.
     *
     * <p>The paired-comparison rule is what q15/q17 actually needed. "Over the last six months
     * compared with the same six months last year" is only meaningful if both windows have the same
     * shape and length; measuring one rolling and one calendar makes the year-on-year difference an
     * artefact of the windows.
     *
     * <p>Three details this text has to get exactly right, each a way the contract could defeat
     * itself (raised on review of #1664):
     *
     * <ul>
     *   <li>The current date is supplied by the caller-context block, which both assistants append
     *       <em>after</em> the assembled layers ({@code SpringAiPosAssistant.buildSystemPrompt}), so
     *       this layer must not describe it as being "above". It names the block instead of a
     *       direction, which stays true wherever the block is placed.
     *   <li>The worked example is labelled as an illustration. An unlabelled concrete date is an
     *       anchor the model can carry into its answer, which would reintroduce exactly the invented
     *       "today" this layer exists to remove.
     *   <li>Requiring whole periods can invert a range on its own: a six-month CALENDAR SPAN asked
     *       when fewer than six complete months exist resolves to a start after its end. The
     *       analytics endpoints reject {@code endDate} before {@code startDate} with a 400, so the
     *       rule carries an explicit floor rather than relying on the model to notice. This no
     *       longer applies to "this year", which is CURRENT-TO-DATE and ends on the current date,
     *       so it cannot invert.
     * </ul>
     */
    static final String DATE_WINDOW_LAYER_TEXT = """
            Date-window contract:
            - Resolve every relative date range from the current date stated in the authenticated user context block. Never use a date you assume, recall, or infer from the conversation.
            - The wording decides the SHAPE of the window, and the shapes give different answers:
            -   ROLLING — "over the last N days/weeks/months/years", "over the past N": the N units ending on the current date, that date included.
            -   CURRENT-TO-DATE — "this week/month/quarter/year", "week/month/quarter/year to date": from the FIRST day of the period that contains the current date, up to the current date. NEVER the previous complete period. "This quarter" on 2026-09-03 is 2026-07-01 to 2026-09-03, not April-June.
            -   PRIOR COMPLETE — "last week/month/quarter/year", "the previous month": exactly one whole period, the most recent one that has ended. "Last month" on 2026-09-03 is 2026-08-01 to 2026-08-31.
            -   CALENDAR SPAN — "in the last N weeks/months", "during the last N months", "for the last N months": the N whole periods ENDING WITH THE MOST RECENT COMPLETE ONE. On 2026-09-03 "the last six months" is 2026-03-01 to 2026-08-31 — it ends in August, not in June, and not in the current partial month.
            - Read the wording carefully: "over the last six months" is rolling; "in the last six months", "during the last six months" and "for the last six months" are a calendar span. They are different questions and must not be answered alike.
            - "This X" and "last X" are BOTH fixed, and they are NOT the same period. "This X" includes the current date and is deliberately partial; "last X" is the whole period before it. Answering "this quarter" with the previous complete quarter reports a period the user did not ask about, and can return nothing at all when the data lies in the current one.
            - Illustration only, not today's dates: were the current date 2026-09-03, "over the last six months" would be 2026-03-04 to 2026-09-03; "in the last six months" 2026-03-01 to 2026-08-31; "this quarter" 2026-07-01 to 2026-09-03; "last month" 2026-08-01 to 2026-08-31. Always recompute from the current date you were actually given.
            - Exclude the current partial period ONLY from a CALENDAR SPAN or a PRIOR COMPLETE period. A CURRENT-TO-DATE range is partial by definition and must keep the current period; a ROLLING range ends on the current date and excludes nothing.
            - A multi-period span always ends with the most recent COMPLETE period. Do not anchor it to the start of the year, to a quarter boundary, or to any other convenient edge: count back N periods from the last complete one.
            - Never emit a range whose start date is after its end date. Where excluding the partial period would leave an inverted or empty range — "in the last six months" asked when fewer than six complete months exist — use the partial period up to the current date instead, and say in the answer that the period is incomplete.
            - A range expressed in days has no calendar form and is always rolling; only weeks, months, quarters and years have complete calendar periods.
            - When a question pairs a range with a comparison period — "compared with the same six months last year", "versus the prior quarter", "compared with last year" — measure BOTH on the same shape and the same length, offset by one period. "Compared with last year" against a partial current year means the SAME partial span one year earlier, never a complete prior year against an incomplete current one. Comparing a rolling window against a calendar one, or a longer period against a shorter, makes the difference an artefact of the windows rather than of the business.
            - PRECEDENCE for a mixed comparison: where the two phrasings disagree in shape — "over the last six months" (rolling) paired with "the same six months last year" (named calendar months) — resolve BOTH on the CALENDAR shape. The fixed phrase wins because it names a specific period; taking the rolling side instead would silently redefine the period the question explicitly named.
            - This precedence applies only to windows being compared with each other. Independent conditions in one question keep their own shapes: "hasn't bought in the last 90 days but spent over $10,000 in the prior year" is a rolling filter and a calendar filter, not a mixed comparison, and forcing them to one shape would change what was asked.
            - State the window you used in the answer itself, with explicit start and end dates and whether it is rolling or calendar — not only in the tool arguments. A figure whose window is invisible cannot be checked, and the two shapes are indistinguishable from the number alone.
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
