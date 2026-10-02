package com.positivity.mcp.internal.scopegraph;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.mcp.internal.config.StaticRagPreloadProperties;
import com.positivity.mcp.internal.config.StaticRagPreloadProperties.StaticDocEntry;
import com.positivity.mcp.internal.discovery.OpenApiToolMapper;
import com.positivity.mcp.internal.orchestration.tools.BusinessGlossaryScopeTerms;
import com.positivity.mcp.internal.scopegraph.OpenApiSchemaIndex.DomainIndex;
import com.positivity.mcp.internal.scopegraph.ScopeGraphCatalog.ScreenRow;
import com.positivity.mcp.internal.scopegraph.ScopeGraphCatalog.ToolPermissionRow;
import com.positivity.mcp.internal.scopegraph.ScopeGraphCatalog.ToolPrerequisiteRow;
import com.positivity.mcp.internal.scopegraph.ScopeGraphCatalog.ToolRow;
import com.positivity.mcp.internal.scopegraph.ScopeGraphCatalog.ToolWorkflowRow;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * ADR-0069 section 3, "Build-time validation": the real curated inputs must build a scope graph with
 * no strict finding. This is the test that fails the build on a lexicon or annotation gap.
 *
 * <p>Inputs, all real:
 *
 * <ul>
 *   <li>the shipped {@code scope-graph/entities.yaml};
 *   <li>the effective {@code mcp.rag.preload.docs}, bound the way Spring Boot binds it, under the
 *       default profile and again under {@code alpha} (whose list replaces the base list wholesale);
 *   <li>the schema index built with the runtime's own builder from each module's {@code
 *       openapi.yaml} in the reactor checkout (spec 2.3). Those files are what {@code API Artifacts
 *       Sync} regenerates, so a renamed DTO reaches this test at the next sync;
 *   <li>the catalog as the Flyway seed ({@code V2__seed_mcp_server.sql}) leaves it: the facade tools
 *       with their permission groups and workflow states, the screens and the prerequisites; plus
 *       one discovered row per operation of the module specs, named as discovery names it.
 * </ul>
 *
 * <p>The RAG file-header agreement rule is checked by its own test, not here.
 */
public class ScopeGraphRealConfigValidationTest {

    private static final Path MODULE_DIR = Paths.get(System.getProperty("user.dir"));
    private static final Path SEED = MODULE_DIR.resolve("src/main/resources/db/migration/V2__seed_mcp_server.sql");
    private static final String MODULE_PREFIX = "pos-";
    /** ADR-0069, Current state: the catalog seeds 18 facades. */
    private static final int MIN_FACADES = 18;

    private static final ScopeGraphBuilder BUILDER =
            new ScopeGraphBuilder(Clock.fixed(Instant.parse("2026-09-30T00:00:00Z"), ZoneOffset.UTC));

    private static Specs specs;

    /** The two coverage rules: they fail until every facade and every RAG document is annotated. */
    private static final Set<ScopeGraphFinding.Kind> COVERAGE_KINDS = EnumSet.of(
            ScopeGraphFinding.Kind.FACADE_TOOL_WITHOUT_ENTITY, ScopeGraphFinding.Kind.RAG_DOC_WITHOUT_ENTITIES);

    @ParameterizedTest(name = "profile {0}")
    @ValueSource(strings = {"default", "alpha"})
    @DisplayName("the real lexicon, RAG annotations, specs and seed build a graph with no strict finding")
    void realConfigurationHasNoStrictFinding(String profile) {
        ScopeGraphBuildResult result = build(profile);

        assertThat(result.strictFindings())
                .as(
                        "ADR-0069 section 3 validation under the %s profile. Fix scope-graph/entities.yaml or the"
                                + " entities: lists of mcp.rag.preload.docs. %d strict finding(s) by kind: %s",
                        profile, result.strictFindings().size(), byKind(result.strictFindings()))
                .isEmpty();
    }

    @ParameterizedTest(name = "profile {0}")
    @ValueSource(strings = {"default", "alpha"})
    @DisplayName("everything the lexicon and the RAG annotations do declare resolves")
    void declaredReferencesResolve(String profile) {
        // Holds for a partial lexicon too: whatever is written must name a real entity, domain, RAG
        // scope, schema, tool and screen, and carry en, fr and es terms.
        List<ScopeGraphFinding> unresolved = build(profile).strictFindings().stream()
                .filter(finding -> !COVERAGE_KINDS.contains(finding.kind()))
                .toList();

        assertThat(unresolved)
                .as("unresolved references under the %s profile, by kind: %s", profile, byKind(unresolved))
                .isEmpty();
    }

    @Test
    @DisplayName(
            "ADR-0068: the lexicon's domains block describes every rag-scope of both preload lists and names no other scope")
    void domainSentencesCoverEveryRagScopeAndNothingElse() {
        EntityLexicon lexicon = EntityLexiconLoader.loadDefault();
        Set<String> scopes = new TreeSet<>();
        for (String profile : List.of("default", "alpha")) {
            ragDocs(profile).stream()
                    .map(StaticDocEntry::ragScope)
                    .filter(java.util.Objects::nonNull)
                    .forEach(scopes::add);
        }
        scopes.add("master");

        assertThat(lexicon.domains().keySet())
                .as("every rag-scope used by mcp.rag.preload.docs (and master) has a domains: sentence")
                .containsAll(scopes);
        assertThat(lexicon.domains().keySet())
                .as("domains: names only rag-scopes of the preload lists, or master")
                .isSubsetOf(scopes);
        lexicon.domains()
                .forEach((scope, sentence) -> assertThat(sentence)
                        .as("sentence for %s", scope)
                        .isNotBlank()
                        .endsWith("."));
        // The domain question stays under the option cap with this vocabulary.
        assertThat(scopes).hasSizeLessThanOrEqualTo(26);
    }

    @Test
    @DisplayName("the module specs of the reactor checkout are found and indexed")
    void moduleSpecsAreIndexed() {
        OpenApiSchemaIndex index = specs().index();

        assertThat(index.domains())
                .as("module openapi.yaml files next to %s", MODULE_DIR)
                .contains("workorder", "customer", "inventory", "accounting");
        assertThat(index.hasSchema("workorder:WorkorderResponse")).isTrue();
    }

    @Test
    @DisplayName("the catalog fixture is the seed: every facade with a permission group and a workflow state")
    void catalogFixtureMatchesTheSeed() {
        ScopeGraphCatalog catalog = seedCatalog(List.of());

        List<ToolRow> facades =
                catalog.tools().stream().filter(tool -> !tool.discovered()).toList();
        assertThat(facades).hasSizeGreaterThanOrEqualTo(MIN_FACADES).allMatch(ToolRow::enabled);
        assertThat(facades).extracting(ToolRow::name).contains("WorkorderFacadeTool", "GlossaryFacadeTool");
        assertThat(facades).allSatisfy(facade -> {
            assertThat(catalog.toolPermissions())
                    .as("%s permission rows", facade.name())
                    .anyMatch(row -> row.toolName().equals(facade.name()));
            assertThat(catalog.toolWorkflowStates())
                    .as("%s workflow rows", facade.name())
                    .anyMatch(row -> row.toolName().equals(facade.name()));
        });
        assertThat(catalog.screens()).isNotEmpty();
        assertThat(catalog.toolPrerequisites()).isNotEmpty();
    }

    /** Shared with this package's product-fitment (#2381) and analytics-entity (#2384) tests: the real graph under one profile. */
    static ScopeGraphBuildResult build(String profile) {
        Specs moduleSpecs = specs();
        return BUILDER.build(new ScopeGraphSources(
                EntityLexiconLoader.loadDefault(),
                seedCatalog(moduleSpecs.discoveredTools()),
                ragDocs(profile),
                moduleSpecs.index(),
                new BusinessGlossaryScopeTerms().terms()));
    }

    private static Map<ScopeGraphFinding.Kind, List<String>> byKind(List<ScopeGraphFinding> findings) {
        return findings.stream()
                .collect(Collectors.groupingBy(
                        ScopeGraphFinding::kind,
                        TreeMap::new,
                        Collectors.mapping(ScopeGraphFinding::subject, Collectors.toList())));
    }

    // ---- mcp.rag.preload.docs, as the runtime binds it --------------------------------------------

    /** Shared with the parity and header tests of this package: one binding, the runtime's own. */
    public static List<StaticDocEntry> ragDocs(String profile) {
        StandardEnvironment environment = new StandardEnvironment();
        addYaml(environment, "application.yml");
        if (!"default".equals(profile)) {
            // Added first, so it wins: a profile list replaces the base list wholesale.
            addYaml(environment, "application-" + profile + ".yml");
        }
        return Binder.get(environment)
                .bind("mcp.rag.preload", StaticRagPreloadProperties.class)
                .orElseThrow(() -> new AssertionError("mcp.rag.preload did not bind under profile " + profile))
                .docs();
    }

    private static void addYaml(StandardEnvironment environment, String resource) {
        try {
            for (PropertySource<?> source :
                    new YamlPropertySourceLoader().load("scope-graph-" + resource, new ClassPathResource(resource))) {
                environment.getPropertySources().addFirst(source);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    // ---- module specs (spec 2.3) -----------------------------------------------------------------

    /** The schema index of every module spec, and one discovered tool row per operation. */
    private record Specs(OpenApiSchemaIndex index, List<ToolRow> discoveredTools) {}

    private static synchronized Specs specs() {
        if (specs == null) {
            specs = loadSpecs();
        }
        return specs;
    }

    private static Specs loadSpecs() {
        Path reactor = MODULE_DIR.getParent();
        List<DomainIndex> indexes = new ArrayList<>();
        List<ToolRow> tools = new ArrayList<>();
        try (DirectoryStream<Path> modules = Files.newDirectoryStream(reactor, MODULE_PREFIX + "*")) {
            for (Path module : modules) {
                Path spec = module.resolve("openapi.yaml");
                if (!Files.isRegularFile(spec)) {
                    continue;
                }
                // The gateway routing prefix, which discovery persists as mcp_tool.domain.
                String domain = module.getFileName().toString().substring(MODULE_PREFIX.length());
                String prefix = "/" + domain;
                OpenAPI openApi = parse(spec);
                indexes.add(OpenApiSchemaIndexBuilder.build(
                        openApi,
                        domain,
                        (path, operation) -> OpenApiToolMapper.discoveredToolName(prefix + path, operation)));
                if (openApi.getPaths() != null) {
                    openApi.getPaths()
                            .forEach((path, item) -> methods(item)
                                    .forEach((method, operation) -> tools.add(new ToolRow(
                                            OpenApiToolMapper.discoveredToolName(prefix + path, operation),
                                            domain,
                                            ScopeGraphCatalog.SOURCE_OPENAPI,
                                            method,
                                            true))));
                }
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        // Spec 2.3: a missing checkout must fail loudly, not pass on an empty index.
        assertThat(indexes)
                .as(
                        "no module spec found: expected %s*/openapi.yaml next to %s (run from a full reactor"
                                + " checkout)",
                        reactor.resolve(MODULE_PREFIX), MODULE_DIR)
                .isNotEmpty();
        return new Specs(new OpenApiSchemaIndex(indexes), tools);
    }

    /** Parsed as the runtime capture parses a fetched spec ({@code OpenApiDocumentFetcher}). */
    private static OpenAPI parse(Path spec) {
        try {
            OpenAPI openApi = OpenApiSchemaIndexBuilder.parseUnresolved(Files.readString(spec, StandardCharsets.UTF_8));
            assertThat(openApi).as("%s must parse as OpenAPI", spec).isNotNull();
            return openApi;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static Map<String, Operation> methods(PathItem item) {
        Map<String, Operation> methods = new LinkedHashMap<>();
        putIfPresent(methods, "GET", item.getGet());
        putIfPresent(methods, "POST", item.getPost());
        putIfPresent(methods, "PUT", item.getPut());
        putIfPresent(methods, "DELETE", item.getDelete());
        putIfPresent(methods, "PATCH", item.getPatch());
        return methods;
    }

    private static void putIfPresent(Map<String, Operation> methods, String method, Operation operation) {
        if (operation != null) {
            methods.put(method, operation);
        }
    }

    // ---- the seeded catalog ----------------------------------------------------------------------

    /** The catalog as {@code V2__seed_mcp_server.sql} seeds it, plus the given discovered rows. */
    private static ScopeGraphCatalog seedCatalog(List<ToolRow> discoveredTools) {
        Map<String, String> toolNameById = new LinkedHashMap<>();
        Map<String, String> stateNameById = new LinkedHashMap<>();
        List<ToolRow> tools = new ArrayList<>();
        List<Map<String, String>> permissions = new ArrayList<>();
        List<Map<String, String>> workflows = new ArrayList<>();
        List<ToolPrerequisiteRow> prerequisites = new ArrayList<>();
        List<ScreenRow> screens = new ArrayList<>();
        for (String line : readSeed()) {
            Map<String, String> row = SeedRow.parse(line);
            switch (SeedRow.table(line)) {
                case "mcp_tool" -> {
                    toolNameById.put(row.get("id"), row.get("name"));
                    tools.add(new ToolRow(
                            row.get("name"),
                            row.get("domain"),
                            row.get("source"),
                            row.get("http_method"),
                            Boolean.parseBoolean(row.get("enabled"))));
                }
                case "mcp_workflow_state" -> stateNameById.put(row.get("id"), row.get("name"));
                case "mcp_tool_permission" -> permissions.add(row);
                case "mcp_tool_workflow" -> workflows.add(row);
                case "mcp_tool_prerequisite" ->
                    prerequisites.add(new ToolPrerequisiteRow(
                            row.get("tool_name"), row.get("required_param"), row.get("producing_tool")));
                case "mcp_screen_registry" ->
                    screens.add(new ScreenRow(
                            row.get("screen_key"),
                            row.get("domain"),
                            row.get("required_perm"),
                            row.get("url_template"),
                            row.get("title")));
                default -> {
                    // Tables the scope graph does not read.
                }
            }
        }
        tools.addAll(discoveredTools);
        return new ScopeGraphCatalog(
                tools,
                permissions.stream()
                        .map(row -> new ToolPermissionRow(
                                toolNameById.get(row.get("tool_id")),
                                row.get("permission_code"),
                                row.get("permission_group")))
                        .toList(),
                workflows.stream()
                        .map(row -> new ToolWorkflowRow(
                                toolNameById.get(row.get("tool_id")), stateNameById.get(row.get("workflow_state_id"))))
                        .toList(),
                prerequisites,
                screens);
    }

    private static List<String> readSeed() {
        try {
            return Files.readAllLines(SEED, StandardCharsets.UTF_8).stream()
                    .filter(line -> line.startsWith(SeedRow.INSERT))
                    .toList();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** One single-line {@code INSERT INTO t (a, b) VALUES ('x', NULL);} of the flattened seed. */
    private static final class SeedRow {

        static final String INSERT = "INSERT INTO ";

        private SeedRow() {}

        static String table(String line) {
            return line.substring(INSERT.length(), line.indexOf(' ', INSERT.length()));
        }

        /** Column → value; {@code NULL} becomes null, quotes are removed and {@code ''} unescaped. */
        static Map<String, String> parse(String line) {
            int open = line.indexOf('(');
            int close = line.indexOf(')', open);
            String[] columns = line.substring(open + 1, close).split(",\\s*");
            List<String> values = values(line.substring(line.indexOf("VALUES (", close) + "VALUES (".length()));
            assertThat(values).as("column/value count of: %s", line).hasSameSizeAs(columns);
            Map<String, String> row = new LinkedHashMap<>();
            for (int i = 0; i < columns.length; i++) {
                row.put(columns[i].trim(), values.get(i));
            }
            return row;
        }

        private static List<String> values(String text) {
            List<String> values = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            boolean quoted = false;
            boolean wasQuoted = false;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (quoted) {
                    if (c == '\'' && i + 1 < text.length() && text.charAt(i + 1) == '\'') {
                        current.append('\'');
                        i++;
                    } else if (c == '\'') {
                        quoted = false;
                    } else {
                        current.append(c);
                    }
                } else if (c == '\'') {
                    quoted = true;
                    wasQuoted = true;
                } else if (c == ',' || c == ')') {
                    String value = current.toString().trim();
                    values.add(!wasQuoted && "NULL".equalsIgnoreCase(value) ? null : value);
                    current.setLength(0);
                    wasQuoted = false;
                    if (c == ')') {
                        break;
                    }
                } else {
                    current.append(c);
                }
            }
            return values;
        }
    }
}
