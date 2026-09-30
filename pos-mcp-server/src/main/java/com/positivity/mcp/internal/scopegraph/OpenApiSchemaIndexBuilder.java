package com.positivity.mcp.internal.scopegraph;

import com.positivity.mcp.internal.scopegraph.OpenApiSchemaIndex.DomainIndex;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Builds the {@link OpenApiSchemaIndex} of one parsed spec (spec §2.2).
 *
 * <p>The spec must be parsed <strong>without</strong> {@code resolveFully} ({@link
 * #parseUnresolved}). Discovery parses with it, and for the OpenAPI 3.1 documents the services
 * publish the resolver replaces every {@code $ref} by an unnamed copy of the component schema: the
 * operation then no longer says which schema it exchanges, and nothing recovers the name. A schema
 * is therefore named by its {@code $ref} only. An operation whose body is written inline names
 * nothing, and the tool then attaches to its domain only (ADR-0069 §3); it is never mis-attached.
 *
 * <p>Tool names are not derived here. The caller supplies a {@link ToolNamer}, so the index is keyed
 * with exactly the derivation discovery persists to {@code mcp_tool.name}.
 */
public final class OpenApiSchemaIndexBuilder {

    private static final String JSON = "application/json";
    private static final List<String> SUCCESS_CODES = List.of("200", "201", "202");
    /** The array properties that mark a page or list envelope. */
    private static final List<String> ENVELOPE_PROPERTIES = List.of("content", "items");

    private static final List<String> STATE_SUFFIXES = List.of("status", "state");

    private OpenApiSchemaIndexBuilder() {}

    /**
     * Parses a raw spec leaving every {@code $ref} in place, which is what {@link #build} needs.
     * Returns null when the text is not an OpenAPI document.
     */
    public static @Nullable OpenAPI parseUnresolved(@NonNull String rawSpec) {
        ParseOptions options = new ParseOptions();
        options.setResolve(false);
        options.setResolveFully(false);
        return new OpenAPIV3Parser().readContents(rawSpec, null, options).getOpenAPI();
    }

    /** Names the tool an operation is registered as; the same derivation discovery uses. */
    @FunctionalInterface
    public interface ToolNamer {
        @NonNull
        String toolName(@NonNull String path, @NonNull Operation operation);
    }

    /** Indexes one service spec, every operation of which belongs to {@code domain}. */
    public static @NonNull DomainIndex build(@NonNull OpenAPI spec, @NonNull String domain, @NonNull ToolNamer namer) {
        List<DomainIndex> indexes = buildByPathDomain(spec, path -> domain, namer);
        // A spec without paths still contributes its schema names and enums.
        return indexes.isEmpty() ? new Names(spec).index(domain, Map.of()) : indexes.getFirst();
    }

    /**
     * Indexes a single merged aggregate, where the domain differs per path. Every domain gets the
     * whole {@code components.schemas} of the document, since one document has one schema namespace.
     */
    public static @NonNull List<DomainIndex> buildByPathDomain(
            @NonNull OpenAPI spec, @NonNull UnaryOperator<String> domainOfPath, @NonNull ToolNamer namer) {
        Names names = new Names(spec);
        Map<String, Map<String, Set<String>>> operationsByDomain = new TreeMap<>();
        if (spec.getPaths() != null) {
            spec.getPaths().forEach((path, item) -> {
                if (item == null) {
                    return;
                }
                Map<String, Set<String>> operations =
                        operationsByDomain.computeIfAbsent(domainOfPath.apply(path), ignored -> new LinkedHashMap<>());
                for (Operation operation : operations(item)) {
                    Set<String> schemas = new TreeSet<>();
                    names.collect(requestSchema(operation), schemas);
                    names.collect(successResponseSchema(operation), schemas);
                    operations.put(namer.toolName(path, operation), schemas);
                }
            });
        }
        List<DomainIndex> indexes = new ArrayList<>();
        operationsByDomain.forEach((domain, operations) -> indexes.add(names.index(domain, operations)));
        return indexes;
    }

    /** The same five methods discovery registers ({@code OpenApiToolMapper}). */
    private static List<Operation> operations(PathItem item) {
        List<Operation> operations = new ArrayList<>();
        for (Operation operation :
                new Operation[] {item.getGet(), item.getPost(), item.getPut(), item.getDelete(), item.getPatch()}) {
            if (operation != null) {
                operations.add(operation);
            }
        }
        return operations;
    }

    private static @Nullable Schema<?> requestSchema(Operation operation) {
        return operation.getRequestBody() == null
                ? null
                : jsonSchema(operation.getRequestBody().getContent());
    }

    /** The first success response, in the order {@code OpenApiToolMapper} picks it: 200, 201, 202, any 2xx. */
    private static @Nullable Schema<?> successResponseSchema(Operation operation) {
        ApiResponses responses = operation.getResponses();
        if (responses == null) {
            return null;
        }
        for (String code : SUCCESS_CODES) {
            ApiResponse response = responses.get(code);
            if (response != null) {
                return jsonSchema(response.getContent());
            }
        }
        for (Map.Entry<String, ApiResponse> entry : responses.entrySet()) {
            String code = entry.getKey();
            if (code != null && code.length() == 3 && code.charAt(0) == '2' && entry.getValue() != null) {
                return jsonSchema(entry.getValue().getContent());
            }
        }
        return null;
    }

    private static @Nullable Schema<?> jsonSchema(@Nullable Content content) {
        if (content == null || content.isEmpty()) {
            return null;
        }
        MediaType media = content.get(JSON);
        if (media == null) {
            media = content.values().iterator().next();
        }
        return media == null ? null : media.getSchema();
    }

    /** Resolves schema names against one spec's {@code components.schemas}. */
    private static final class Names {

        private final Map<String, Schema<?>> components = new TreeMap<>();

        @SuppressWarnings("rawtypes")
        Names(OpenAPI spec) {
            if (spec.getComponents() != null && spec.getComponents().getSchemas() != null) {
                for (Map.Entry<String, Schema> entry :
                        spec.getComponents().getSchemas().entrySet()) {
                    if (entry.getValue() != null) {
                        components.put(entry.getKey(), entry.getValue());
                    }
                }
            }
        }

        DomainIndex index(String domain, Map<String, Set<String>> operations) {
            Map<String, Map<String, List<String>>> enums = new TreeMap<>();
            components.forEach((name, schema) -> {
                Map<String, List<String>> stateEnums = stateEnums(schema);
                if (!stateEnums.isEmpty()) {
                    enums.put(name, stateEnums);
                }
            });
            return new DomainIndex(domain, operations, components.keySet(), enums);
        }

        /**
         * Adds the names reachable from a request or response schema: through {@code $ref}, array
         * {@code items}, one level of {@code allOf} / {@code oneOf} / {@code anyOf}, and the element
         * of a page envelope. Deliberately shallow: the schemas an operation exchanges, not every
         * DTO nested inside them, or every tool would act on every entity.
         */
        void collect(@Nullable Schema<?> schema, Set<String> out) {
            Schema<?> current = schema;
            // Bounded, so an array-of-array-of-... spec cannot loop.
            for (int depth = 0; current != null && depth < 4; depth++) {
                String name = nameOf(current);
                Schema<?> resolved = name == null ? current : components.getOrDefault(name, current);
                if (name != null) {
                    out.add(name);
                }
                if (resolved.getItems() != null) {
                    current = resolved.getItems();
                    continue;
                }
                addComposedMembers(resolved, out);
                addEnvelopeElement(resolved, out);
                return;
            }
        }

        private void addComposedMembers(Schema<?> schema, Set<String> out) {
            for (List<Schema> members : composed(schema)) {
                for (Schema<?> member : members) {
                    String name = member == null ? null : nameOf(member);
                    if (name != null) {
                        out.add(name);
                    }
                }
            }
        }

        private void addEnvelopeElement(Schema<?> schema, Set<String> out) {
            if (schema.getProperties() == null) {
                return;
            }
            for (String property : ENVELOPE_PROPERTIES) {
                Schema<?> candidate = schema.getProperties().get(property);
                if (candidate == null || candidate.getItems() == null) {
                    continue;
                }
                String name = nameOf(candidate.getItems());
                if (name != null) {
                    out.add(name);
                }
            }
        }

        @SuppressWarnings("rawtypes")
        private static List<List<Schema>> composed(Schema<?> schema) {
            List<List<Schema>> groups = new ArrayList<>();
            if (schema.getAllOf() != null) {
                groups.add(schema.getAllOf());
            }
            if (schema.getOneOf() != null) {
                groups.add(schema.getOneOf());
            }
            if (schema.getAnyOf() != null) {
                groups.add(schema.getAnyOf());
            }
            return groups;
        }

        private static @Nullable String nameOf(Schema<?> schema) {
            String ref = schema.get$ref();
            if (ref == null) {
                return null;
            }
            int slash = ref.lastIndexOf('/');
            return slash >= 0 ? ref.substring(slash + 1) : ref;
        }

        /** Enum values of the properties named {@code status} / {@code state} (suffix, any case). */
        private Map<String, List<String>> stateEnums(Schema<?> schema) {
            Map<String, List<String>> enums = new TreeMap<>();
            if (schema.getProperties() == null) {
                return enums;
            }
            schema.getProperties().forEach((property, propertySchema) -> {
                String lower = property.toLowerCase(Locale.ROOT);
                if (propertySchema == null || STATE_SUFFIXES.stream().noneMatch(lower::endsWith)) {
                    return;
                }
                List<String> values = enumValues(propertySchema);
                if (!values.isEmpty()) {
                    enums.put(property, values);
                }
            });
            return enums;
        }

        private List<String> enumValues(Schema<?> property) {
            Schema<?> resolved = property;
            String name = nameOf(property);
            if (name != null) {
                resolved = components.getOrDefault(name, property);
            }
            if (resolved.getEnum() == null) {
                return List.of();
            }
            return resolved.getEnum().stream()
                    .filter(java.util.Objects::nonNull)
                    .map(String::valueOf)
                    .toList();
        }
    }
}
