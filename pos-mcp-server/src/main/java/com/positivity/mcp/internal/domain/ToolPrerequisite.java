package com.positivity.mcp.internal.domain;

import org.jspecify.annotations.NonNull;

/**
 * A one-hop prerequisite edge (rung 2 of the answer resolution ladder): {@code toolName} needs
 * {@code requiredParam}, which {@code producingTool} can supply via {@code producingField} on its
 * result.
 */
public record ToolPrerequisite(
        @NonNull String toolName,
        @NonNull String requiredParam,
        @NonNull String producingTool,
        @NonNull String producingField) {}
