package com.positivity.mcp.internal.service;

import com.positivity.mcp.internal.domain.ToolMetadata;
import com.positivity.mcp.internal.domain.ToolSelectionContext;
import com.positivity.mcp.internal.repository.ToolMetadataRepository;
import dev.langchain4j.model.embedding.EmbeddingModel;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
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
    private static final int MAX_QUERY_PREVIEW_LENGTH = 160;
    private static final String ROLE_ADMIN = "ROLE_ADMIN";
    private static final String ADMIN_FACADE_TOOL = "AdminFacadeTool";
    private static final Set<String> ADMIN_QUERY_KEYWORDS = Set.of(
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
    private static final Set<String> ADMIN_QUERY_PHRASES = Set.of(
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
        String registryRole = ToolRegistryRoleMapper.normalize(context.role());
        if (topK <= 0) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(
                        "MCP tool lookup skipped role={} registryRole={} workflow={} topK={} reason=non-positive-topk queryPreview=\"{}\"",
                        context.role(),
                        registryRole,
                        context.workflowState(),
                        topK,
                        preview(context.userInput()));
            }
            return List.of();
        }

        List<ToolMetadata> gatedTools = repository.findEnabledByRoleAndWorkflow(registryRole, context.workflowState());

        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(
                    "MCP tool lookup start role={} registryRole={} workflow={} topK={} gatedToolCount={} gatedTools={} queryPreview=\"{}\"",
                    context.role(),
                    registryRole,
                    context.workflowState(),
                    topK,
                    gatedTools.size(),
                    toolNames(gatedTools),
                    preview(context.userInput()));
        }

        if (gatedTools.isEmpty()) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(
                        "MCP tool lookup no gated tools role={} registryRole={} workflow={} queryPreview=\"{}\"",
                        context.role(),
                        registryRole,
                        context.workflowState(),
                        preview(context.userInput()));
            }
            return List.of();
        }

        List<ToolMetadata> adminFastPathSelection = adminFastPathSelection(context, gatedTools);
        if (!adminFastPathSelection.isEmpty()) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(
                        "MCP tool lookup fast-path result role={} registryRole={} workflow={} selectedTools={} queryPreview=\"{}\"",
                        context.role(),
                        registryRole,
                        context.workflowState(),
                        toolNames(adminFastPathSelection),
                        preview(context.userInput()));
            }
            return adminFastPathSelection;
        }

        float[] embedding = embeddingModel.embed(context.userInput()).content().vector();
        int semanticLimit = Math.max(topK, 10);

        // Phase 2 fix: gated ANN — only tools authorized for this role+workflow enter
        // the
        // ranking window. Unauthorized tools can never displace authorized ones.
        List<ToolMetadata> semanticCandidates = repository.findTopKByEmbeddingForRole(
                embedding, semanticLimit, registryRole, context.workflowState());

        if (semanticCandidates.isEmpty()) {
            // Fallback: tools have no embeddings yet; return highest-priority gated tools
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(
                        "MCP tool scoring no-embedding fallback role={} registryRole={} workflow={} returning top-{} by priority",
                        context.role(),
                        registryRole,
                        context.workflowState(),
                        topK);
            }
            return gatedTools.stream()
                    .sorted(Comparator.comparingDouble(ToolMetadata::priority)
                            .reversed()
                            .thenComparing(ToolMetadata::name))
                    .limit(topK)
                    .toList();
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
                    "MCP tool scoring role={} registryRole={} workflow={} topK={} gatedTools={} semanticCandidates={} scoredCandidates={}",
                    context.role(),
                    registryRole,
                    context.workflowState(),
                    topK,
                    toolNames(gatedTools),
                    semanticCandidateSummaries(semanticCandidates),
                    scoredCandidateSummaries(scoredCandidates));
        }

        List<ToolMetadata> selectedTools =
                scoredCandidates.stream().limit(topK).map(ScoredTool::tool).toList();
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(
                    "MCP tool lookup result role={} registryRole={} workflow={} selectedTools={} queryPreview=\"{}\"",
                    context.role(),
                    registryRole,
                    context.workflowState(),
                    toolNames(selectedTools),
                    preview(context.userInput()));
        }
        return selectedTools;
    }

    private @NonNull List<ToolMetadata> adminFastPathSelection(
            @NonNull ToolSelectionContext context, @NonNull List<ToolMetadata> gatedTools) {
        if (!ROLE_ADMIN.equals(context.role())) {
            return List.of();
        }

        Set<String> matchedTerms = matchedAdminQueryTerms(context.userInput());
        if (matchedTerms.isEmpty()) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(
                        "MCP tool fast-path skipped role={} workflow={} reason=no-admin-keywords queryPreview=\"{}\"",
                        context.role(),
                        context.workflowState(),
                        preview(context.userInput()));
            }
            return List.of();
        }

        List<ToolMetadata> adminTools = gatedTools.stream()
                .filter(tool -> ADMIN_FACADE_TOOL.equals(tool.name()))
                .toList();
        if (!adminTools.isEmpty()) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(
                        "MCP tool fast-path matched role={} workflow={} tool={} matchedTerms={} queryPreview=\"{}\"",
                        context.role(),
                        context.workflowState(),
                        ADMIN_FACADE_TOOL,
                        matchedTerms,
                        preview(context.userInput()));
            }
        } else {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(
                        "MCP tool fast-path eligible but admin tool unavailable role={} workflow={} matchedTerms={} gatedTools={}",
                        context.role(),
                        context.workflowState(),
                        matchedTerms,
                        toolNames(gatedTools));
            }
        }
        return adminTools;
    }

    private @NonNull Set<String> matchedAdminQueryTerms(@NonNull String userInput) {
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
