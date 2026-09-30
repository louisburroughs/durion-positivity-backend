package com.positivity.mcp.internal.scopegraph;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jspecify.annotations.NonNull;

/**
 * What the scope graph needs from the OpenAPI specs (ADR-0069 §2, spec §2.2): which component
 * schemas each discovered operation exchanges, and the status enums of those schemas.
 *
 * <p>Aggregate discovery merges only the paths of each service spec, so component schemas do not
 * survive into the aggregate. The index is therefore built per service spec, before merging, and
 * keyed by the tool's domain (the gateway routing prefix, the value {@code mcp_tool.domain} holds
 * for a discovered operation). A schema is always named {@code domain:SchemaName}, because schema
 * names are not unique across services.
 */
public final class OpenApiSchemaIndex {

    private static final OpenApiSchemaIndex EMPTY = new OpenApiSchemaIndex(List.of());

    private final Map<String, DomainIndex> byDomain;
    private final Map<String, Set<String>> operationSchemas;

    /**
     * The index of one service spec.
     *
     * @param domain the routing prefix without its slash
     * @param operationSchemas tool name → the schema names (unqualified) of its request body and 2xx
     *     response
     * @param schemaNames every component schema name of the spec, the set a lexicon reference must
     *     resolve against
     * @param enums schema name → property name → enum values, for {@code status} / {@code state}
     *     properties
     */
    public record DomainIndex(
            @NonNull String domain,
            @NonNull Map<String, Set<String>> operationSchemas,
            @NonNull Set<String> schemaNames,
            @NonNull Map<String, Map<String, List<String>>> enums) {

        public DomainIndex {
            operationSchemas = Map.copyOf(operationSchemas);
            schemaNames = Set.copyOf(schemaNames);
            enums = Map.copyOf(enums);
        }
    }

    public OpenApiSchemaIndex(@NonNull Collection<DomainIndex> domains) {
        Map<String, DomainIndex> indexed = new TreeMap<>();
        Map<String, Set<String>> operations = new LinkedHashMap<>();
        for (DomainIndex domain : domains) {
            indexed.put(domain.domain(), domain);
        }
        for (DomainIndex domain : indexed.values()) {
            domain.operationSchemas().forEach((tool, names) -> {
                Set<String> qualified = operations.computeIfAbsent(tool, ignored -> new TreeSet<>());
                names.forEach(name -> qualified.add(qualify(domain.domain(), name)));
            });
        }
        this.byDomain = java.util.Collections.unmodifiableMap(indexed);
        this.operationSchemas = java.util.Collections.unmodifiableMap(operations);
    }

    public static @NonNull OpenApiSchemaIndex empty() {
        return EMPTY;
    }

    /** {@code domain:SchemaName}. */
    public static @NonNull String qualify(@NonNull String domain, @NonNull String schemaName) {
        return domain + ":" + schemaName;
    }

    public boolean isEmpty() {
        return byDomain.isEmpty();
    }

    /** The domains a spec was indexed for, sorted. */
    public @NonNull Set<String> domains() {
        return byDomain.keySet();
    }

    /** The qualified schema names of a tool's request body and 2xx response; empty when unknown. */
    public @NonNull Set<String> operationSchemas(@NonNull String toolName) {
        Set<String> names = operationSchemas.get(toolName);
        return names == null ? Set.of() : java.util.Collections.unmodifiableSet(names);
    }

    /** Every component schema of every indexed domain, qualified and sorted. */
    public @NonNull Set<String> schemaNames() {
        Set<String> names = new TreeSet<>();
        byDomain.values()
                .forEach(domain -> domain.schemaNames().forEach(name -> names.add(qualify(domain.domain(), name))));
        return names;
    }

    public boolean hasSchema(@NonNull String qualifiedName) {
        int colon = qualifiedName.indexOf(':');
        if (colon <= 0) {
            return false;
        }
        DomainIndex domain = byDomain.get(qualifiedName.substring(0, colon));
        return domain != null && domain.schemaNames().contains(qualifiedName.substring(colon + 1));
    }

    /** Property name → enum values of a schema's {@code status} / {@code state} properties. */
    public @NonNull Map<String, List<String>> enums(@NonNull String qualifiedName) {
        int colon = qualifiedName.indexOf(':');
        if (colon <= 0) {
            return Map.of();
        }
        DomainIndex domain = byDomain.get(qualifiedName.substring(0, colon));
        if (domain == null) {
            return Map.of();
        }
        return domain.enums().getOrDefault(qualifiedName.substring(colon + 1), Map.of());
    }
}
