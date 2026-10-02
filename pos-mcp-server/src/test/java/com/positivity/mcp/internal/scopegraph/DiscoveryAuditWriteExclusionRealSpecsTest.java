package com.positivity.mcp.internal.scopegraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.mock;

import com.positivity.mcp.internal.config.McpServerProperties;
import com.positivity.mcp.internal.discovery.OpenApiToolMapper;
import com.positivity.mcp.internal.discovery.OperationProxyFactory;
import com.positivity.mcp.internal.domain.DiscoveredOperation;
import io.modelcontextprotocol.spec.McpSchema;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import reactor.core.publisher.Mono;

/**
 * #2370: audit events are evidence, and platform event emission/registration is service-to-service.
 * A tool call may cause an audit event as a side effect; the assistant never writes one itself. This
 * test runs the real discovery mapper, with the real {@code mcp.server} defaults of {@code
 * application.yml}, over every module's {@code openapi.yaml} in the reactor checkout (prefixed with
 * its routing prefix the way {@code OpenApiDocumentFetcher} prefixes a fetched spec) and checks:
 *
 * <ul>
 *   <li>no non-GET operation under the audit and platform-event paths survives discovery;
 *   <li>the GET operations on those same paths do survive (reading the audit log is a legitimate
 *       admin question, ADR-0068);
 *   <li>nothing outside those paths was removed: a path that merely contains {@code audit} or {@code
 *       events} in another service (pos-accounting's event retry and reprocess, for instance) keeps
 *       its writes.
 * </ul>
 *
 * <p>#2374 moved two operations across that line. pos-accounting's {@code submitAccountingEvent} is
 * in scope: a call would make the assistant the upstream producer of a source-system fact, so it is
 * excluded while the event list GET on the same path stays. pos-security-service's {@code
 * requestAuditExport} is out of scope: it is a bulk read of audit data, not an emit, so it is a tool
 * again.
 *
 * <p>The same checks run over the per-service Eureka fallback ({@code toToolSpecifications}, used by
 * the full fallback when the aggregate yields nothing and by the targeted fallback for a partial
 * aggregate's failed prefixes), whose specs carry unprefixed paths.
 *
 * <p>Same approach as {@link ScopeGraphRealConfigValidationTest}: the module specs are what {@code
 * API Artifacts Sync} regenerates, so a new audit write reaches this test at the next sync.
 */
class DiscoveryAuditWriteExclusionRealSpecsTest {

    private static final Path MODULE_DIR = Path.of(System.getProperty("user.dir"));
    private static final String MODULE_PREFIX = "pos-";

    /**
     * The in-scope surfaces of #2370, stated independently of the configured patterns so the test
     * checks the configuration rather than restating it. Paths are routing-prefixed.
     */
    private static final Predicate<String> IN_SCOPE = path -> (path.startsWith("/security-service/v1/audit/")
                    && !path.equals("/security-service/v1/audit/exports")
                    && !path.startsWith("/security-service/v1/audit/exports/"))
            || path.startsWith("/accounting/v1/accounting/audit/")
            || path.equals("/accounting/v1/accounting/events")
            || path.equals("/event-receiver/v1/events")
            || path.startsWith("/event-receiver/v1/events/")
            || path.equals("/event-receiver/v1/eventTypes")
            || path.startsWith("/event-receiver/v1/eventTypes/")
            || path.startsWith("/mcp-server/v1/mcp/audit")
            || path.startsWith("/mcp-server/v1/nlt/audit");

    /** Write operations the specs carry today under the in-scope paths; each must be gone. */
    private static final Set<String> KNOWN_EXCLUDED_WRITES = Set.of(
            "security-service_createauditevent",
            "security-service_rejectauditeventupdate",
            "security-service_rejectauditeventdelete",
            "security-service_createpricingsnapshot",
            "accounting_recordcancellationaudit",
            "accounting_recordpriceoverrideaudit",
            "accounting_recordrefundaudit",
            "accounting_submitaccountingevent",
            "event-receiver_receiveevent",
            "event-receiver_createeventtype",
            "event-receiver_upserteventtype",
            "event-receiver_updateeventtype",
            "event-receiver_deleteeventtype");

    /** Reads on the same paths; each must stay discoverable. */
    private static final Set<String> KNOWN_KEPT_READS = Set.of(
            "security-service_searchauditevents",
            "security-service_getauditevent",
            "security-service_getpricingsnapshot",
            "accounting_getaudittrailbyactor",
            "accounting_getaudittrailbyorder_1",
            "accounting_getaudittrailbyinvoice",
            "accounting_getaudittrailbyorder",
            "accounting_getaudittrailbydaterange",
            "accounting_getaudittrailbytype",
            "accounting_listaccountingevents",
            "event-receiver_queryeventsbyentity",
            "event-receiver_geteventsummarylastday",
            "event-receiver_listeventtypes",
            "event-receiver_geteventtypebyid",
            "mcp-server_searchnltiauditevents");

    /**
     * Business writes whose paths merely contain "audit" or "events"; each must stay discoverable.
     * (pos-accounting's audit-trail writes under {@code /v1/accounting/audit/} and its event submit
     * are in scope and excluded above.) Retry and reprocess stay behind {@code
     * AccountingEventWriteGuard}; the audit export is a read of the log (#2374).
     */
    private static final Set<String> KNOWN_KEPT_LOOKALIKE_WRITES = Set.of(
            "accounting_reprocesssuspendedevent",
            "accounting_retryaccountingevent",
            "security-service_requestauditexport");

    /** Every operation of every module spec, keyed by discovered tool name: "METHOD prefixed-path". */
    private static Map<String, String> allOperations;

    private static Map<String, String> discovered;

    /** The permissions each discovered operation is granted to, keyed by discovered tool name. */
    private static Map<String, List<String>> discoveredPermissions;

    private static McpServerProperties properties;

    /** Every module spec as its service serves it (unprefixed paths), keyed by its routing prefix. */
    private static Map<String, OpenAPI> moduleSpecs;

    @BeforeAll
    static void discoverWithRealDefaults() {
        properties = bindServerProperties();
        moduleSpecs = moduleSpecs();
        OpenAPI aggregate = prefixedAggregate(moduleSpecs);
        allOperations = new LinkedHashMap<>();
        aggregate
                .getPaths()
                .forEach((path, item) -> methods(item)
                        .forEach((method, operation) -> allOperations.put(
                                OpenApiToolMapper.discoveredToolName(path, operation), method + " " + path)));

        OpenApiToolMapper mapper = new OpenApiToolMapper(properties, mock(OperationProxyFactory.class));
        List<DiscoveredOperation> operations = mapper.toDiscoveredOperations("pos-api-gateway", aggregate);
        discovered = operations.stream()
                .collect(Collectors.toMap(
                        DiscoveredOperation::name,
                        operation -> operation.httpMethod() + " " + operation.httpPath(),
                        (first, second) -> first,
                        LinkedHashMap::new));
        discoveredPermissions = operations.stream()
                .collect(Collectors.toMap(
                        DiscoveredOperation::name,
                        DiscoveredOperation::requiredPermissions,
                        (first, second) -> first,
                        LinkedHashMap::new));
    }

    @Test
    @DisplayName("application.yml configures the #2370 write exclusion")
    void defaultsCarryTheWriteExclusion() {
        assertThat(properties.excludedWritePathPatterns())
                .as("mcp.server.excluded-write-path-patterns in application.yml")
                .isNotEmpty();
        assertThat(allOperations)
                .as("the reactor checkout next to %s must carry the module specs", MODULE_DIR)
                .isNotEmpty();
    }

    @Test
    @DisplayName("no non-GET operation under the audit or platform-event paths survives discovery")
    void noWriteSurvivesOnAuditOrEventPaths() {
        Set<String> survivingWrites = discovered.entrySet().stream()
                .filter(entry -> !entry.getValue().startsWith("GET "))
                .filter(entry -> IN_SCOPE.test(pathOf(entry.getValue())))
                .map(entry -> entry.getKey() + " (" + entry.getValue() + ")")
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(survivingWrites)
                .as("write operations on audit/platform-event paths still discoverable as agent tools (#2370)")
                .isEmpty();
        assertThat(discovered.keySet())
                .as("the audit/platform-event writes the specs carry today must all be gone")
                .doesNotContainAnyElementsOf(KNOWN_EXCLUDED_WRITES);
        assertThat(allOperations.keySet())
                .as("the specs still declare the writes this test expects to see excluded (rename here if they moved)")
                .containsAll(KNOWN_EXCLUDED_WRITES);
    }

    @Test
    @DisplayName("GET operations on the same paths stay discoverable")
    void readsOnAuditAndEventPathsSurvive() {
        Set<String> inScopeReads = allOperations.entrySet().stream()
                .filter(entry -> entry.getValue().startsWith("GET "))
                .filter(entry -> IN_SCOPE.test(pathOf(entry.getValue())))
                .map(Map.Entry::getKey)
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(inScopeReads).isNotEmpty().containsAll(KNOWN_KEPT_READS);
        assertThat(discovered.keySet())
                .as("every GET on an audit/platform-event path is still a tool")
                .containsAll(inScopeReads);
    }

    @Test
    @DisplayName("the exclusion removed nothing outside the audit and platform-event paths")
    void nothingOutsideTheScopedPathsWasRemoved() {
        Map<String, String> removed = new LinkedHashMap<>(allOperations);
        removed.keySet().removeAll(discovered.keySet());
        // Operations gone for the pre-existing reasons (excluded-path-fragments, allowlist) are not
        // this exclusion's doing; only writes on paths the write patterns match are attributed to it.
        Map<String, String> removedByWriteExclusion = removed.entrySet().stream()
                .filter(entry -> !properties.excludesPath(pathOf(entry.getValue())))
                .filter(entry -> properties.includesPath(pathOf(entry.getValue())))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));

        List<String> outOfScope = removedByWriteExclusion.entrySet().stream()
                .filter(entry -> !IN_SCOPE.test(pathOf(entry.getValue())))
                .map(entry -> entry.getKey() + " (" + entry.getValue() + ")")
                .sorted()
                .toList();

        assertThat(outOfScope)
                .as(
                        "mcp.server.excluded-write-path-patterns removed operations outside the #2370/#2374 scope. All"
                                + " removed by the exclusion: %s",
                        describe(removedByWriteExclusion))
                .isEmpty();
        assertThat(removedByWriteExclusion.values())
                .as("the exclusion only ever removes non-GET operations: %s", describe(removedByWriteExclusion))
                .noneMatch(coordinates -> coordinates.startsWith("GET "));
        assertThat(discovered.keySet())
                .as("business writes on look-alike paths stay discoverable")
                .containsAll(KNOWN_KEPT_LOOKALIKE_WRITES);
        assertThat(discovered.keySet())
                .as("pos-accounting's reconciliation audit read is outside the audit-trail surface and untouched")
                .contains("accounting_getreconciliationaudit");
    }

    @Test
    @DisplayName(
            "#2374: the writes kept on the event and export paths are offered only to holders of their own permission")
    void keptWritesStayBehindTheirOwnPermission() {
        // The tool gate is the operation's x-required-permissions: a caller without the grant never
        // sees the tool, so retry and reprocess reach only accounting:events:retry / :reprocess holders.
        assertThat(discoveredPermissions.get("accounting_retryaccountingevent"))
                .as("retryAccountingEvent permissions")
                .containsExactly("accounting:events:retry");
        assertThat(discoveredPermissions.get("accounting_reprocesssuspendedevent"))
                .as("reprocessSuspendedEvent permissions")
                .containsExactly("accounting:events:reprocess");
        assertThat(discoveredPermissions.get("security-service_requestauditexport"))
                .as("requestAuditExport permissions")
                .containsExactly("security:audit:export");
        assertThat(discovered)
                .as("the guard's status read is a discoverable tool on the same surface")
                .containsEntry("accounting_getaccountingevent", "GET /accounting/v1/accounting/events/{eventId}");
        assertThat(discovered)
                .as("the export's job poll stays a tool beside the export request")
                .containsEntry("security-service_getauditexportjob", "GET /security-service/v1/audit/exports/{jobId}");
    }

    @Test
    @DisplayName("the per-service Eureka fallback drops exactly the audit and platform-event writes, and nothing else")
    void perServiceFallbackDropsExactlyTheScopedWrites() {
        // The handler methods are package-private to the discovery package: answered by return type.
        BiFunction<?, ?, ?> inert = (exchange, request) -> Mono.empty();
        OperationProxyFactory proxyFactory = mock(
                OperationProxyFactory.class,
                invocation -> BiFunction.class.equals(invocation.getMethod().getReturnType())
                        ? inert
                        : RETURNS_DEFAULTS.answer(invocation));
        OpenApiToolMapper mapper = new OpenApiToolMapper(properties, proxyFactory);
        Set<String> declared = new TreeSet<>();
        Set<String> registered = new TreeSet<>();
        moduleSpecs.forEach((prefix, spec) -> {
            spec.getPaths()
                    .forEach((path, item) ->
                            methods(item).keySet().forEach(method -> declared.add(method + " " + prefix + path)));
            // The Eureka id as included-services names it ("pos-security-service"); DiscoveryClient's
            // own spelling ("security-service") resolves to the same routing prefix.
            String serviceId = MODULE_PREFIX + prefix.substring(1);
            mapper.toToolSpecifications(serviceId, URI.create("http://" + serviceId + ".test"), spec)
                    .forEach(specification -> registered.add(coordinates(prefix, specification.tool())));
        });

        Set<String> removed = new TreeSet<>(declared);
        removed.removeAll(registered);
        Set<String> scopedWrites = declared.stream()
                .filter(coordinates -> !coordinates.startsWith("GET "))
                .filter(coordinates -> IN_SCOPE.test(pathOf(coordinates)))
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(scopedWrites)
                .as("the specs still declare audit/platform-event writes")
                .isNotEmpty();
        assertThat(removed)
                .as("operations the per-service fallback did not register (#2370: exactly the scoped writes)")
                .isEqualTo(scopedWrites);
    }

    // ---- inputs ----------------------------------------------------------------------------------

    /** {@code mcp.server} bound the way Spring Boot binds it, from the shipped application.yml. */
    private static McpServerProperties bindServerProperties() {
        StandardEnvironment environment = new StandardEnvironment();
        try {
            for (PropertySource<?> source : new YamlPropertySourceLoader()
                    .load("audit-write-exclusion-application.yml", new ClassPathResource("application.yml"))) {
                environment.getPropertySources().addFirst(source);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return Binder.get(environment)
                .bind("mcp.server", McpServerProperties.class)
                .orElseThrow(() -> new AssertionError("mcp.server did not bind from application.yml"));
    }

    /**
     * Every module spec merged under its routing prefix ({@code pos-security-service} serves at {@code
     * /security-service/**}), as {@code OpenApiDocumentFetcher.prefixPaths} merges fetched specs.
     */
    private static OpenAPI prefixedAggregate(Map<String, OpenAPI> specs) {
        Paths paths = new Paths();
        specs.forEach((prefix, openApi) -> {
            if (openApi.getPaths() != null) {
                openApi.getPaths().forEach((path, item) -> paths.addPathItem(prefix + path, item));
            }
        });
        OpenAPI aggregate = new OpenAPI();
        aggregate.setPaths(paths);
        return aggregate;
    }

    /** Every module's {@code openapi.yaml} in the reactor checkout, keyed by its routing prefix. */
    private static Map<String, OpenAPI> moduleSpecs() {
        Path reactor = MODULE_DIR.getParent();
        Map<String, OpenAPI> specs = new LinkedHashMap<>();
        List<String> modules = new ArrayList<>();
        try (DirectoryStream<Path> moduleDirs = Files.newDirectoryStream(reactor, MODULE_PREFIX + "*")) {
            for (Path module : moduleDirs) {
                Path spec = module.resolve("openapi.yaml");
                if (!Files.isRegularFile(spec)) {
                    continue;
                }
                String prefix = "/" + module.getFileName().toString().substring(MODULE_PREFIX.length());
                OpenAPI openApi =
                        OpenApiSchemaIndexBuilder.parseUnresolved(Files.readString(spec, StandardCharsets.UTF_8));
                assertThat(openApi).as("%s must parse as OpenAPI", spec).isNotNull();
                modules.add(prefix);
                if (openApi.getPaths() != null) {
                    specs.put(prefix, openApi);
                }
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        assertThat(modules)
                .as(
                        "no module spec found: expected %s*/openapi.yaml next to %s (run from a full reactor"
                                + " checkout)",
                        reactor.resolve(MODULE_PREFIX), MODULE_DIR)
                .isNotEmpty();
        return specs;
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

    /** "METHOD prefixed-path" of a per-service tool, read from its input schema's constants. */
    private static String coordinates(String prefix, McpSchema.Tool tool) {
        Map<String, Object> schema = tool.inputSchema().properties();
        return constOf(schema.get("httpMethod")) + " " + prefix + constOf(schema.get("path"));
    }

    private static Object constOf(Object property) {
        return ((Map<?, ?>) property).get("const");
    }

    private static String pathOf(String coordinates) {
        return coordinates.substring(coordinates.indexOf(' ') + 1);
    }

    private static String describe(Map<String, String> operations) {
        return operations.entrySet().stream()
                .map(entry -> entry.getKey() + " (" + entry.getValue() + ")")
                .sorted()
                .collect(Collectors.joining(", ", "[", "]"));
    }
}
