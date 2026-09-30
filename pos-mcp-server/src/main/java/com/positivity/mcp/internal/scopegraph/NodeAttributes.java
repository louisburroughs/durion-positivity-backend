package com.positivity.mcp.internal.scopegraph;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * What a node carries beyond its identity. Only the node types a later consumer has to filter or
 * render have attributes (ADR-0069 §5.3, §7); the rest are identity only.
 */
public sealed interface NodeAttributes {

    /** Where a tool row came from; the two sources are gated differently (§5.3). */
    enum ToolSource {
        /** A hand-written facade ({@code mcp_tool.source <> 'openapi'}): AND within a permission group. */
        FACADE,
        /** An OpenAPI-discovered operation: any one permission code qualifies. */
        DISCOVERED
    }

    /**
     * @param domain the {@code mcp_tool.domain} value
     * @param permissionGroups {@code permission_group} → the codes of that group; empty means the
     *     tool has no permission rows and stays excluded for every caller (§5.3)
     */
    record Tool(
            @NonNull ToolSource source,
            @NonNull String domain,
            @Nullable String httpMethod,
            @NonNull Map<String, Set<String>> permissionGroups)
            implements NodeAttributes {
        public Tool {
            permissionGroups = Map.copyOf(permissionGroups);
        }
    }

    /**
     * @param ragScope the normalized {@code rag_scope}
     * @param requiredPermissions as enforced by {@code PermissionAwareMetadataFilter}
     * @param platformWide {@code entities: [none]}: deliberately about no entity
     */
    record RagDoc(@NonNull String ragScope, @NonNull List<String> requiredPermissions, boolean platformWide)
            implements NodeAttributes {
        public RagDoc {
            requiredPermissions = List.copyOf(requiredPermissions);
        }
    }

    /** @param requiredPerm the single nullable {@code mcp_screen_registry.required_perm} */
    record Screen(
            @NonNull String title,
            @NonNull String urlTemplate,
            @NonNull String domain,
            @Nullable String requiredPerm) implements NodeAttributes {}

    /**
     * @param language {@code en}, {@code fr} or {@code es}
     * @param phrase the phrase as written in its source
     * @param glossary true when the phrase comes from {@code BusinessGlossary}, not the lexicon
     */
    record Term(@NonNull String language, @NonNull String phrase, boolean glossary) implements NodeAttributes {}

    /** @param pattern the regex, already checked to compile */
    record IdentifierPattern(@NonNull String pattern) implements NodeAttributes {}
}
