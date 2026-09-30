package com.positivity.mcp.internal.scopegraph;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/** A node of the scope graph: its identity and, for the types that have them, its attributes. */
public record ScopeNode(@NonNull NodeId id, @Nullable NodeAttributes attributes) {}
