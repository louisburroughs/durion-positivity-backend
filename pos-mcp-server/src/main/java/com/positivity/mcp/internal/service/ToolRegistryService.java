package com.positivity.mcp.internal.service;

import com.positivity.mcp.internal.domain.ToolMetadata;
import com.positivity.mcp.internal.domain.ToolSelectionContext;
import com.positivity.mcp.internal.repository.ToolMetadataRepository;
import dev.langchain4j.model.embedding.EmbeddingModel;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.jspecify.annotations.NonNull;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
public class ToolRegistryService {

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

        float[] embedding = embeddingModel.embed(context.userInput()).content().vector();
        int semanticLimit = Math.max(topK, 10);
        List<ToolMetadata> semanticCandidates = repository.findTopKByEmbedding(embedding, semanticLimit);

        Set<UUID> gatedIds = new HashSet<>();
        for (ToolMetadata gated : gatedTools) {
            gatedIds.add(gated.id());
        }

        return IntStream.range(0, semanticCandidates.size())
                .mapToObj(index -> new ScoredTool(
                        semanticCandidates.get(index), scorer.score(semanticCandidates.get(index), index), index))
                .filter(scored -> gatedIds.contains(scored.tool().id()))
                .sorted(Comparator.comparingDouble(ScoredTool::score)
                        .reversed()
                        .thenComparingInt(ScoredTool::rankPosition)
                        .thenComparing(st -> st.tool().name()))
                .limit(topK)
                .map(ScoredTool::tool)
                .toList();
    }

    static final class ToolScorer {

        double score(@NonNull ToolMetadata tool, int rankPosition) {
            double semanticScore = 1.0 / (rankPosition + 1);
            double priorityBoost = tool.priority();
            double latencyPenalty = Math.min(tool.avgLatencyMs() / 1000.0, 1.0) * 0.2;
            double costPenalty =
                    switch (tool.costLevel().toLowerCase()) {
                        case "high" -> 0.2;
                        case "medium" -> 0.1;
                        default -> 0.0;
                    };
            return semanticScore + priorityBoost - latencyPenalty - costPenalty;
        }
    }

    private record ScoredTool(@NonNull ToolMetadata tool, double score, int rankPosition) {}
}
