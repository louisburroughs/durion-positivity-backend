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
