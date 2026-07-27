package com.positivity.mcp.internal.service;

import com.positivity.mcp.internal.repository.ToolPrerequisiteRepository;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * Default one-hop prerequisite resolver. Looks up the tool's declared edges and keeps only those
 * whose required param is not already known.
 */
@Service
@Profile({"!test", "openapi"})
public class PrerequisiteResolverImpl implements PrerequisiteResolver {

    private final ToolPrerequisiteRepository repository;

    public PrerequisiteResolverImpl(@NonNull ToolPrerequisiteRepository repository) {
        this.repository = repository;
    }

    @Override
    public @NonNull List<MissingParam> missingFor(@NonNull String toolName, @NonNull Set<String> knownParams) {
        return repository.findByToolName(toolName).stream()
                .filter(edge -> !knownParams.contains(edge.requiredParam()))
                .map(edge -> new MissingParam(edge.requiredParam(), edge.producingTool(), edge.producingField()))
                .toList();
    }
}
