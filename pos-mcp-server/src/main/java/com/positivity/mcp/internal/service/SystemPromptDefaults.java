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
     * <p>Whole complete periods, not a rolling window, is also the sounder business default —
     * a window ending mid-period compares a part-period against whole ones, which is precisely how
     * q17's comparison was corrupted.
     */
    static final String DATE_WINDOW_LAYER_TEXT = """
            Date-window contract:
            - Resolve every relative date range from the current date given in the caller context above. Never use a date you assume, recall, or infer from the conversation.
            - A relative range means whole calendar periods ending with the last COMPLETE period — never a rolling window ending today. With a current date of 2026-09-03, "the last six months" is 2026-03-01 to 2026-08-31, not 2026-03-04 to 2026-09-03.
            - Exclude the current, partial period. Including it compares a part-period against whole ones and overstates or understates every trend built on it.
            - "This year" and "year to date" mean 1 January of the current year through the end of the last complete month.
            - State the window you used, with explicit start and end dates, in the answer itself — not only in the tool arguments. A figure whose window is invisible cannot be checked.
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
