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

    /** TOOL-USE layer: argument-grounding contract appended to every assembled prompt (Gate 1). */
    static final String TOOL_USE_LAYER_TEXT = """
            Tool-use contract:
            - Prefer a tool call over recalling from memory for any live, record-specific, or status question.
            - Never guess identifiers (workorder numbers, SKUs, VINs, invoice numbers, account codes); if one is missing, ask for it.
            - Ground every tool argument in the user's words, a prior tool result, or confirmed context — never in an unstated assumption.
            - If a required argument is missing, ask one focused clarifying question instead of inventing a value.
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
