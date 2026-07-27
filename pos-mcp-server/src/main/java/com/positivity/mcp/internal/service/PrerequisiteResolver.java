package com.positivity.mcp.internal.service;

import java.util.List;
import java.util.Set;
import org.jspecify.annotations.NonNull;

/**
 * Resolves the missing prerequisites of a tool the agent wants to call (rung 2 of the answer
 * resolution ladder). For each required param absent from the known request context, returns the
 * tool that can produce it. Resolution is bounded to a single hop — deeper chains fall through to
 * rung 3.
 */
public interface PrerequisiteResolver {

    /**
     * @param toolName    the tool the agent intends to call
     * @param knownParams param names already available from the request context
     * @return one entry per required param that is missing, each with its producing tool
     */
    @NonNull
    List<MissingParam> missingFor(@NonNull String toolName, @NonNull Set<String> knownParams);

    /** A required param the tool lacks, and the tool that can supply it. */
    record MissingParam(
            @NonNull String param,
            @NonNull String producingTool,
            @NonNull String producingField) {}
}
