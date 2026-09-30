package com.positivity.mcp.internal.scopegraph;

import org.jspecify.annotations.NonNull;

/**
 * One build-time validation finding (ADR-0069 §3): what kind of rule was broken and by what.
 *
 * @param subject the key of the offending thing: a tool name, a {@code document_id}, or {@code
 *     owner -> reference} for a reference that does not resolve
 */
public record ScopeGraphFinding(@NonNull Kind kind, @NonNull String subject) {

    /**
     * The validation rules. A {@linkplain #strict() strict} kind fails the module test; at runtime
     * every kind is logged and degraded, never thrown (spec §2.5).
     */
    public enum Kind {
        /** An enabled facade tool has no {@code ACTS_ON} edge and is not in {@code unscoped_tools}. */
        FACADE_TOOL_WITHOUT_ENTITY(true),
        /**
         * An enabled discovered operation matches no lexicon schema. Not strict: it attaches to its
         * domain only and is counted ({@code mcp.scope_graph.unmapped_tools}), so a new service
         * degrades to today's behaviour instead of failing the build.
         */
        DISCOVERED_TOOL_UNMAPPED(false),
        /** A RAG document declares no entity and not {@code entities: [none]}. */
        RAG_DOC_WITHOUT_ENTITIES(true),
        /** A relation or a RAG document names an entity the lexicon does not define. */
        UNKNOWN_ENTITY(true),
        /** An entity's domain, or a {@code domain_scopes} key, is no tool, screen or spec domain and no RAG scope. */
        UNKNOWN_DOMAIN(true),
        /** A {@code domain_scopes} value is not the {@code rag_scope} of any preload document. */
        UNKNOWN_RAG_SCOPE(true),
        /** A lexicon {@code schemas} entry names no schema of the indexed specs. */
        UNKNOWN_SCHEMA(true),
        /** A lexicon {@code schema_patterns} regex matches no schema. */
        SCHEMA_PATTERN_WITHOUT_MATCH(true),
        /** A lexicon {@code facade_tools} entry names no enabled facade tool. */
        UNKNOWN_FACADE_TOOL(true),
        /** An {@code unscoped_tools} entry names no enabled tool. */
        UNKNOWN_UNSCOPED_TOOL(true),
        /** A lexicon {@code screens} entry names no registered screen. */
        UNKNOWN_SCREEN(true),
        /** An entity has no term in one of en, fr, es. */
        MISSING_LANGUAGE_TERMS(true),
        /**
         * A prerequisite row names a tool that is not an enabled catalog row. Not strict: the seeded
         * rows name discovered operations, which exist only after discovery has run.
         */
        PREREQUISITE_TOOL_MISSING(false);

        private final boolean strict;

        Kind(boolean strict) {
            this.strict = strict;
        }

        public boolean strict() {
            return strict;
        }
    }

    public boolean strict() {
        return kind.strict();
    }
}
