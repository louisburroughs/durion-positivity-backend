package com.positivity.mcp.internal.service;

import com.positivity.mcp.internal.domain.ToolMetadata;
import com.positivity.mcp.internal.domain.ToolSelectionContext;
import com.positivity.mcp.internal.repository.ToolMetadataRepository;
import dev.langchain4j.model.embedding.EmbeddingModel;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
public class ToolRegistryService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ToolRegistryService.class);
    private static final String ROLE_ADMIN = "ROLE_ADMIN";
    private static final String ADMIN_FACADE_TOOL = "AdminFacadeTool";
    private static final Set<String> ADMIN_QUERY_KEYWORDS =
            Set.of(
                    "user",
                    "users",
                    "role",
                    "roles",
                    "permission",
                    "permissions",
                    "access",
                    "account",
                    "accounts",
                    "audit",
                    "audits",
                    "registered",
                    "registration",
                    "login",
                    "logins");
    private static final Set<String> ADMIN_QUERY_PHRASES =
            Set.of(
                    "who has access",
                    "who can access",
                    "audit log",
                    "access review",
                    "user count",
                    "registered users",
                    "account state");

    private final ToolMetadataRepository repository;
    private final EmbeddingModel embeddingModel;
    private final ToolScorer scorer;

    public ToolRegistryService(@NonNull ToolMetadataRepository repository, @NonNull EmbeddingModel embeddingModel) {
        this.repository = repository;
        this.embeddingModel = embeddingModel;
        this.scorer = new ToolScorer();
    }

    public @NonNull List<ToolMetadata> resolveCandidateTools(@NonNull ToolSelectionContext context, int topK) {
        if (topK <= 0) {
            return List.of();
        }

        List<ToolMetadata> gatedTools =
                repository.findEnabledByRoleAndWorkflow(context.role(), context.workflowState());

        if (gatedTools.isEmpty()) {
            return List.of();
        }

        List<ToolMetadata> adminFastPathSelection = adminFastPathSelection(context, gatedTools);
        if (!adminFastPathSelection.isEmpty()) {
            return adminFastPathSelection;
        }

        float[] embedding = embeddingModel.embed(context.userInput()).content().vector();
        int semanticLimit = Math.max(topK, 10);
        List<ToolMetadata> semanticCandidates = repository.findTopKByEmbedding(embedding, semanticLimit);

        Set<UUID> gatedIds = new HashSet<>();
        for (ToolMetadata gated : gatedTools) {
            gatedIds.add(gated.id());
        }

        List<ScoredTool> gatedScoredCandidates = IntStream.range(0, semanticCandidates.size())
                .mapToObj(index -> new ScoredTool(
                        semanticCandidates.get(index), scorer.score(semanticCandidates.get(index), index), index))
                .filter(scored -> gatedIds.contains(scored.tool().id()))
                .sorted(Comparator.comparingDouble((ScoredTool scored) -> scored.score().total())
                        .reversed()
                        .thenComparingInt(ScoredTool::rankPosition)
                        .thenComparing(st -> st.tool().name()))
                .toList();

        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(
                    "MCP tool scoring role={} workflow={} topK={} gatedTools={} semanticCandidates={} gatedCandidateScores={}",
                    context.role(),
                    context.workflowState(),
                    topK,
                    toolNames(gatedTools),
                    semanticCandidateSummaries(semanticCandidates),
                    scoredCandidateSummaries(gatedScoredCandidates));
        }

        return gatedScoredCandidates.stream().limit(topK).map(ScoredTool::tool).toList();
    }

    private @NonNull List<ToolMetadata> adminFastPathSelection(
            @NonNull ToolSelectionContext context, @NonNull List<ToolMetadata> gatedTools) {
        if (!ROLE_ADMIN.equals(context.role()) || !containsAdminQueryKeyword(context.userInput())) {
            return List.of();
        }

        List<ToolMetadata> adminTools = gatedTools.stream()
                .filter(tool -> ADMIN_FACADE_TOOL.equals(tool.name()))
                .toList();
        if (!adminTools.isEmpty()) {
            LOGGER.debug(
                    "MCP tool fast-path matched role={} workflow={} tool={} query=\"{}\"",
                    context.role(),
                    context.workflowState(),
                    ADMIN_FACADE_TOOL,
                    context.userInput());
        }
        return adminTools;
    }

    private boolean containsAdminQueryKeyword(@NonNull String userInput) {
        String normalized = userInput.toLowerCase(Locale.ROOT);
        for (String keyword : ADMIN_QUERY_KEYWORDS) {
            if (normalized.matches(".*\\b" + keyword + "\\b.*")) {
                return true;
            }
        }
        for (String phrase : ADMIN_QUERY_PHRASES) {
            if (normalized.contains(phrase)) {
                return true;
            }
        }
        return false;
    }

    static final class ToolScorer {

        @NonNull ToolScore score(@NonNull ToolMetadata tool, int rankPosition) {
            double semanticScore = 1.0 / (rankPosition + 1);
            double priorityBoost = tool.priority();
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

    private record ToolScore(
            double total, double semanticScore, double priorityBoost, double latencyPenalty, double costPenalty) {}

    private record ScoredTool(@NonNull ToolMetadata tool, @NonNull ToolScore score, int rankPosition) {}
}
