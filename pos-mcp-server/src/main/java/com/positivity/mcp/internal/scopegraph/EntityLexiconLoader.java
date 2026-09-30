package com.positivity.mcp.internal.scopegraph;

import com.positivity.mcp.internal.scopegraph.EntityLexicon.EntityDefinition;
import com.positivity.mcp.internal.scopegraph.EntityLexicon.FacadeToolRef;
import com.positivity.mcp.internal.scopegraph.EntityLexicon.Identifier;
import com.positivity.mcp.internal.scopegraph.EntityLexicon.Relation;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * Reads {@code scope-graph/entities.yaml} into an {@link EntityLexicon} (ADR-0069 §3.1).
 *
 * <p>The loader checks shape only: required fields, types, duplicate keys, regexes that compile, and
 * no unknown field (a misspelt {@code facade_tool} would otherwise be silently ignored). Every error
 * names the entity it was found in. Whether a reference resolves (an entity, a tool, a schema) is
 * the builder's business, because that needs the other sources.
 */
public final class EntityLexiconLoader {

    /** Where the lexicon lives on the classpath. */
    public static final String CLASSPATH_LOCATION = "scope-graph/entities.yaml";

    private static final Set<String> ROOT_FIELDS = Set.of("domain_scopes", "entities", "unscoped_tools");
    private static final Set<String> ENTITY_FIELDS = Set.of(
            "key",
            "domain",
            "terms",
            "identifiers",
            "relates_to",
            "schemas",
            "schema_patterns",
            "facade_tools",
            "screens");
    private static final Pattern SCHEMA_REFERENCE = Pattern.compile("[^:\\s]+:[^:\\s]+");

    private EntityLexiconLoader() {}

    /** Loads the lexicon shipped with the module. */
    public static @NonNull EntityLexicon loadDefault() {
        return load(new ClassPathResource(CLASSPATH_LOCATION));
    }

    public static @NonNull EntityLexicon load(@NonNull Resource resource) {
        try (InputStream in = resource.getInputStream()) {
            return load(in);
        } catch (IOException exception) {
            throw new EntityLexiconException("Cannot read the entity lexicon " + resource.getDescription(), exception);
        }
    }

    public static @NonNull EntityLexicon load(@NonNull InputStream in) {
        Object root;
        try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            root = new Yaml(new SafeConstructor(new LoaderOptions())).load(reader);
        } catch (YAMLException | IOException exception) {
            throw new EntityLexiconException(
                    "The entity lexicon is not valid YAML: " + exception.getMessage(), exception);
        }
        if (!(root instanceof Map<?, ?> rootMap)) {
            throw new EntityLexiconException("The entity lexicon must be a mapping with an 'entities' list");
        }
        rejectUnknownFields(rootMap, ROOT_FIELDS, "the lexicon root");

        List<EntityDefinition> entities = new ArrayList<>();
        Set<String> seenKeys = new LinkedHashSet<>();
        List<?> rawEntities = list(rootMap.get("entities"), "the lexicon root", "entities");
        for (int index = 0; index < rawEntities.size(); index++) {
            EntityDefinition entity = entity(rawEntities.get(index), index);
            if (!seenKeys.add(entity.key())) {
                throw new EntityLexiconException("entity '" + entity.key() + "' is defined more than once");
            }
            entities.add(entity);
        }
        rejectConflictingIdentifiers(entities);
        return new EntityLexicon(
                stringMap(rootMap.get("domain_scopes"), "the lexicon root", "domain_scopes"),
                entities,
                strings(rootMap.get("unscoped_tools"), "the lexicon root", "unscoped_tools"));
    }

    /**
     * An identifier key is one graph node whatever entity lists it (a VIN may identify more than one
     * entity), so it can only carry one pattern.
     */
    private static void rejectConflictingIdentifiers(List<EntityDefinition> entities) {
        Map<String, String> patternByKey = new LinkedHashMap<>();
        Map<String, String> ownerByKey = new LinkedHashMap<>();
        for (EntityDefinition entity : entities) {
            for (Identifier identifier : entity.identifiers()) {
                String known = patternByKey.putIfAbsent(identifier.key(), identifier.pattern());
                if (known != null && !known.equals(identifier.pattern())) {
                    throw new EntityLexiconException("entity '" + entity.key() + "': identifier '" + identifier.key()
                            + "' is already defined by entity '" + ownerByKey.get(identifier.key())
                            + "' with a different pattern");
                }
                ownerByKey.putIfAbsent(identifier.key(), entity.key());
            }
        }
    }

    private static EntityDefinition entity(@Nullable Object raw, int index) {
        if (!(raw instanceof Map<?, ?> map)) {
            throw new EntityLexiconException("entities[" + index + "] must be a mapping");
        }
        Object rawKey = map.get("key");
        if (!(rawKey instanceof String key) || key.isBlank()) {
            throw new EntityLexiconException("entities[" + index + "] has no 'key'");
        }
        String where = "entity '" + key + "'";
        rejectUnknownFields(map, ENTITY_FIELDS, where);

        List<String> schemas = strings(map.get("schemas"), where, "schemas");
        for (String schema : schemas) {
            if (!SCHEMA_REFERENCE.matcher(schema).matches()) {
                throw new EntityLexiconException(where + ": schema '" + schema + "' must be written domain:SchemaName");
            }
        }
        List<String> schemaPatterns = strings(map.get("schema_patterns"), where, "schema_patterns");
        schemaPatterns.forEach(pattern -> compile(pattern, where, "schema_patterns"));

        return new EntityDefinition(
                key,
                requiredString(map.get("domain"), where, "domain"),
                terms(map.get("terms"), where),
                identifiers(map.get("identifiers"), where),
                relations(map.get("relates_to"), where),
                schemas,
                schemaPatterns,
                facadeTools(map.get("facade_tools"), where),
                strings(map.get("screens"), where, "screens"));
    }

    private static Map<String, List<String>> terms(@Nullable Object raw, String where) {
        if (raw == null) {
            return Map.of();
        }
        if (!(raw instanceof Map<?, ?> map)) {
            throw new EntityLexiconException(where + ": 'terms' must map a language to a list of phrases");
        }
        Map<String, List<String>> terms = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String language = String.valueOf(entry.getKey()).toLowerCase(Locale.ROOT);
            terms.put(language, strings(entry.getValue(), where, "terms." + language));
        }
        return terms;
    }

    private static List<Identifier> identifiers(@Nullable Object raw, String where) {
        List<Identifier> identifiers = new ArrayList<>();
        for (Object item : list(raw, where, "identifiers")) {
            Map<?, ?> map = mapping(item, where, "identifiers");
            rejectUnknownFields(map, Set.of("key", "pattern"), where + " identifiers");
            String pattern = requiredString(map.get("pattern"), where, "identifiers.pattern");
            compile(pattern, where, "identifiers");
            identifiers.add(new Identifier(requiredString(map.get("key"), where, "identifiers.key"), pattern));
        }
        return identifiers;
    }

    private static List<Relation> relations(@Nullable Object raw, String where) {
        List<Relation> relations = new ArrayList<>();
        for (Object item : list(raw, where, "relates_to")) {
            Map<?, ?> map = mapping(item, where, "relates_to");
            rejectUnknownFields(map, Set.of("entity", "label"), where + " relates_to");
            relations.add(new Relation(
                    requiredString(map.get("entity"), where, "relates_to.entity"),
                    requiredString(map.get("label"), where, "relates_to.label")));
        }
        return relations;
    }

    private static List<FacadeToolRef> facadeTools(@Nullable Object raw, String where) {
        List<FacadeToolRef> tools = new ArrayList<>();
        for (Object item : list(raw, where, "facade_tools")) {
            Map<?, ?> map = mapping(item, where, "facade_tools");
            rejectUnknownFields(map, Set.of("tool", "access"), where + " facade_tools");
            String tool = requiredString(map.get("tool"), where, "facade_tools.tool");
            String access = requiredString(map.get("access"), where, "facade_tools.access");
            tools.add(new FacadeToolRef(tool, access(access, where, tool)));
        }
        return tools;
    }

    private static Access access(String raw, String where, String tool) {
        for (Access access : Access.values()) {
            if (access.label().equals(raw)) {
                return access;
            }
        }
        throw new EntityLexiconException(
                where + ": facade tool '" + tool + "' has access '" + raw + "'; it must be reads or writes");
    }

    private static void compile(String pattern, String where, String field) {
        try {
            Pattern.compile(pattern);
        } catch (PatternSyntaxException exception) {
            throw new EntityLexiconException(
                    where + ": '" + field + "' holds an invalid regex '" + pattern + "': " + exception.getDescription(),
                    exception);
        }
    }

    private static void rejectUnknownFields(Map<?, ?> map, Set<String> known, String where) {
        for (Object field : map.keySet()) {
            if (!known.contains(String.valueOf(field))) {
                throw new EntityLexiconException(where + ": unknown field '" + field + "'; expected one of "
                        + known.stream().sorted().toList());
            }
        }
    }

    private static Map<?, ?> mapping(@Nullable Object raw, String where, String field) {
        if (raw instanceof Map<?, ?> map) {
            return map;
        }
        throw new EntityLexiconException(where + ": every '" + field + "' item must be a mapping");
    }

    private static List<?> list(@Nullable Object raw, String where, String field) {
        if (raw == null) {
            return List.of();
        }
        if (raw instanceof List<?> list) {
            return list;
        }
        throw new EntityLexiconException(where + ": '" + field + "' must be a list");
    }

    private static List<String> strings(@Nullable Object raw, String where, String field) {
        List<String> values = new ArrayList<>();
        for (Object item : list(raw, where, field)) {
            // A scalar only: YAML reads an unquoted `none`-like word as a string, but a nested list
            // or mapping here is a shape error, and a blank phrase would make an empty node key.
            if (item == null || item instanceof Map<?, ?> || item instanceof List<?>) {
                throw new EntityLexiconException(where + ": '" + field + "' must hold plain values");
            }
            String value = String.valueOf(item).trim();
            if (value.isEmpty()) {
                throw new EntityLexiconException(where + ": '" + field + "' holds a blank value");
            }
            values.add(value);
        }
        return values;
    }

    private static Map<String, String> stringMap(@Nullable Object raw, String where, String field) {
        if (raw == null) {
            return Map.of();
        }
        if (!(raw instanceof Map<?, ?> map)) {
            throw new EntityLexiconException(where + ": '" + field + "' must be a mapping");
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            values.put(String.valueOf(entry.getKey()), requiredString(entry.getValue(), where, field));
        }
        return values;
    }

    private static String requiredString(@Nullable Object raw, String where, String field) {
        if (raw instanceof String value && !value.isBlank()) {
            return value.trim();
        }
        throw new EntityLexiconException(where + ": '" + field + "' is missing or not a string");
    }
}
