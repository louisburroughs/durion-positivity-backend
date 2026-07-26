package com.positivity.mcp.internal.repository;

import com.positivity.mcp.internal.domain.ToolPrerequisite;
import java.util.List;
import org.jspecify.annotations.NonNull;

/** Lookup of prerequisite edges for a tool (rung 2 of the answer resolution ladder). */
public interface ToolPrerequisiteRepository {

    /** All prerequisite edges declared for {@code toolName}; empty when the tool has none. */
    @NonNull
    List<ToolPrerequisite> findByToolName(@NonNull String toolName);
}
