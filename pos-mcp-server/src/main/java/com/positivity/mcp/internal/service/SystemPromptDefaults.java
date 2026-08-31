package com.positivity.mcp.internal.service;

import com.positivity.mcp.internal.domain.RagScope;
import java.util.List;
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

    // SQL-seeded security roles from pos-security-service
    // (R__seed_reference_security.sql)
    static final String ROLE_ADMIN_PROMPT_NAME = "ROLE_ADMIN";
    static final String ROLE_SYSTEM_ADMINISTRATOR_PROMPT_NAME = "ROLE_SYSTEM_ADMINISTRATOR";
    static final String ROLE_ACCOUNT_MANAGER_PROMPT_NAME = "ROLE_ACCOUNT_MANAGER";
    static final String ROLE_ACCOUNTING_ASSOCIATE_PROMPT_NAME = "ROLE_ACCOUNTING_ASSOCIATE";
    static final String ROLE_LOCATION_MANAGER_PROMPT_NAME = "ROLE_LOCATION_MANAGER";
    static final String ROLE_SERVICE_ADVISOR_PROMPT_NAME = "ROLE_SERVICE_ADVISOR";
    static final String ROLE_DISPATCHER_PROMPT_NAME = "ROLE_DISPATCHER";
    static final String ROLE_TECHNICIAN_PROMPT_NAME = "ROLE_TECHNICIAN";
    static final String ROLE_CUSTOMER_PROMPT_NAME = "ROLE_CUSTOMER";
    static final String ROLE_SELF_SERVICE_CUSTOMER_PROMPT_NAME = "ROLE_SELF_SERVICE_CUSTOMER";
    // Fallback persona for authenticated callers with no higher-priority role (McpRoleResolver
    // FALLBACK_ROLE). Internal-only interface: ROLE_CUSTOMER / ROLE_SELF_SERVICE_CUSTOMER are
    // intentionally NOT seeded as personas (Gate 1).
    static final String ROLE_USER_PROMPT_NAME = "ROLE_USER";

    static final List<String> MCP_ROLE_PRIORITY = List.of(
            ROLE_SYSTEM_ADMINISTRATOR_PROMPT_NAME,
            ROLE_ADMIN_PROMPT_NAME,
            ROLE_LOCATION_MANAGER_PROMPT_NAME,
            ROLE_ACCOUNT_MANAGER_PROMPT_NAME,
            ROLE_ACCOUNTING_ASSOCIATE_PROMPT_NAME,
            ROLE_SERVICE_ADVISOR_PROMPT_NAME,
            ROLE_DISPATCHER_PROMPT_NAME,
            ROLE_TECHNICIAN_PROMPT_NAME,
            ROLE_CUSTOMER_PROMPT_NAME,
            ROLE_SELF_SERVICE_CUSTOMER_PROMPT_NAME);

    /**
     * Canonical set of role identifiers that must be pre-built by both session managers (Gate 2A,
     * issue #639). Equal to {@link #MCP_ROLE_PRIORITY} plus the {@code ROLE_USER} fallback, so a
     * caller resolving to any priority role — or the fallback — hits a warm agent. Previously
     * preload was driven only by configured tool assignments, which omitted ROLE_TECHNICIAN and
     * ROLE_USER.
     */
    public static final List<String> PRELOADABLE_ROLE_IDENTIFIERS = buildPreloadableRoleIdentifiers();

    private static List<String> buildPreloadableRoleIdentifiers() {
        var roles = new java.util.LinkedHashSet<>(MCP_ROLE_PRIORITY);
        roles.add(ROLE_USER_PROMPT_NAME);
        return List.copyOf(roles);
    }

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
