package com.positivity.mcp.internal.service;

import com.positivity.mcp.internal.domain.QuestionTags;
import com.positivity.mcp.internal.domain.ToolMetadata;
import com.positivity.mcp.internal.domain.ToolSelectionContext;
import com.positivity.mcp.internal.repository.ToolMetadataRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.IntStream;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
public class ToolRegistryService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ToolRegistryService.class);
    private static final int MAX_QUERY_PREVIEW_LENGTH = 160;
    private static final String ADMIN_FACADE_TOOL = "AdminFacadeTool";
    /**
     * Bare nouns that identify an administration question. {@code account}/{@code accounts} are
     * deliberately absent: they collide with the accounting domain's core vocabulary ("accounts
     * receivable", "chart of accounts", "GL account"), and because the fast path returns
     * {@code AdminFacadeTool} <em>alone</em>, a collision silently suppressed every accounting
     * tool for the whole request. Admin senses of the word are carried by
     * {@link #ADMIN_QUERY_PHRASES} instead.
     */
    private static final Set<String> ADMIN_QUERY_KEYWORDS = Set.of(
            "user",
            "users",
            "role",
            "roles",
            "permission",
            "permissions",
            "access",
            "audit",
            "audits",
            "registered",
            "registration",
            "login",
            "logins");

    private static final Set<String> ADMIN_QUERY_PHRASES = Set.of(
            "who has access",
            "who can access",
            "audit log",
            "access review",
            "user count",
            "registered users",
            "account state",
            "user account",
            "user accounts",
            "account access",
            "disable account",
            "deactivate account");

    /**
     * Vocabulary that puts a question in a business domain the {@code AdminFacadeTool} cannot
     * answer. Any hit vetoes the fast path outright, so a query that mixes an admin keyword with
     * domain vocabulary ("who has access to the receivables ledger") still reaches semantic
     * ranking rather than being collapsed to the admin tool alone.
     */
    private static final Set<String> FAST_PATH_VETO_TERMS = Set.of(
            "receivable",
            "receivables",
            "payable",
            "payables",
            "invoice",
            "invoices",
            "invoiced",
            "ledger",
            "revenue",
            "past due",
            "past-due",
            "outstanding balance",
            "aging",
            "aged",
            "balance sheet",
            "income statement",
            "chart of accounts",
            "gl account",
            "workorder",
            "work order");

    private final ToolMetadataRepository repository;
    private final EmbeddingModel embeddingModel;
    private final TenantToolPriorityResolver priorityResolver;
    private final ToolScorer scorer;

    public ToolRegistryService(
            @NonNull ToolMetadataRepository repository,
            @NonNull EmbeddingModel embeddingModel,
            @NonNull TenantToolPriorityResolver priorityResolver) {
        this.repository = repository;
        this.embeddingModel = embeddingModel;
        this.priorityResolver = priorityResolver;
        this.scorer = new ToolScorer();
    }

    public @NonNull List<ToolMetadata> resolveCandidateTools(@NonNull ToolSelectionContext context, int topK) {
        return resolveCandidateSelection(context, topK).candidates();
    }

    /**
     * ADR-0069 §6: the ranked cut of {@link #resolveCandidateTools} together with the caller's whole
     * gated set, which this resolution fetches anyway ({@code findEnabledByPermissionsAndWorkflow}),
     * and whether the admin fast path answered. The scope's additive tool slots are intersected with
     * that gated set, so exposing it here saves them a second query; nothing about the ranking
     * changes.
     */
    public @NonNull CandidateSelection resolveCandidateSelection(@NonNull ToolSelectionContext context, int topK) {
        return resolveCandidateSelection(context, topK, QuestionTags.none());
    }

    /**
     * ADR-0068 §3.4: as {@link #resolveCandidateSelection(ToolSelectionContext, int)}, with the turn's
     * tag record. The admin fast path fires only when the heuristic keyword or phrase matched without a
     * veto term <em>and</em> the acting {@code admin_account_question} is {@code true}: a model {@code
     * false} at or above threshold vetoes it, a model {@code true} never fires it alone. In {@code off}
     * and {@code shadow} the acting value is the heuristic one, so the path fires exactly as before;
     * {@link QuestionTags#none()} behaves the same.
     */
    public @NonNull CandidateSelection resolveCandidateSelection(
            @NonNull ToolSelectionContext context, int topK, @NonNull QuestionTags tags) {
        if (topK <= 0) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(
                        "MCP tool lookup skipped role={} permissionCodes={} workflow={} topK={} reason=non-positive-topk queryPreview=\"{}\"",
                        context.role(),
                        context.permissionCodes(),
                        context.workflowState(),
                        topK,
                        preview(context.userInput()));
            }
            return CandidateSelection.EMPTY;
        }

        // ADR-0062 plan WS6: the catalog rows carry the global priority; the bound tenant's overlay
        // replaces it tool by tool (read once here, applied to every list of this resolution).
        TenantToolPriorityResolver.Overlay priorityOverlay = priorityResolver.currentOverlay();
        List<ToolMetadata> gatedTools = priorityOverlay.apply(
                repository.findEnabledByPermissionsAndWorkflow(context.permissionCodes(), context.workflowState()));

        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(
                    "MCP tool lookup start role={} permissionCodes={} workflow={} topK={} gatedToolCount={} gatedTools={} queryPreview=\"{}\"",
                    context.role(),
                    context.permissionCodes(),
                    context.workflowState(),
                    topK,
                    gatedTools.size(),
                    toolNames(gatedTools),
                    preview(context.userInput()));
        }

        if (gatedTools.isEmpty()) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(
                        "MCP tool lookup no gated tools role={} permissionCodes={} workflow={} queryPreview=\"{}\"",
                        context.role(),
                        context.permissionCodes(),
                        context.workflowState(),
                        preview(context.userInput()));
            }
            return CandidateSelection.EMPTY;
        }
        Set<String> gatedToolNames =
                gatedTools.stream().map(ToolMetadata::name).collect(java.util.stream.Collectors.toUnmodifiableSet());

        List<ToolMetadata> adminFastPathSelection = adminFastPathSelection(context, gatedTools, tags);
        if (!adminFastPathSelection.isEmpty()) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(
                        "MCP tool lookup fast-path result role={} permissionCodes={} workflow={} selectedTools={} queryPreview=\"{}\"",
                        context.role(),
                        context.permissionCodes(),
                        context.workflowState(),
                        toolNames(adminFastPathSelection),
                        preview(context.userInput()));
            }
            return new CandidateSelection(adminFastPathSelection, gatedToolNames, true);
        }

        float[] embedding = embeddingModel.embed(context.userInput());
        int semanticLimit = Math.max(topK, 10);

        // Permission-gated ANN — only tools authorized for this caller's permissionCodes
        // and workflow enter the ranking window. Unauthorized tools can never displace
        // authorized ones.
        List<ToolMetadata> semanticCandidates = priorityOverlay.apply(repository.findTopKByEmbeddingForPermissions(
                embedding, semanticLimit, context.permissionCodes(), context.workflowState()));

        if (semanticCandidates.isEmpty()) {
            // Fallback: tools have no embeddings yet; return highest-priority gated tools
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(
                        "MCP tool scoring no-embedding fallback role={} permissionCodes={} workflow={} gatedToolCount={} returning top-{} by priority",
                        context.role(),
                        context.permissionCodes(),
                        context.workflowState(),
                        gatedTools.size(),
                        topK);
            }
            return new CandidateSelection(
                    gatedTools.stream()
                            .sorted(Comparator.comparingDouble(ToolMetadata::priority)
                                    .reversed()
                                    .thenComparing(ToolMetadata::name))
                            .limit(topK)
                            .toList(),
                    gatedToolNames,
                    false);
        }

        List<ScoredTool> scoredCandidates = IntStream.range(0, semanticCandidates.size())
                .mapToObj(index -> new ScoredTool(
                        semanticCandidates.get(index), scorer.score(semanticCandidates.get(index), index), index))
                .sorted(Comparator.comparingDouble(
                                (ScoredTool scored) -> scored.score().total())
                        .reversed()
                        .thenComparingInt(ScoredTool::rankPosition)
                        .thenComparing(st -> st.tool().name()))
                .toList();

        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(
                    "MCP tool scoring role={} permissionCodes={} workflow={} topK={} gatedToolCount={} semanticCandidateCount={} gatedTools={} semanticCandidates={} scoredCandidates={}",
                    context.role(),
                    context.permissionCodes(),
                    context.workflowState(),
                    topK,
                    gatedTools.size(),
                    semanticCandidates.size(),
                    toolNames(gatedTools),
                    semanticCandidateSummaries(semanticCandidates),
                    scoredCandidateSummaries(scoredCandidates));
        }

        List<ToolMetadata> selectedTools =
                scoredCandidates.stream().limit(topK).map(ScoredTool::tool).toList();
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(
                    "MCP tool lookup result role={} permissionCodes={} workflow={} selectedToolCount={} selectedTools={} queryPreview=\"{}\"",
                    context.role(),
                    context.permissionCodes(),
                    context.workflowState(),
                    selectedTools.size(),
                    toolNames(selectedTools),
                    preview(context.userInput()));
        }
        return new CandidateSelection(selectedTools, gatedToolNames, false);
    }

    /**
     * One resolution's ranked cut plus what it learnt on the way (ADR-0069 §6).
     *
     * @param candidates the ranked cut, exactly what {@link #resolveCandidateTools} returns
     * @param gatedToolNames every tool the caller may use in this workflow state, by {@code
     *     mcp_tool.name}; empty when the caller has none
     * @param adminFastPath true when {@code AdminFacadeTool} was returned alone by the admin fast
     *     path rather than by ranking
     */
    public record CandidateSelection(
            @NonNull List<ToolMetadata> candidates, @NonNull Set<String> gatedToolNames, boolean adminFastPath) {

        /** No candidates and no gated set: the caller holds no permission group for any tool. */
        public static final CandidateSelection EMPTY = new CandidateSelection(List.of(), Set.of(), false);

        public CandidateSelection {
            candidates = List.copyOf(candidates);
            gatedToolNames = Set.copyOf(gatedToolNames);
        }
    }

    /**
     * Returns {@code AdminFacadeTool} as the sole candidate when the caller's query matches
     * admin-style keywords/phrases and {@code AdminFacadeTool} is already present in {@code
     * gatedTools} — i.e. the caller holds at least one of its mapped {@code
     * mcp_tool_permission} codes (permission gating already applied upstream by {@link
     * #resolveCandidateTools}). No role check is performed here.
     */
    private @NonNull List<ToolMetadata> adminFastPathSelection(
            @NonNull ToolSelectionContext context, @NonNull List<ToolMetadata> gatedTools, @NonNull QuestionTags tags) {
        Set<String> matchedTerms = matchedAdminQueryTerms(context.userInput());
        if (matchedTerms.isEmpty()) {
            return List.of();
        }

        Set<String> vetoTerms = matchedVetoTerms(context.userInput());
        if (!vetoTerms.isEmpty()) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(
                        "MCP tool fast-path vetoed by domain vocabulary role={} workflow={} matchedTerms={} vetoTerms={} queryPreview=\"{}\"",
                        context.role(),
                        context.workflowState(),
                        matchedTerms,
                        vetoTerms,
                        preview(context.userInput()));
            }
            return List.of();
        }
        // ADR-0068 §3.4: the heuristic matched; the acting tag may still veto (a model false at or
        // above threshold in enforce). An absent record decides from the lists alone, as before.
        if (!tags.isNone() && !tags.adminAccountQuestion()) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(
                        "MCP tool fast-path vetoed by the admin_account_question tag role={} workflow={} matchedTerms={}",
                        context.role(),
                        context.workflowState(),
                        matchedTerms);
            }
            return List.of();
        }

        List<ToolMetadata> adminTools = gatedTools.stream()
                .filter(tool -> ADMIN_FACADE_TOOL.equals(tool.name()))
                .toList();
        if (!adminTools.isEmpty()) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(
                        "MCP tool fast-path matched role={} permissionCodes={} workflow={} tool={} matchedTerms={} queryPreview=\"{}\"",
                        context.role(),
                        context.permissionCodes(),
                        context.workflowState(),
                        ADMIN_FACADE_TOOL,
                        matchedTerms,
                        preview(context.userInput()));
            }
        } else {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(
                        "MCP tool fast-path eligible but admin tool unavailable role={} permissionCodes={} workflow={} matchedTerms={} gatedTools={}",
                        context.role(),
                        context.permissionCodes(),
                        context.workflowState(),
                        matchedTerms,
                        toolNames(gatedTools));
            }
        }
        return adminTools;
    }

    /**
     * ADR-0068 §1, {@code admin_account_question}: the heuristic value the fast path fires on, an
     * admin keyword or phrase matched and no veto term, read here by {@code HeuristicQuestionTagger}
     * so the three lists stay in this class (ADR-0068 §1 placement). The fast path itself is
     * unchanged in Wave 1 and still decides from the same lists.
     */
    public static boolean isAdminAccountQuestion(@NonNull String userInput) {
        return !matchedAdminQueryTerms(userInput).isEmpty()
                && matchedVetoTerms(userInput).isEmpty();
    }

    /**
     * ADR-0068: the rule behind {@link #isAdminAccountQuestion}, for the trace: {@code veto:<term>} when a
     * veto term blocked a matched keyword, {@code match:<term>} when the path fires, empty when nothing
     * matched. The sets are sorted, so the first term is deterministic.
     */
    public static @NonNull Optional<String> adminAccountRule(@NonNull String userInput) {
        Set<String> matched = matchedAdminQueryTerms(userInput);
        if (matched.isEmpty()) {
            return Optional.empty();
        }
        Set<String> vetoes = matchedVetoTerms(userInput);
        return Optional.of(
                vetoes.isEmpty()
                        ? "match:" + matched.iterator().next()
                        : "veto:" + vetoes.iterator().next());
    }

    private static @NonNull Set<String> matchedAdminQueryTerms(@NonNull String userInput) {
        String normalized = userInput.toLowerCase(Locale.ROOT);
        Set<String> matches = new TreeSet<>();
        for (String keyword : ADMIN_QUERY_KEYWORDS) {
            if (normalized.matches(".*\\b" + keyword + "\\b.*")) {
                matches.add(keyword);
            }
        }
        for (String phrase : ADMIN_QUERY_PHRASES) {
            if (normalized.contains(phrase)) {
                matches.add(phrase);
            }
        }
        return matches;
    }

    private static @NonNull Set<String> matchedVetoTerms(@NonNull String userInput) {
        String normalized = userInput.toLowerCase(Locale.ROOT);
        Set<String> matches = new TreeSet<>();
        for (String term : FAST_PATH_VETO_TERMS) {
            if (normalized.contains(term)) {
                matches.add(term);
            }
        }
        return matches;
    }

    static final class ToolScorer {

        @NonNull
        ToolScore score(@NonNull ToolMetadata tool, int rankPosition) {
            double semanticScore = 1.0 / (rankPosition + 1);
            double priorityBoost = Math.clamp(tool.priority(), 0.0, 1.0);
            double latencyPenalty = Math.min(tool.avgLatencyMs() / 1000.0, 1.0) * 0.2;
            double costPenalty =
                    switch (tool.costLevel().toLowerCase()) {
                        case "high" -> 0.2;
                        case "medium" -> 0.1;
                        default -> 0.0;
                    };
            return new ToolScore(
                    semanticScore + priorityBoost - latencyPenalty - costPenalty,
                    semanticScore,
                    priorityBoost,
                    latencyPenalty,
                    costPenalty);
        }
    }

    private static @NonNull List<String> toolNames(@NonNull List<ToolMetadata> tools) {
        return tools.stream().map(ToolMetadata::name).toList();
    }

    private static @NonNull List<String> semanticCandidateSummaries(@NonNull List<ToolMetadata> semanticCandidates) {
        return IntStream.range(0, semanticCandidates.size())
                .mapToObj(index -> index + ":" + semanticCandidates.get(index).name())
                .toList();
    }

    private static @NonNull List<String> scoredCandidateSummaries(@NonNull List<ScoredTool> scoredCandidates) {
        return scoredCandidates.stream()
                .map(scored -> scored.tool().name()
                        + "[rank=" + scored.rankPosition()
                        + ",total=" + format(scored.score().total())
                        + ",semantic=" + format(scored.score().semanticScore())
                        + ",priority=" + format(scored.score().priorityBoost())
                        + ",latencyPenalty=" + format(scored.score().latencyPenalty())
                        + ",costPenalty=" + format(scored.score().costPenalty())
                        + "]")
                .toList();
    }

    private static @NonNull String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }

    private static @NonNull String preview(@NonNull String userInput) {
        String normalized = userInput.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= MAX_QUERY_PREVIEW_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, MAX_QUERY_PREVIEW_LENGTH - 3) + "...";
    }

    private record ToolScore(
            double total, double semanticScore, double priorityBoost, double latencyPenalty, double costPenalty) {}

    private record ScoredTool(
            @NonNull ToolMetadata tool, @NonNull ToolScore score, int rankPosition) {}
}
