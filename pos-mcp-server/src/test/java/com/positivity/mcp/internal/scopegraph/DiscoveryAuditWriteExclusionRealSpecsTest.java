package com.positivity.mcp.internal.scopegraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.positivity.mcp.internal.config.McpServerProperties;
import com.positivity.mcp.internal.discovery.OpenApiToolMapper;
import com.positivity.mcp.internal.discovery.OperationProxyFactory;
import com.positivity.mcp.internal.domain.DiscoveredOperation;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import java.io.IOException;
import java.io.UncheckedIOException;
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
 *       events} in another service (pos-accounting's audit-trail records and event retry, for
 *       instance) keeps its writes.
 * </ul>
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
    private static final Predicate<String> IN_SCOPE = path -> path.startsWith("/security-service/v1/audit/")
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
            "security-service_requestauditexport",
            "security-service_createpricingsnapshot",
            "event-receiver_receiveevent",
            "event-receiver_createeventtype",
            "event-receiver_upserteventtype",
            "event-receiver_updateeventtype",
            "event-receiver_deleteeventtype");

    /** Reads on the same paths; each must stay discoverable. */
    private static final Set<String> KNOWN_KEPT_READS = Set.of(
            "security-service_searchauditevents",
            "security-service_getauditevent",
            "security-service_getauditexportjob",
            "security-service_getpricingsnapshot",
            "event-receiver_queryeventsbyentity",
            "event-receiver_geteventsummarylastday",
            "event-receiver_listeventtypes",
            "event-receiver_geteventtypebyid",
            "mcp-server_searchnltiauditevents");

    /** Business writes whose paths merely contain "audit" or "events"; each must stay discoverable. */
    private static final Set<String> KNOWN_KEPT_LOOKALIKE_WRITES = Set.of(
            "accounting_recordcancellationaudit",
            "accounting_recordpriceoverrideaudit",
            "accounting_recordrefundaudit",
            "accounting_submitaccountingevent",
            "accounting_reprocesssuspendedevent",
            "accounting_retryaccountingevent");

    /** Every operation of every module spec, keyed by discovered tool name: "METHOD prefixed-path". */
    private static Map<String, String> allOperations;

    private static Map<String, String> discovered;

    private static McpServerProperties properties;

    @BeforeAll
    static void discoverWithRealDefaults() {
        properties = bindServerProperties();
        OpenAPI aggregate = prefixedAggregate();
        allOperations = new LinkedHashMap<>();
        aggregate
                .getPaths()
                .forEach((path, item) -> methods(item)
                        .forEach((method, operation) -> allOperations.put(
                                OpenApiToolMapper.discoveredToolName(path, operation), method + " " + path)));

        OpenApiToolMapper mapper = new OpenApiToolMapper(properties, mock(OperationProxyFactory.class));
        discovered = mapper.toDiscoveredOperations("pos-api-gateway", aggregate).stream()
                .collect(Collectors.toMap(
                        DiscoveredOperation::name,
                        operation -> operation.httpMethod() + " " + operation.httpPath(),
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
                        "mcp.server.excluded-write-path-patterns removed operations outside the #2370 scope. All"
                                + " removed by the exclusion: %s",
                        describe(removedByWriteExclusion))
                .isEmpty();
        assertThat(removedByWriteExclusion.values())
                .as("the exclusion only ever removes non-GET operations: %s", describe(removedByWriteExclusion))
                .noneMatch(coordinates -> coordinates.startsWith("GET "));
        assertThat(discovered.keySet())
                .as("business writes on look-alike paths stay discoverable")
                .containsAll(KNOWN_KEPT_LOOKALIKE_WRITES);
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
    private static OpenAPI prefixedAggregate() {
        Path reactor = MODULE_DIR.getParent();
        Paths paths = new Paths();
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
                    openApi.getPaths().forEach((path, item) -> paths.addPathItem(prefix + path, item));
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
        OpenAPI aggregate = new OpenAPI();
        aggregate.setPaths(paths);
        return aggregate;
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
