package com.positivity.mcp.internal.scopegraph;

import com.positivity.mcp.internal.config.StaticRagPreloadProperties.StaticDocEntry;
import com.positivity.mcp.internal.discovery.OpenApiToolMapper;
import com.positivity.mcp.internal.scopegraph.ScopeGraphCatalog.ScreenRow;
import com.positivity.mcp.internal.scopegraph.ScopeGraphCatalog.ToolPermissionRow;
import com.positivity.mcp.internal.scopegraph.ScopeGraphCatalog.ToolPrerequisiteRow;
import com.positivity.mcp.internal.scopegraph.ScopeGraphCatalog.ToolRow;
import com.positivity.mcp.internal.scopegraph.ScopeGraphCatalog.ToolWorkflowRow;
import com.positivity.mcp.internal.scopegraph.ScopeGraphGlossarySource.GlossaryTerm;
import io.swagger.v3.oas.models.OpenAPI;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.io.ClassPathResource;

/** Small hand-written sources for the scope-graph unit tests (src/test/resources/scope-graph). */
final class ScopeGraphTestFixtures {

    static final String DOMAIN = "workorder";
    static final String ROUTING_PREFIX = "/workorder";

    private ScopeGraphTestFixtures() {}

    static EntityLexicon lexicon() {
        return EntityLexiconLoader.load(new ClassPathResource("scope-graph/entities-fixture.yaml"));
    }

    /** The fixture spec, parsed the way the runtime capture parses a fetched spec: references left in place. */
    static OpenAPI spec() {
        OpenAPI openApi = OpenApiSchemaIndexBuilder.parseUnresolved(read("scope-graph/service-spec.yaml"));
        if (openApi == null) {
            throw new IllegalStateException("fixture spec did not parse");
        }
        return openApi;
    }

    /** Indexed with discovery's own tool-name derivation, as the fetcher does. */
    static OpenApiSchemaIndex schemaIndex() {
        return new OpenApiSchemaIndex(List.of(OpenApiSchemaIndexBuilder.build(
                spec(),
                DOMAIN,
                (path, operation) -> OpenApiToolMapper.discoveredToolName(ROUTING_PREFIX + path, operation))));
    }

    static ToolRow facade(String name, String domain) {
        return new ToolRow(name, domain, "facade", null, true);
    }

    static ToolRow discovered(String name, String method) {
        return new ToolRow(name, DOMAIN, ScopeGraphCatalog.SOURCE_OPENAPI, method, true);
    }

    /** A catalog in which every reference of the fixture lexicon resolves. */
    static ScopeGraphCatalog catalog() {
        List<ToolRow> tools = new ArrayList<>(List.of(
                facade("WorkorderFacadeTool", DOMAIN),
                facade("DateWindowFacadeTool", "date-window"),
                new ToolRow("RetiredFacadeTool", "retired", "facade", null, false),
                // Disabled, so not a node; its domain is still a catalog domain, which the fixture
                // lexicon's domain_scopes key needs.
                new ToolRow("ShopManagerFacadeTool", "shop-manager", "facade", null, false),
                discovered("workorder_listworkorders", "GET"),
                discovered("workorder_createworkorder", "POST"),
                discovered("workorder_getworkorder", "GET"),
                discovered("workorder_listworkorderparts", "GET"),
                discovered("workorder_getestimate", "GET"),
                discovered("workorder_updateestimate", "PUT")));
        return new ScopeGraphCatalog(
                tools,
                List.of(
                        new ToolPermissionRow("WorkorderFacadeTool", "workorder:workorder:view", "getWorkorder"),
                        new ToolPermissionRow("WorkorderFacadeTool", "workorder:analytics:view", "getLaborAnalytics"),
                        new ToolPermissionRow("WorkorderFacadeTool", "location:read", "getLaborAnalytics"),
                        new ToolPermissionRow("DateWindowFacadeTool", "AUTHENTICATED", "AUTHENTICATED"),
                        new ToolPermissionRow(
                                "workorder_getworkorder", "workorder:workorder:view", "workorder:workorder:view"),
                        new ToolPermissionRow("RetiredFacadeTool", "retired:thing:view", "retired")),
                List.of(
                        new ToolWorkflowRow("WorkorderFacadeTool", "IDLE"),
                        new ToolWorkflowRow("workorder_getworkorder", "IDLE"),
                        new ToolWorkflowRow("RetiredFacadeTool", "IDLE")),
                List.of(new ToolPrerequisiteRow("workorder_getworkorder", "id", "workorder_listworkorders")),
                List.of(
                        new ScreenRow(
                                "workorders.list", DOMAIN, "workorder:workorder:view", "/workorders", "Work Orders"),
                        new ScreenRow("workorders.wip", DOMAIN, null, "/workorders/wip", "Work In Progress")));
    }

    static List<StaticDocEntry> ragDocs() {
        return List.of(
                new StaticDocEntry(
                        "workorder.status-lifecycle",
                        "classpath:rag/workorder.md",
                        "workorder",
                        List.of("workorder:workorder:view"),
                        List.of("workorder", "estimate")),
                new StaticDocEntry(
                        "shopmanager.guide", "classpath:rag/shop.md", "shopmanager", List.of(), List.of("workorder")),
                new StaticDocEntry(
                        "glossary.identifiers",
                        "classpath:rag/glossary.md",
                        "master",
                        List.of("AUTHENTICATED"),
                        List.of("none")));
    }

    static List<GlossaryTerm> glossary() {
        return List.of(
                new GlossaryTerm("backed up", List.of("open work orders running late")),
                new GlossaryTerm("who owes us the most money", List.of()));
    }

    /** Sources that build with no finding at all. */
    static ScopeGraphSources sources() {
        return new ScopeGraphSources(lexicon(), catalog(), ragDocs(), schemaIndex(), glossary());
    }

    /** The fixture lexicon with one piece of its text replaced, for the validation-failure tests. */
    static EntityLexicon lexiconWith(String target, String replacement) {
        String yaml = read("scope-graph/entities-fixture.yaml");
        if (!yaml.contains(target)) {
            throw new IllegalArgumentException("fixture lexicon does not contain: " + target);
        }
        return EntityLexiconLoader.load(new java.io.ByteArrayInputStream(
                yaml.replace(target, replacement).getBytes(StandardCharsets.UTF_8)));
    }

    static String read(String classpathResource) {
        try {
            return new ClassPathResource(classpathResource).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
