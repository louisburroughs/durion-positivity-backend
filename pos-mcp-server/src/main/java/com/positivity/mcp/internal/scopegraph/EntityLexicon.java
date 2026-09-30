package com.positivity.mcp.internal.scopegraph;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;

/**
 * ADR-0069 §3.1: the entity lexicon ({@code scope-graph/entities.yaml}), the one new curated source
 * of truth behind the scope graph.
 *
 * @param domainScopes tool-catalog domain → RAG scope, for the domains the two vocabularies spell
 *     differently (§2, Domain row)
 * @param entities the curated entities, in file order
 * @param unscopedTools enabled tools that deliberately act on no entity (date windows, glossary,
 *     web search); the explicit exemption from the "every tool acts on an entity" rule
 */
public record EntityLexicon(
        @NonNull Map<String, String> domainScopes,
        @NonNull List<EntityDefinition> entities,
        @NonNull List<String> unscopedTools) {

    /** The languages every entity must carry at least one term in (ADR-0069 §3). */
    public static final List<String> REQUIRED_LANGUAGES = List.of("en", "fr", "es");

    public EntityLexicon {
        domainScopes = Map.copyOf(domainScopes);
        entities = List.copyOf(entities);
        unscopedTools = List.copyOf(unscopedTools);
    }

    /**
     * @param key the curated entity key ({@code workorder}, {@code purchase-order}, ...)
     * @param domain the owning domain, in the tool-catalog vocabulary
     * @param terms language → phrases that denote the entity
     * @param identifiers regexes that recognise an identifier of the entity in a message
     * @param relatesTo labelled relations to other entities
     * @param schemas {@code domain:SchemaName} references to the OpenAPI schemas that represent the
     *     entity; also the schemas whose status enum gives the entity's lifecycle states
     * @param schemaPatterns regexes over {@code domain:SchemaName}, so an entity with many DTOs does
     *     not have to list each one
     * @param facadeTools the facade tools that act on the entity, each with its declared access
     * @param screens the {@code mcp_screen_registry.screen_key}s that show the entity
     */
    public record EntityDefinition(
            @NonNull String key,
            @NonNull String domain,
            @NonNull Map<String, List<String>> terms,
            @NonNull List<Identifier> identifiers,
            @NonNull List<Relation> relatesTo,
            @NonNull List<String> schemas,
            @NonNull List<String> schemaPatterns,
            @NonNull List<FacadeToolRef> facadeTools,
            @NonNull List<String> screens) {

        public EntityDefinition {
            terms = Map.copyOf(terms);
            identifiers = List.copyOf(identifiers);
            relatesTo = List.copyOf(relatesTo);
            schemas = List.copyOf(schemas);
            schemaPatterns = List.copyOf(schemaPatterns);
            facadeTools = List.copyOf(facadeTools);
            screens = List.copyOf(screens);
        }
    }

    /** @param pattern a regex, checked to compile when the lexicon is loaded */
    public record Identifier(@NonNull String key, @NonNull String pattern) {}

    /** @param label the relation as read from this entity ({@code promoted_from}, {@code billed_by}) */
    public record Relation(@NonNull String entity, @NonNull String label) {}

    /**
     * @param access declared, because a facade row has no HTTP method or schema to derive it from
     *     (ADR-0069 §2, {@code ACTS_ON} row)
     */
    public record FacadeToolRef(
            @NonNull String tool, @NonNull Access access) {}
}
