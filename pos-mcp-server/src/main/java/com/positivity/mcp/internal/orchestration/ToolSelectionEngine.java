package com.positivity.mcp.internal.orchestration;

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
import java.util.regex.Pattern;
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

    /**
     * Vocabulary that makes a question a dated one, and so makes {@code resolveDateWindow}
     * mandatory rather than merely likely (#1684). Broad on purpose: the tool is additive to the
     * semantic top-K rather than competing for a slot in it, so a false positive costs one tool
     * schema in the prompt while a false negative costs the whole date-window contract — the
     * DATE_WINDOW layer would be instructing the model to call a tool it cannot see, leaving it to
     * compute the dates itself, which is the failure #1675 and #1684 exist to remove.
     *
     * <p>Broad is not unbounded, and the schema is not free: this tool's description and parameters
     * are roughly 440 tokens, more than the ~305 the #1684 prompt-layer shrink saves. On a question
     * that matches a token but needs no window the assembled prompt is therefore <em>larger</em>
     * than before, which is the opposite of what the shrink was for. So a token earns its place only
     * by naming a window the resolver can actually resolve. Four were cut on that test: {@code
     * recent}, {@code recently} and {@code lately} are the phrases the layer itself singles out as
     * having no conventional reading and tells the model to ask about, so offering a resolver for
     * them is incoherent (and {@code recent} already pulls in the web-search tool below, making two
     * extra schemas); {@code period} is accounting vocabulary far more often than it is a window,
     * and it names the {@code period} shortcut that is a shape bypass rather than a resolver call.
     */
    private static final List<Pattern> DATE_WINDOW_WORD_PATTERNS = compileWordPatterns(Set.of(
            "annual",
            "daily",
            "day",
            "days",
            "month",
            "monthly",
            "months",
            "mtd",
            "quarter",
            "quarterly",
            "quarters",
            "qtd",
            "since",
            "today",
            "week",
            "weekly",
            "weeks",
            "year",
            "yearly",
            "years",
            "yesterday",
            "ytd"));

    /**
     * Vocabulary of a metric question that names no window at all (#1840). The DATE_WINDOW layer
     * tells the model that a windowless report question still has a window — the contract's default
     * — and to resolve it, so "who are our ten largest customers by revenue?" is a dated question
     * even though it contains none of the words above. On the 2026-09-06 sequences run that exact
     * question lost {@code resolveDateWindow} to the candidate cut and the model called the tool it
     * could not see. A metric word earns its place here when the prompt would send the model to the
     * resolver for it; the near misses guarded by the test suite ("phone number for NAPA", "recent
     * notes on this vehicle") name no metric and stay out.
     */
    private static final List<Pattern> IMPLIED_WINDOW_WORD_PATTERNS = compileWordPatterns(Set.of(
            "average",
            "avg",
            "billed",
            "biggest",
            "collected",
            "count",
            "growth",
            "invoiced",
            "largest",
            "least",
            "margin",
            "most",
            "payables",
            "profit",
            "rank",
            "ranking",
            "receivables",
            "revenue",
            "sales",
            "spend",
            "spending",
            "spent",
            "top",
            "total",
            "totals",
            "trend"));

    /**
     * Vocabulary that names an ABSOLUTE period rather than a relative one — a four-digit year, a
     * calendar quarter, or a month name (#1684).
     *
     * <p>These were not in {@link #DATE_WINDOW_WORD_PATTERNS} because when that list was written the
     * only resolver expressed relative shapes, so a bare "2025" named no window it could resolve —
     * which is the exact test that list applies. {@code resolveNamedPeriod} changed that: "in 2025",
     * "Q3 2026" and "July 2026" are now resolvable, so the tokens earn their place under the same
     * rule rather than in spite of it.
     *
     * <p>Adding them is load-bearing, not tidying. Removing the {@code period} shortcut made a
     * resolver call mandatory before any dated report call, and {@code ReportingPeriods} now hard
     * rejects a missing range with a message telling the model to call {@code resolveNamedPeriod}.
     * Without these tokens "what did we spend with Michelin in 2025?" matches nothing here, the
     * resolver is left to compete in the embedding ranking (which this class already documents it
     * can lose), and the turn can dead-end being told to call a tool it was never offered — in
     * precisely the case {@code resolveNamedPeriod} exists to serve.
     */
    private static final List<Pattern> NAMED_PERIOD_PATTERNS = List.of(
            Pattern.compile("\\b(?:19|20)\\d{2}\\b"),
            Pattern.compile("\\bq[1-4]\\b"),
            Pattern.compile("\\b(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)"
                    + "(?:uary|ruary|ch|il|e|y|ust|tember|ober|ember)?\\b"));

    /** Multi-word date vocabulary, matched as plain substrings rather than on word boundaries. */
    private static final Set<String> DATE_WINDOW_PHRASES = Set.of("to date", "so far this");

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

    public @NonNull ToolSelectionResult selectRoleTools(
            @NonNull String role, @NonNull Set<String> permissionCodes, @NonNull String message) {
        // Session-less callers (e.g. /v1/mcp/chat) fall back to message-heuristic derivation.
        return selectRoleTools(role, permissionCodes, message, deriveWorkflowState(message));
    }

    /**
     * Gate 2C: workflow-state-aware selection. {@code workflowState} is an explicit input so a
     * session-bearing caller can supply the persisted {@code NltiSession} state rather than relying
     * on message-text heuristics.
     */
    public @NonNull ToolSelectionResult selectRoleTools(
            @NonNull String role,
            @NonNull Set<String> permissionCodes,
            @NonNull String message,
            @NonNull WorkflowState workflowState) {
        // ADR-0069 §5: the scope is resolved here, once the workflow state is known and before tool
        // ranking, because this is the one selection entry point both session managers call. The
        // only consumer that reads it here is the facade slot step (§6), and only when enforced.
        ScopeSet scope = resolveScope(message, permissionCodes, workflowState);
        RankedRoleTools ranked = roleToolsForMessage(role, permissionCodes, message, workflowState, scope);
        List<Object> fallbackTools = sharedOrchestrationSupport.mergeTools(
                toolRegistry.resolveMasterTools(), fallbackToolsForMessage(message));
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

    /** Null when no resolver is wired or the mode is {@code off}: the turn has no scope at all. */
    private @Nullable ScopeSet resolveScope(
            @NonNull String message, @NonNull Set<String> permissionCodes, @NonNull WorkflowState workflowState) {
        ScopeResolver resolver = scopeResolver;
        if (resolver == null || !resolver.enabled()) {
            return null;
        }
        try {
            return resolver.resolve(message, permissionCodes, workflowState);
        } catch (RuntimeException exception) {
            // The resolver already swallows its own failures; this guards a resolver that does not.
            LOGGER.warn(
                    "Scope resolution threw into tool selection; continuing with no scope: {}",
                    exception.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * The message-independent superset of {@link #fallbackToolsForMessage}, for the role-level agent
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
     */
    private record RankedRoleTools(
            @NonNull List<Object> tools, @NonNull List<String> scopeAddedTools) {
        private static RankedRoleTools of(List<Object> tools) {
            return new RankedRoleTools(tools, List.of());
        }
    }

    private @NonNull RankedRoleTools roleToolsForMessage(
            @NonNull String role,
            @NonNull Set<String> permissionCodes,
            @NonNull String message,
            @NonNull WorkflowState workflowState,
            @Nullable ScopeSet scope) {
        List<Object> fullRoleTools = toolRegistry.resolveDomainTools(role);
        if (toolRegistryService == null) {
            logToolSelectorUnavailable(role, permissionCodes, message, fullRoleTools);
            return RankedRoleTools.of(fullRoleTools);
        }
        ScopeConsumers consumers = scopeConsumers;
        boolean scopeToolsActive = consumers != null && consumers.toolsActOn(scope);
        try {
            logWorkflowState(message, workflowState.name());
            ToolSelectionContext context =
                    new ToolSelectionContext(message, role, workflowState.name(), permissionCodes);
            // ADR-0069 §6: the slot step needs the caller's gated set, which the ranking fetches
            // anyway, so it asks for the wider answer. When the consumer is not enforced the call
            // is exactly today's, so nothing on the ranked path changes in off or shadow.
            CandidateSelection selection = scopeToolsActive
                    ? toolRegistryService.resolveCandidateSelection(context, candidateToolLimit)
                    : new CandidateSelection(
                            toolRegistryService.resolveCandidateTools(context, candidateToolLimit), Set.of(), false);
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
                return RankedRoleTools.of(List.of());
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
                return RankedRoleTools.of(List.of());
            }
            logResolvedCandidates(role, permissionCodes, message, selectedNames, resolvedTools);
            if (!scopeToolsActive || selection.adminFastPath()) {
                // ADR-0069 §6: the admin fast path returns AdminFacadeTool ALONE by design (see
                // ToolRegistryService): an administration question is answered by that tool and
                // nothing else, and the fast path exists so no other tool competes for the prompt.
                // Adding scope tools there would undo that decision, so the slot step treats it like
                // the ranked cut it replaces and adds nothing.
                return RankedRoleTools.of(resolvedTools);
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
            // ADR-0069 §6: a gate that could not be evaluated adds nothing either.
            return RankedRoleTools.of(List.of());
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
            return RankedRoleTools.of(resolvedTools);
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
        return new RankedRoleTools(tools, added);
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
     * Heuristic workflow-state derivation from message text. Used only as a fallback for
     * session-less callers; the authoritative state is the persisted {@code NltiSession} value
     * supplied to the {@code WorkflowState} overload of {@link #selectRoleTools}.
     */
    private @NonNull WorkflowState deriveWorkflowState(@NonNull String message) {
        String lower = message.toLowerCase(Locale.ROOT);
        if (containsAny(lower, Set.of("purchase order", "create po", "new po", "po for vendor"))) {
            return WorkflowState.CREATING_PO;
        } else if (containsAny(
                lower,
                Set.of(
                        "receiving asn",
                        "receive asn",
                        "receive shipment",
                        "receive order",
                        "receiving shipment",
                        "advanced shipment notice",
                        "asn"))) {
            return WorkflowState.RECEIVING_ASN;
        } else if (containsAny(
                lower,
                Set.of(
                        "inventory recon",
                        "inventory reconciliation",
                        "reconcile inventory",
                        "stock reconciliation",
                        "cycle count"))) {
            return WorkflowState.INVENTORY_RECON;
        }
        return WorkflowState.IDLE;
    }

    /**
     * Tools added on top of the semantic top-K rather than selected within it, so nothing here can
     * displace a tool the embedding ranking chose.
     */
    private @NonNull List<Object> fallbackToolsForMessage(@NonNull String message) {
        String text = message.toLowerCase(Locale.ROOT);
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
        if (mentionsDateWindow(text)) {
            selected.add(dateWindowFacadeTool);
        }
        if (containsAny(text, Set.of("current", "internet", "news", "online", "recent", "web"))) {
            selected.add(exaWebSearchTool);
        }
        if (containsAny(
                text, Set.of("availability", "inventory", "location", "part", "product", "sku", "stock", "store"))) {
            selected.add(inventoryFacadeTool);
        }
        if (containsAny(text, Set.of("order", "po", "purchase", "sale", "sales"))) {
            selected.add(orderFacadeTool);
        }
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("MCP shared fallback tool matches tools={}", sharedOrchestrationSupport.toolNames(selected));
        }
        return selected;
    }

    /**
     * Whether {@code text} names a date window.
     *
     * <p>Deliberately not {@link #containsAny}: that builds {@code ".*\\btoken\\b.*"} and calls
     * {@link String#matches}, which anchors the whole input and — without {@code DOTALL} — has
     * {@code .} exclude {@code \n}, so no single-word token matches a message containing a line
     * break at all. A pasted or multi-paragraph question is ordinary in a chat surface, and this
     * guard is the one whose false negative costs the whole date-window contract, so it uses
     * {@code Matcher.find} on precompiled patterns instead. Precompiling also keeps the added
     * vocabulary off the per-request regex-compilation path.
     */
    private static boolean mentionsDateWindow(@NonNull String text) {
        for (String phrase : DATE_WINDOW_PHRASES) {
            if (text.contains(phrase)) {
                return true;
            }
        }
        for (Pattern pattern : DATE_WINDOW_WORD_PATTERNS) {
            if (pattern.matcher(text).find()) {
                return true;
            }
        }
        for (Pattern pattern : IMPLIED_WINDOW_WORD_PATTERNS) {
            if (pattern.matcher(text).find()) {
                return true;
            }
        }
        for (Pattern pattern : NAMED_PERIOD_PATTERNS) {
            if (pattern.matcher(text).find()) {
                return true;
            }
        }
        return false;
    }

    private static @NonNull List<Pattern> compileWordPatterns(@NonNull Set<String> words) {
        return words.stream()
                .map(word -> Pattern.compile("\\b" + Pattern.quote(word) + "\\b"))
                .toList();
    }

    private static boolean containsAny(@NonNull String text, @NonNull Set<String> tokens) {
        for (String token : tokens) {
            if (token.contains(" ")) {
                if (text.contains(token)) {
                    return true;
                }
            } else if (text.matches(".*\\b" + token + "\\b.*")) {
                return true;
            }
        }
        return false;
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
