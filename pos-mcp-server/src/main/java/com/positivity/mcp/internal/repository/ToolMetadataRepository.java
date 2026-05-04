package com.positivity.mcp.internal.repository;

import com.positivity.mcp.internal.domain.ToolMetadata;
import java.util.List;
import org.jspecify.annotations.NonNull;

public interface ToolMetadataRepository {

    /**
     * Returns all role names currently defined in the mcp_role table.
     */
    @NonNull
    List<String> findAllRoleNames();

    @NonNull
    List<ToolMetadata> findEnabledByRoleAndWorkflow(@NonNull String role, @NonNull String workflowState);

    @NonNull
    List<ToolMetadata> findTopKByEmbedding(float @NonNull [] embedding, int limit);
}
