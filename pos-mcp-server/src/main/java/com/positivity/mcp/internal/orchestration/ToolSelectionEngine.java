package com.positivity.mcp.internal.orchestration;

import com.positivity.mcp.internal.config.ScopeGraphProperties.Consumer;
import com.positivity.mcp.internal.domain.QuestionTags;
import com.positivity.mcp.internal.domain.TagSeeds;
import com.positivity.mcp.internal.domain.ToolMetadata;
import com.positivity.mcp.internal.domain.ToolSelectionContext;
import com.positivity.mcp.internal.domain.WorkflowState;
import com.positivity.mcp.internal.orchestration.agent.MasterAgentRegistry;
import com.positivity.mcp.internal.orchestration.tools.DateWindowFacadeTool;
import com.positivity.mcp.internal.orchestration.tools.ExaWebSearchTool;
import com.positivity.mcp.internal.orchestration.tools.GlossaryFacadeTool;
import com.positivity.mcp.internal.orchestration.tools.InventoryFacadeTool;
import com.positivity.mcp.internal.orchestration.tools.OrderFacadeTool;
import com.positivity.mcp.internal.scopegraph.ScopeConsumers;
import com.positivity.mcp.internal.scopegraph.ScopeResolver;
import com.positivity.mcp.internal.scopegraph.ScopeSet;
import com.positivity.mcp.internal.scopegraph.ScopeSet.ScopeTool;
import com.positivity.mcp.internal.service.ToolRegistryService;
import com.positivity.mcp.internal.service.ToolRegistryService.CandidateSelection;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ToolSelectionEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger(ToolSelectionEngine.class);

    private final MasterAgentRegistry toolRegistry;
    private final DateWindowFacadeTool dateWindowFacadeTool;
    private final GlossaryFacadeTool glossaryFacadeTool;
    private final ExaWebSearchTool exaWebSearchTool;
    private final InventoryFacadeTool inventoryFacadeTool;
    private final OrderFacadeTool orderFacadeTool;
    private final SharedOrchestrationSupport sharedOrchestrationSupport;

    @Nullable
    private final ToolRegistryService toolRegistryService;

    private final int candidateToolLimit;

    /** ADR-0069: absent in hand-built constructions and in contexts without the scope-graph beans. */
    @Nullable
    private volatile ScopeResolver scopeResolver;

    /** ADR-0069 §6: the consumer switch; absent means no consumer acts, whatever the scope says. */
    @Nullable
    private volatile ScopeConsumers scopeConsumers;

    /**
     * ADR-0068 §1: the per-turn tagging step. Absent in hand-built constructions, where {@link #tag}
     * answers with the heuristic tagger below (no provider is ever called).
     */
    @Nullable
    private volatile TaggingService taggingService;

    /** ADR-0068 §2: the heuristic tagger the legacy overloads and the unwired engine tag with. */
    private volatile HeuristicQuestionTagger heuristicTagger = HeuristicQuestionTagger.withDefaultCatalog();

    public ToolSelectionEngine(
            @NonNull MasterAgentRegistry toolRegistry,
            @NonNull DateWindowFacadeTool dateWindowFacadeTool,
            @NonNull GlossaryFacadeTool glossaryFacadeTool,
            @NonNull ExaWebSearchTool exaWebSearchTool,
            @NonNull InventoryFacadeTool inventoryFacadeTool,
            @NonNull OrderFacadeTool orderFacadeTool,
            @Nullable ToolRegistryService toolRegistryService,
            @NonNull SharedOrchestrationSupport sharedOrchestrationSupport,
            @Value("${mcp.agent.candidate-tool-limit:8}") int candidateToolLimit) {
        this.toolRegistry = toolRegistry;
        this.dateWindowFacadeTool = dateWindowFacadeTool;
        this.glossaryFacadeTool = glossaryFacadeTool;
        this.exaWebSearchTool = exaWebSearchTool;
        this.inventoryFacadeTool = inventoryFacadeTool;
        this.orderFacadeTool = orderFacadeTool;
        this.toolRegistryService = toolRegistryService;
        this.sharedOrchestrationSupport = sharedOrchestrationSupport;
        this.candidateToolLimit = Math.max(1, candidateToolLimit);
    }

    /**
     * ADR-0068 §1: the one tagging call of a chat turn. Both session managers call it once, ahead of
     * {@code isSimpleChat} and {@code routeTier}, and pass the record on to every consumer; nothing
     * downstream re-derives a tag from the message. Without a wired {@link TaggingService} (hand-built
     * engines, contexts without the tagging beans) the heuristic tagger answers and no provider is
     * called.
     */
    public @NonNull QuestionTags tag(@NonNull String message) {
        TaggingService service = taggingService;
        return service == null ? heuristicTagger.tag(message) : service.tag(message);
    }

    /** The heuristic record alone, for the overloads that predate the seam: never a provider call. */
    private @NonNull QuestionTags heuristicTags(@NonNull String message) {
        TaggingService service = taggingService;
        return service == null ? heuristicTagger.tag(message) : service.heuristicOnly(message);
    }

    /**
     * The pre-ADR-0068 session-less entry point: tags with the heuristics alone and selects on them.
     * Kept for callers and tests that never tagged; the managers call {@link #tag} and the {@link
     * QuestionTags} overloads.
     */
    public @NonNull ToolSelectionResult selectRoleTools(
            @NonNull String role, @NonNull Set<String> permissionCodes, @NonNull String message) {
        return selectRoleTools(role, permissionCodes, message, heuristicTags(message));
    }

    /**
     * Gate 2C: workflow-state-aware selection. {@code workflowState} is an explicit input so a
     * session-bearing caller can supply the persisted {@code NltiSession} state rather than relying
     * on message-text heuristics. Pre-ADR-0068 shape: tags with the heuristics alone.
     */
    public @NonNull ToolSelectionResult selectRoleTools(
            @NonNull String role,
            @NonNull Set<String> permissionCodes,
            @NonNull String message,
            @NonNull WorkflowState workflowState) {
        return selectRoleTools(role, permissionCodes, message, workflowState, heuristicTags(message));
    }

    /**
     * ADR-0068 §3.3: a session-less caller's workflow state is the {@code workflow_state} tag's acting
     * value; a persisted {@code NltiSession} state goes through the {@link WorkflowState} overload and
     * is never overridden. An absent record ({@link QuestionTags#none()}: warm-up, a caller that never
     * tagged) behaves exactly as {@code off}: the heuristic answers stand in, without a provider call.
     */
    public @NonNull ToolSelectionResult selectRoleTools(
            @NonNull String role,
            @NonNull Set<String> permissionCodes,
            @NonNull String message,
            @NonNull QuestionTags tags) {
        QuestionTags acting = tags.isNone() ? heuristicTags(message) : tags;
        return selectRoleTools(role, permissionCodes, message, acting.workflowState(), acting);
    }

    /**
     * The selection every path ends in. {@code tags} is the turn's record from {@link #tag}; every
     * decision this class used to take from the message (workflow state for session-less callers,
     * keyword-added facades) is read from it (ADR-0068 §1).
     */
    public @NonNull ToolSelectionResult selectRoleTools(
            @NonNull String role,
            @NonNull Set<String> permissionCodes,
            @NonNull String message,
            @NonNull WorkflowState workflowState,
            @NonNull QuestionTags tags) {
        // ADR-0068: an absent record behaves exactly as mode off (the heuristic answers, no provider).
        QuestionTags acting = tags.isNone() ? heuristicTags(message) : tags;
        // ADR-0069 §5: the scope is resolved here, once the workflow state is known and before tool
        // ranking, because this is the one selection entry point both session managers call. The
        // consumers that read it here are the facade slot step (§6) and the lexicon lookups (§6 row
        // 3), each only when enforced. ADR-0068 spec §2.7: the acting tags add their seeds.
        ScopeSet scope = resolveScope(message, permissionCodes, workflowState, acting.tagSeeds());
        RankedRoleTools ranked = roleToolsForMessage(role, permissionCodes, message, workflowState, scope, acting);
        List<Object> fallbackTools = sharedOrchestrationSupport.mergeTools(
                toolRegistry.resolveMasterTools(), fallbackToolsForTags(acting, ranked.gatedToolNames(), scope));
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(
                    "MCP shared tool selection role={} permissionCodes={} workflowState={} roleTools={} scopeAddedTools={} fallbackTools={} queryPreview=\"{}\"",
                    role,
                    permissionCodes,
                    workflowState,
                    sharedOrchestrationSupport.toolNames(ranked.tools()),
                    ranked.scopeAddedTools(),
                    sharedOrchestrationSupport.toolNames(fallbackTools),
                    sharedOrchestrationSupport.preview(message));
        }
        return new ToolSelectionResult(ranked.tools(), fallbackTools, workflowState, scope, ranked.scopeAddedTools());
    }

    /**
     * ADR-0069 §9: wires the scope resolver. Setter-injected and optional so the many hand-built
     * constructions of this class, and contexts without the scope-graph beans, keep resolving nothing.
     */
    @Autowired(required = false)
    public void setScopeResolver(@Nullable ScopeResolver scopeResolver) {
        this.scopeResolver = scopeResolver;
    }

    /**
     * ADR-0069 §6: wires the consumer switch. Setter-injected and optional for the same reason as
     * the resolver; without it the facade slot step never runs.
     */
    @Autowired(required = false)
    public void setScopeConsumers(@Nullable ScopeConsumers scopeConsumers) {
        this.scopeConsumers = scopeConsumers;
    }

    /**
     * ADR-0068 §1: wires the tagging step. Setter-injected and optional for the same reason as the
     * scope resolver; without it {@link #tag} answers with the heuristic tagger.
     */
    @Autowired(required = false)
    public void setTaggingService(@Nullable TaggingService taggingService) {
        this.taggingService = taggingService;
    }

    /**
     * The heuristic tagger of this context (its simple-chat classifier reads the live rule catalog);
     * a hand-built engine keeps the one over the shipped catalog.
     */
    @Autowired(required = false)
    public void setHeuristicTagger(@Nullable HeuristicQuestionTagger heuristicTagger) {
        if (heuristicTagger != null) {
            this.heuristicTagger = heuristicTagger;
        }
    }

    /**
     * Null when no resolver is wired or the mode is {@code off}: the turn has no scope at all.
     *
     * @param tagSeeds ADR-0068 spec §2.7: the acting entity Nouls and {@code domain}; {@link
     *     TagSeeds#none()} outside {@code enforce}, so the three-argument resolution
     */
    private @Nullable ScopeSet resolveScope(
            @NonNull String message,
            @NonNull Set<String> permissionCodes,
            @NonNull WorkflowState workflowState,
            @NonNull TagSeeds tagSeeds) {
        ScopeResolver resolver = scopeResolver;
        if (resolver == null || !resolver.enabled()) {
            return null;
        }
        try {
            return resolver.resolve(message, permissionCodes, workflowState, tagSeeds);
        } catch (RuntimeException exception) {
            // The resolver already swallows its own failures; this guards a resolver that does not.
            LOGGER.warn(
                    "Scope resolution threw into tool selection; continuing with no scope: {}",
                    exception.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * The message-independent superset of {@link #fallbackToolsForTags}, for the role-level agent
     * paths that build before a question exists (cache warm-up, {@code getOrCreateAgent}). Every
     * keyword-addable tool belongs here precisely because there is no keyword to match on yet —
     * omitting {@code dateWindowFacadeTool} would leave those agents unable to resolve a window at
     * all while their prompt still required one (#1684). {@code glossaryFacadeTool} is here for the
     * same reason (#1688): it is added unconditionally below, and the GLOSSARY prompt layer is
     * appended unconditionally too, so leaving it out here would hand those agents a prompt telling
     * them to call {@code lookupBusinessTerm} before answering and no such tool to call.
     */
    public @NonNull List<Object> fullFallbackTools() {
        return sharedOrchestrationSupport.mergeTools(
                toolRegistry.resolveMasterTools(),
                List.of(
                        dateWindowFacadeTool,
                        exaWebSearchTool,
                        glossaryFacadeTool,
                        inventoryFacadeTool,
                        orderFacadeTool));
    }

    /**
     * @param tools the ranked cut's beans, with the scope-added facades (if any) appended after them
     * @param scopeAddedTools the {@code mcp_tool.name}s the scope added, in slot order; empty unless
     *     the {@code tools} consumer acted
     * @param gatedToolNames ADR-0068 §2: the caller's permission-gated set by {@code mcp_tool.name},
     *     which the tag-added facades are intersected with; null when it is unavailable (no {@code
     *     ToolRegistryService}, or the ranked path failed closed), in which case nothing tag-driven is
     *     added
     */
    private record RankedRoleTools(
            @NonNull List<Object> tools,
            @NonNull List<String> scopeAddedTools,
            @Nullable Set<String> gatedToolNames) {
        private static RankedRoleTools unavailable(List<Object> tools) {
            return new RankedRoleTools(tools, List.of(), null);
        }

        private static RankedRoleTools of(List<Object> tools, Set<String> gatedToolNames) {
            return new RankedRoleTools(tools, List.of(), gatedToolNames);
        }
    }

    private @NonNull RankedRoleTools roleToolsForMessage(
            @NonNull String role,
            @NonNull Set<String> permissionCodes,
            @NonNull String message,
            @NonNull WorkflowState workflowState,
            @Nullable ScopeSet scope,
            @NonNull QuestionTags tags) {
        List<Object> fullRoleTools = toolRegistry.resolveDomainTools(role);
        if (toolRegistryService == null) {
            logToolSelectorUnavailable(role, permissionCodes, message, fullRoleTools);
            return RankedRoleTools.unavailable(fullRoleTools);
        }
        ScopeConsumers consumers = scopeConsumers;
        boolean scopeToolsActive = consumers != null && consumers.toolsActOn(scope);
        try {
            logWorkflowState(message, workflowState.name());
            ToolSelectionContext context =
                    new ToolSelectionContext(message, role, workflowState.name(), permissionCodes);
            // ADR-0069 §6 and ADR-0068 §2: both the scope's slot step and the tag-added facades are
            // intersected with the caller's gated set, which the ranking fetches anyway, so the
            // resolution always returns the wider answer. The ranked cut itself is unchanged; the tag
            // record reaches the admin fast path's veto (ADR-0068 §3.4).
            CandidateSelection selection =
                    toolRegistryService.resolveCandidateSelection(context, candidateToolLimit, tags);
            List<ToolMetadata> candidates = selection.candidates();
            logCandidates(role, permissionCodes, workflowState.name(), candidates);
            List<String> selectedNames =
                    candidates.stream().map(ToolMetadata::name).toList();
            if (selectedNames.isEmpty()) {
                // #1606 / #1608: an empty gated set is a CORRECT answer — the caller holds no
                // permission group for any tool — so it must yield no tools. Returning
                // fullRoleTools here would hand back MasterAgentRegistry.resolveDomainTools(role),
                // which is bucketed by domain with no permission gating at all (its own javadoc
                // defers visibility to "upstream permission gating" — this path). That inverted
                // V40's purpose: a caller holding only a code V40 strips from the gate matched
                // nothing, fell through here, and received the entire domain tool set.
                logNoCandidates(role, permissionCodes, message, fullRoleTools);
                return RankedRoleTools.of(List.of(), selection.gatedToolNames());
            }
            // Resolve names across the full registered tool set (not role-scoped): permission gating
            // + scoring already ran in ToolRegistryService, and tools are bucketed by domain. The
            // legacy role-scoped name lookup was removed with the role preassignment. See Gate 2B / #780.
            List<Object> resolvedTools = toolRegistry.resolveToolsByName(selectedNames);
            if (resolvedTools.isEmpty() && !fullRoleTools.isEmpty()) {
                // Gating succeeded but the selected names resolved to no beans — a registry wiring
                // fault, not a permission decision. Still fail closed: the caller's permissions did
                // not authorise the domain set, so returning it would be a wider answer than
                // success would have produced.
                logResolvedToZeroTools(role, permissionCodes, message, selectedNames, fullRoleTools);
                return RankedRoleTools.unavailable(List.of());
            }
            logResolvedCandidates(role, permissionCodes, message, selectedNames, resolvedTools);
            if (!scopeToolsActive || selection.adminFastPath()) {
                // ADR-0069 §6: the admin fast path returns AdminFacadeTool ALONE by design (see
                // ToolRegistryService): an administration question is answered by that tool and
                // nothing else, and the fast path exists so no other tool competes for the prompt.
                // Adding scope tools there would undo that decision, so the slot step treats it like
                // the ranked cut it replaces and adds nothing.
                return RankedRoleTools.of(resolvedTools, selection.gatedToolNames());
            }
            return withScopeFacades(scope, selection.gatedToolNames(), selectedNames, resolvedTools, consumers);
        } catch (RuntimeException exception) {
            // #1608: fail CLOSED. The previous behaviour returned fullRoleTools — an ungated,
            // domain-bucketed set — so any error on the gating path silently degraded
            // authorisation from perm_bits back to role scope, the very model the
            // permission-based gating work retired. It is reachable in ordinary operation: a pod
            // that serves before Flyway applies V40 raises BadSqlGrammarException on the
            // permission_group column (observed on alpha, 2026-08-31). ERROR, not WARN — a gate
            // that cannot be evaluated is an incident, not noise.
            LOGGER.error(
                    "MCP shared tool selection failed role={} permissionCodes={} queryPreview=\"{}\" error={}; returning NO tools (fail-closed)",
                    role,
                    permissionCodes,
                    sharedOrchestrationSupport.preview(message),
                    exception.getClass().getSimpleName(),
                    exception);
            // ADR-0069 §6 / ADR-0068 §2: a gate that could not be evaluated adds nothing either.
            return RankedRoleTools.unavailable(List.of());
        }
    }

    /**
     * ADR-0069 §6, facade slots: the scope's facade tools that are in the caller's gated set and
     * not already selected, in slot order (hop, reads before writes, name), up to {@code
     * added-tool-slots}, appended AFTER the ranked cut. The ranked cut is never reordered, trimmed
     * or displaced; the gated set, fetched by the same SQL that gates the ranking, decides what may
     * be added, so a forged scope cannot add a tool the caller may not use.
     */
    private @NonNull RankedRoleTools withScopeFacades(
            @NonNull ScopeSet scope,
            @NonNull Set<String> gatedToolNames,
            @NonNull List<String> selectedNames,
            @NonNull List<Object> resolvedTools,
            @NonNull ScopeConsumers consumers) {
        Set<String> alreadySelected = new HashSet<>();
        selectedNames.forEach(name -> alreadySelected.add(name.toLowerCase(Locale.ROOT)));
        sharedOrchestrationSupport
                .toolNames(resolvedTools)
                .forEach(name -> alreadySelected.add(name.toLowerCase(Locale.ROOT)));
        List<String> addedNames = scope.facadeTools().stream()
                .sorted(ScopeTool.SLOT_ORDER)
                .map(ScopeTool::name)
                .filter(gatedToolNames::contains)
                .filter(name -> !alreadySelected.contains(name.toLowerCase(Locale.ROOT)))
                .distinct()
                .limit(consumers.remainingSlots(0))
                .toList();
        if (addedNames.isEmpty()) {
            return RankedRoleTools.of(resolvedTools, gatedToolNames);
        }
        List<Object> addedTools = toolRegistry.resolveToolsByName(addedNames);
        List<Object> tools = new ArrayList<>(resolvedTools);
        for (Object tool : addedTools) {
            if (tools.stream().noneMatch(existing -> existing == tool)) {
                tools.add(tool);
            }
        }
        // Only names that resolved to a bean count as added: a name without a bean took no slot.
        Set<String> resolvedNames = new HashSet<>();
        sharedOrchestrationSupport
                .toolNames(addedTools)
                .forEach(name -> resolvedNames.add(name.toLowerCase(Locale.ROOT)));
        List<String> added = addedNames.stream()
                .filter(name -> resolvedNames.contains(name.toLowerCase(Locale.ROOT)))
                .toList();
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("MCP scope facade slots added={} confidence={}", added, scope.confidence());
        }
        return new RankedRoleTools(tools, added, gatedToolNames);
    }

    private void logToolSelectorUnavailable(
            @NonNull String role,
            @NonNull Set<String> permissionCodes,
            @NonNull String message,
            @NonNull List<Object> fullRoleTools) {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(
                    "MCP shared tool selector unavailable role={} permissionCodes={} resolvedRoleTools={} queryPreview=\"{}\"",
                    role,
                    permissionCodes,
                    sharedOrchestrationSupport.toolNames(fullRoleTools),
                    sharedOrchestrationSupport.preview(message));
        }
    }

    private void logWorkflowState(@NonNull String message, @NonNull String workflowState) {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(
                    "MCP shared workflow state derived message preview=\"{}\" workflowState={}",
                    sharedOrchestrationSupport.preview(message),
                    workflowState);
        }
    }

    private void logCandidates(
            @NonNull String role,
            @NonNull Set<String> permissionCodes,
            @NonNull String workflowState,
            @NonNull List<ToolMetadata> candidates) {
        if (LOGGER.isDebugEnabled()) {
            for (int i = 0; i < candidates.size(); i++) {
                ToolMetadata candidate = candidates.get(i);
                double confidence = confidenceScore(i, candidate.priority());
                LOGGER.debug(
                        "MCP shared tool candidate role={} permissionCodes={} workflowState={} toolName={} score={} priority={}",
                        role,
                        permissionCodes,
                        workflowState,
                        candidate.name(),
                        String.format(Locale.ROOT, "%.3f", confidence),
                        String.format(Locale.ROOT, "%.3f", candidate.priority()));
            }
        }
    }

    private @NonNull List<Object> logNoCandidates(
            @NonNull String role,
            @NonNull Set<String> permissionCodes,
            @NonNull String message,
            @NonNull List<Object> fullRoleTools) {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(
                    "MCP shared tool selector returned no candidates role={} permissionCodes={} queryPreview=\"{}\" fullRoleTools={}; using full role tool set",
                    role,
                    permissionCodes,
                    sharedOrchestrationSupport.preview(message),
                    sharedOrchestrationSupport.toolNames(fullRoleTools));
        }
        return fullRoleTools;
    }

    private @NonNull List<Object> logResolvedToZeroTools(
            @NonNull String role,
            @NonNull Set<String> permissionCodes,
            @NonNull String message,
            @NonNull List<String> selectedNames,
            @NonNull List<Object> fullRoleTools) {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(
                    "MCP shared tool candidates resolved to zero role tools role={} permissionCodes={} queryPreview=\"{}\" candidateNames={} fullRoleTools={}; using full role tool set",
                    role,
                    permissionCodes,
                    sharedOrchestrationSupport.preview(message),
                    selectedNames,
                    sharedOrchestrationSupport.toolNames(fullRoleTools));
        }
        return fullRoleTools;
    }

    private void logResolvedCandidates(
            @NonNull String role,
            @NonNull Set<String> permissionCodes,
            @NonNull String message,
            @NonNull List<String> selectedNames,
            @NonNull List<Object> resolvedTools) {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(
                    "MCP shared tool candidates role={} permissionCodes={} queryPreview=\"{}\" candidateNames={} resolvedRoleTools={}",
                    role,
                    permissionCodes,
                    sharedOrchestrationSupport.preview(message),
                    selectedNames,
                    sharedOrchestrationSupport.toolNames(resolvedTools));
        }
    }

    /**
     * Tools added on top of the semantic top-K rather than selected within it, so nothing here can
     * displace a tool the embedding ranking chose (ADR-0068 §3.2). Each addition is read from the
     * turn's tag record ({@code implies_date_window}, {@code needs_web_search}, {@code
     * about_inventory}, {@code about_orders}); the heuristics that used to match the message here
     * live in {@link HeuristicQuestionTagger} and answer those tags.
     *
     * <p>ADR-0068 §2, §3.1 (a deliberate change): every tag-added facade is offered only if it is in
     * the caller's permission-gated set. Before, these additions bypassed {@code mcp_tool_permission}
     * at selection and relied on the downstream {@code @PreAuthorize}. When the gated set is
     * unavailable (no {@code ToolRegistryService}, or the ranked path failed closed) no gated facade
     * is added. Two tools are exempt from the intersection and are offered exactly as before: the
     * glossary (always) and web search (on {@code needs_web_search}). Neither has a permission to
     * intersect with, and neither reads tenant data.
     *
     * <p>ADR-0069 §6 row 3 / ADR-0068 spec §2.6: where the scope graph's {@code lookups} consumer is
     * enforced, {@code about_inventory} and {@code about_orders} are replaced by the lexicon's facade
     * tools of the turn's entity seeds, read from the {@link ScopeSet} as its hop-1 facades (the tools
     * whose {@code ACTS_ON} edge reaches a seed entity, already filtered to what the caller may use).
     * These stay tag-added: unioned with the scope's slot step, outside its {@code added-tool-slots}
     * cap (ADR-0068 §3.2), and still intersected with the gated set (an unavailable gated set
     * withholds them too). The replacement needs an entity seed: without one (no entity named or
     * tagged, a domain-only scope, the resolver's empty scope) the two tags decide as today. Only a
     * missing scope (resolution threw) is counted under {@code mcp.scope.fallback{consumer=lookups}}.
     */
    private @NonNull List<Object> fallbackToolsForTags(
            @NonNull QuestionTags tags, @Nullable Set<String> gatedToolNames, @Nullable ScopeSet scope) {
        List<Object> selected = new ArrayList<>();
        // #1688: always offered, with no keyword guard. Every other entry here is gated on wording
        // that names its domain, but the glossary's job is to answer "is this metric defined?" — and
        // the phrases that most need it are precisely the ones NOT in the glossary ("our most loyal
        // customers"), which no keyword derived from the glossary could match. A guard would
        // therefore fire only for terms the model could already have handled and stay silent for the
        // undefined ones, inverting the tool's purpose. It makes no HTTP call and carries one small
        // schema, so offering it unconditionally costs a few prompt tokens and nothing else.
        selected.add(glossaryFacadeTool);
        // #1684: a dated question must always be able to reach resolveDateWindow. Its mcp_tool row
        // (V43) carries domain 'date-window', and no ROLE resolves to that domain agent —
        // resolveDomainTools is keyed on the domain string — so its only route into the candidate
        // set is the embedding ranking in ToolRegistryService.resolveCandidateTools, where it
        // competes with every other gated tool on description similarity and can lose. Nothing about
        // "which customers haven't bought in the last 90 days" reads as a date-arithmetic request,
        // which is exactly the question whose window shape the gate keeps getting wrong.
        if (tags.impliesDateWindow()) {
            addIfGated(selected, dateWindowFacadeTool, gatedToolNames);
        }
        if (tags.needsWebSearch()) {
            // Exempt from the gated-set intersection, like the glossary above. ExaWebSearchTool has no
            // mcp_tool row and no mcp_tool_permission row (it is not a catalog tool, so the
            // registry query that builds the gated set can never return it) and no @PreAuthorize: it
            // queries the public web and reads no tenant data, so there is no permission to
            // intersect with. Gating it would withhold web search from every caller, which is not a
            // permission decision but the loss of a feature the keyword guard offered before ADR-0068.
            selected.add(exaWebSearchTool);
        }
        ScopeConsumers consumers = scopeConsumers;
        boolean lookups = consumers != null && consumers.enforces(Consumer.LOOKUPS);
        if (lookups && scope != null && !scope.seeds().isEmpty()) {
            addLexiconFacades(selected, scope, gatedToolNames);
        } else {
            // No entity seed (a NONE scope, a domain-only scope, the resolver's empty scope): today's
            // guards stand, and that is not a fallback. Only a missing scope is one.
            if (lookups && scope == null && consumers != null) {
                consumers.recordFallback(Consumer.LOOKUPS);
            }
            if (tags.aboutInventory()) {
                addIfGated(selected, inventoryFacadeTool, gatedToolNames);
            }
            if (tags.aboutOrders()) {
                addIfGated(selected, orderFacadeTool, gatedToolNames);
            }
        }
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(
                    "MCP shared fallback tool matches tools={} gatedSetAvailable={}",
                    sharedOrchestrationSupport.toolNames(selected),
                    gatedToolNames != null);
        }
        return selected;
    }

    /**
     * ADR-0069 §6 row 3: the lexicon facade tools of the seed entities (the scope's hop-1 facades), in
     * slot order, each only when the gated set names it and it is not already selected; a null
     * (unavailable) gated set names nothing.
     */
    private void addLexiconFacades(
            @NonNull List<Object> selected, @NonNull ScopeSet scope, @Nullable Set<String> gatedToolNames) {
        if (gatedToolNames == null) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("MCP lexicon facade lookups withheld (gated set unavailable)");
            }
            return;
        }
        List<String> names = scope.facadeTools().stream()
                .filter(tool -> tool.hop() == 1)
                .sorted(ScopeTool.SLOT_ORDER)
                .map(ScopeTool::name)
                .filter(gatedToolNames::contains)
                .distinct()
                .toList();
        if (names.isEmpty()) {
            return;
        }
        for (Object tool : toolRegistry.resolveToolsByName(names)) {
            if (selected.stream().noneMatch(existing -> existing == tool)) {
                selected.add(tool);
            }
        }
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("MCP lexicon facade lookups added={}", names);
        }
    }

    /**
     * ADR-0068 §2: adds {@code tool} only when the gated set names it; a null (unavailable) gated set
     * names nothing. The gated set is keyed by {@code mcp_tool.name}, the facade's class simple name
     * (some rows and tests spell it bean-style), matched the way {@code
     * MasterAgentRegistry.resolveToolsByName} matches.
     */
    private void addIfGated(
            @NonNull List<Object> selected, @NonNull Object tool, @Nullable Set<String> gatedToolNames) {
        String className = sharedOrchestrationSupport.toolName(tool);
        if (gatedToolNames == null) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("MCP shared fallback tool withheld: {} (gated set unavailable)", className);
            }
            return;
        }
        String beanStyle = java.beans.Introspector.decapitalize(className);
        boolean gated = gatedToolNames.stream()
                .anyMatch(name -> name.equalsIgnoreCase(className) || name.equalsIgnoreCase(beanStyle));
        if (gated) {
            selected.add(tool);
        } else if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("MCP shared fallback tool withheld: {} is not in the caller's gated set", className);
        }
    }

    private static double confidenceScore(int rankIndex, double priority) {
        double rankScore = 1.0 / (rankIndex + 1);
        return Math.clamp((rankScore * 0.7) + (Math.clamp(priority, 0.0, 1.0) * 0.3), 0.0, 1.0);
    }

    /**
     * @param scope ADR-0069 §5: this turn's resolved scope, or null when none was resolved (mode
     *     {@code off}, or no resolver wired). Recorded and published by the session managers.
     * @param scopeAddedTools ADR-0069 §6: the facade tools the scope added on top of the ranked
     *     cut (already inside {@code roleTools}), by {@code mcp_tool.name}; empty unless the {@code
     *     tools} consumer acted. They take the first of the turn's shared added-tool slots.
     */
    public record ToolSelectionResult(
            @NonNull List<Object> roleTools,
            @NonNull List<Object> fallbackTools,
            @NonNull WorkflowState workflowState,
            @Nullable ScopeSet scope,
            @NonNull List<String> scopeAddedTools) {

        public ToolSelectionResult {
            scopeAddedTools = List.copyOf(scopeAddedTools);
        }

        /** A scope without added tools (mode {@code shadow}, or the {@code tools} consumer not enforced). */
        public ToolSelectionResult(
                @NonNull List<Object> roleTools,
                @NonNull List<Object> fallbackTools,
                @NonNull WorkflowState workflowState,
                @Nullable ScopeSet scope) {
            this(roleTools, fallbackTools, workflowState, scope, List.of());
        }

        /** The pre-ADR-0069 shape: no scope. */
        public ToolSelectionResult(
                @NonNull List<Object> roleTools,
                @NonNull List<Object> fallbackTools,
                @NonNull WorkflowState workflowState) {
            this(roleTools, fallbackTools, workflowState, null, List.of());
        }

        /** Backward-compatible constructor defaulting to {@link WorkflowState#IDLE}. */
        public ToolSelectionResult(@NonNull List<Object> roleTools, @NonNull List<Object> fallbackTools) {
            this(roleTools, fallbackTools, WorkflowState.IDLE, null, List.of());
        }
    }
}
