package com.positivity.archunit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * ADR-0044 domain-wall check: {@code internal.client} (and client-config)
 * sources must not target
 * other <em>domain</em> modules — synchronous REST is allowed only toward the
 * utility whitelist.
 *
 * <p>
 * Detection is source-based (RestClient base URLs, {@code *.service-id} /
 * {@code *.base-url}
 * config keys, {@code lb://} URIs) because the targets live in string literals
 * and {@code @Value}
 * defaults rather than in the type graph.
 *
 * <p>
 * <strong>Build-failing since Phase 5.6 (#902):</strong> the ADR-0044 migration
 * is complete
 * and any new synchronous domain→domain client fails the build. The utility
 * whitelist below is
 * the single source of truth; changing it — or adding a scoped per-module
 * exception — requires
 * amending ADR-0044.
 */
class DomainWallsTest {

    /**
     * ADR-0044 §1 utility modules (plus this repo's module-name aliases for them).
     */
    private static final Set<String> UTILITY_MODULES = Set.of(
            "pos-api-gateway",
            "pos-security-service",
            "pos-documents",
            "pos-image",
            "pos-tax",
            "pos-event-receiver",
            "pos-price",
            // non-deployed libraries reachable via pos-* tokens in code
            "pos-events",
            "pos-shared-dtos",
            "pos-domain-events",
            "pos-document-helper");

    /**
     * Scoped per-consumer exceptions to ADR-0044 R1: origin module → the exact set
     * of domain
     * modules its {@code internal.client} sources may target synchronously. NOT a
     * widening of the
     * utility whitelist — every entry requires an ADR-0044 amendment, and any other
     * module (or any
     * other target) still fails.
     *
     * <p>
     * pos-warranty: warranty v2 (#924) migrated its
     * candidate-line/eligibility/vehicle-snapshot
     * reads to event-fed {@code ext_*} replicas (ext_vehicle, ext_workorder,
     * ext_invoice,
     * ext_catalog) and pos-customer's dead client was deleted. The sole remaining
     * synchronous target
     * is pos-invoice: settlement adjustment/refund writes plus their reconciliation
     * reads stay
     * synchronous permanently per the <b>ADR-0044 amendment 2026-07-22</b>
     * ("Pos-warranty settlement
     * remains synchronous against pos-invoice") — a money-moving counter-flow that
     * must fail loudly
     * in the request path, with reconciliation reading authoritative post-write
     * state. Widening any
     * edge requires a further ADR-0044 amendment.
     *
     * <p>
     * pos-order: the counter-sale checkout handshake (order parity stories C1–C3,
     * #1071/#1072)
     * creates the fronting invoice at checkout and reverses settled payments in the
     * cancellation
     * saga — the same money-moving counter-flow class, permitted per the
     * <b>ADR-0044 amendment
     * 2026-07-23</b> ("Pos-order checkout/cancellation is synchronous against
     * pos-invoice").
     * Settlement signals stay asynchronous on {@code payment.events.v1}.
     */
    private static final Map<String, Set<String>> SCOPED_MODULE_EXCEPTIONS = Map.of(
            "pos-warranty", Set.of("pos-invoice"),
            "pos-order", Set.of("pos-invoice"));

    /**
     * Class-scoped exceptions to ADR-0044 R1: {@code origin module → client source file name → the
     * exact set of domain modules that ONE file may target synchronously}.
     *
     * <p>Narrower than {@link #SCOPED_MODULE_EXCEPTIONS} on purpose. A module-level grant says "this
     * module may call that one", and every future client added to the module inherits it silently. A
     * file-level grant says "this one class may", so a second client reaching for the same target
     * still fails the build and has to argue its own case.
     *
     * <p>pos-catalog → pos-supplier, from {@code SupplierStockClientImpl} only: live vendor stock is
     * the one cross-domain fact that cannot be replicated. It lives at the vendor, changes without
     * telling us, and is worthless once stale — a replica of it would be a confidently wrong number
     * on a customer's screen. Permitted per the <b>ADR-0044 amendment 2026-08-10</b> ("Live supplier
     * stock inquiry is the single approved synchronous cross-module supplier read"), which names the
     * approved callers as the Product Detail composition and pos-order procurement. Everything else
     * pos-catalog needs from pos-supplier — vendor prices — already arrives as events.
     */
    private static final Map<String, Map<String, Set<String>>> SCOPED_FILE_EXCEPTIONS = Map.of(
            "pos-catalog",
            Map.of("SupplierStockClientImpl.java", Set.of("pos-supplier")),
            // The second approved caller the amendment names: procurement in pos-order, where a
            // buyer is choosing quantities and needs what the vendor says now (CAP-319 #1329).
            // Granted by file name for the same reason as the first — a third caller has to argue
            // its own case rather than inherit either of these.
            "pos-order",
            Map.of("SupplierStockClientImpl.java", Set.of("pos-supplier")),
            // pos-workorder → pos-catalog, from CatalogLaborTimeClientImpl only: vehicle-specific
            // labor-time resolution at quote time (#1569, ADR-0044 amendment 2026-09-02,
            // ADR-0058 §5). The vehicle-keyed time matrix cannot ride events — it is large,
            // licensed, and query-shaped, and QUERY_ONLY guide sources may never be replicated at
            // all; the degraded/offline path is the vehicle-agnostic default hours on the catalog
            // service fact, not a replica of the matrix.
            "pos-workorder",
            Map.of("CatalogLaborTimeClientImpl.java", Set.of("pos-catalog")));

    /**
     * Startup-infra classes exempt per ADR-0044 R2 (registration calls, best-effort
     * at boot).
     */
    private static final Pattern EXEMPT_FILES =
            Pattern.compile(".*(EventTypeInitializer|PermissionRegistration|PermissionInitializer|PermissionRegistry"
                    + "|TemplateInitializer|PermissionVersionStartupCheck)\\.java$");

    private static final Pattern POS_SERVICE_TOKEN = Pattern.compile("pos-[a-z][a-z0-9-]*");
    private static final Pattern SERVICE_ID_DEFAULT = Pattern.compile("\\$\\{[a-z0-9.-]*service-id:([a-z][a-z0-9-]*)}");
    private static final Pattern LOAD_BALANCED_URI = Pattern.compile("lb://([A-Za-z0-9-]+)");

    /**
     * The file-scoped grants stay attached to one file each, and never widen to a module.
     *
     * <p>Each grant names a single class. Converting any to a module-level grant would let every
     * future client in that module reach the target without anybody deciding it should — which is
     * the whole thing the file-scoped form prevents. The census below is exhaustive on purpose:
     * a new grant must be added here, with its ADR amendment, or this test fails.
     */
    @Test
    void fileScopedGrantsAreExhaustiveAndOneFileEach() {
        assertThat(SCOPED_FILE_EXCEPTIONS)
                .as("the file-scoped grant census: supplier stock (2 callers, ADR-0044 amendment"
                        + " 2026-08-10) and catalog labor time (1 caller, ADR-0044 amendment 2026-09-02)")
                .containsOnlyKeys("pos-catalog", "pos-order", "pos-workorder");

        // Supplier stock: pos-catalog and pos-order, one named class, one target each.
        for (String supplierCaller : List.of("pos-catalog", "pos-order")) {
            assertThat(SCOPED_FILE_EXCEPTIONS.get(supplierCaller))
                    .as("%s grants the supplier edge to exactly one file", supplierCaller)
                    .containsOnlyKeys("SupplierStockClientImpl.java");
            assertThat(SCOPED_FILE_EXCEPTIONS.get(supplierCaller).get("SupplierStockClientImpl.java"))
                    .as("%s grants that file exactly one target", supplierCaller)
                    .containsExactly("pos-supplier");
        }

        // Catalog labor time: pos-workorder, one named class, one target (#1569, ADR-0058 §5).
        assertThat(SCOPED_FILE_EXCEPTIONS.get("pos-workorder"))
                .as("pos-workorder grants the catalog labor-time edge to exactly one file")
                .containsOnlyKeys("CatalogLaborTimeClientImpl.java");
        assertThat(SCOPED_FILE_EXCEPTIONS.get("pos-workorder").get("CatalogLaborTimeClientImpl.java"))
                .as("pos-workorder grants that file exactly one target")
                .containsExactly("pos-catalog");

        // A module-level grant would defeat the point: SCOPED_MODULE_EXCEPTIONS must not quietly
        // acquire any file-granted target for these modules.
        assertThat(SCOPED_MODULE_EXCEPTIONS.getOrDefault("pos-catalog", Set.of()))
                .doesNotContain("pos-supplier");
        assertThat(SCOPED_MODULE_EXCEPTIONS.getOrDefault("pos-order", Set.of())).doesNotContain("pos-supplier");
        assertThat(SCOPED_MODULE_EXCEPTIONS.getOrDefault("pos-workorder", Set.of()))
                .doesNotContain("pos-catalog");
    }

    @Test
    void internalClientsShouldOnlyTargetUtilityModules() throws IOException {
        Path repoRoot = repoRoot();
        Map<String, Set<String>> violations = new TreeMap<>();

        try (Stream<Path> modules = Files.list(repoRoot)) {
            for (Path module : modules.filter(DomainWallsTest::isPosModule).toList()) {
                String originModule = module.getFileName().toString();
                Set<String> scopedExceptions = SCOPED_MODULE_EXCEPTIONS.getOrDefault(originModule, Set.of());
                Map<String, Set<String>> fileExceptions = SCOPED_FILE_EXCEPTIONS.getOrDefault(originModule, Map.of());
                for (Path source : clientSources(module)) {
                    if (EXEMPT_FILES.matcher(source.toString()).matches()) {
                        continue;
                    }
                    Set<String> allowedForThisFile =
                            fileExceptions.getOrDefault(source.getFileName().toString(), Set.of());
                    Set<String> targets = referencedServices(source);
                    targets.removeIf(target -> target.equals(originModule)
                            || UTILITY_MODULES.contains(target)
                            || scopedExceptions.contains(target)
                            || allowedForThisFile.contains(target)
                            || !isPosModule(repoRoot.resolve(target)));
                    if (!targets.isEmpty()) {
                        violations
                                .computeIfAbsent(originModule, k -> new TreeSet<>())
                                .add(repoRoot.relativize(source) + " -> " + String.join(", ", targets));
                    }
                }
            }
        }

        StringBuilder report = new StringBuilder("ADR-0044 domain-wall report (sync REST toward domain modules):\n");
        violations.forEach((module, entries) -> {
            report.append("  ")
                    .append(module)
                    .append(" (")
                    .append(entries.size())
                    .append("):\n");
            entries.forEach(entry -> report.append("    ").append(entry).append('\n'));
        });
        int total = violations.values().stream().mapToInt(Set::size).sum();
        report.append("  total violating client sources: ").append(total);
        System.out.println(report);

        Assertions.assertTrue(
                violations.isEmpty(), "ADR-0044: internal.client sources may only target utility modules\n" + report);
    }

    /**
     * internal/client sources plus internal/config client wiring (base URLs often
     * live there).
     */
    private static List<Path> clientSources(Path module) throws IOException {
        Path srcRoot = module.resolve("src/main/java");
        if (!Files.isDirectory(srcRoot)) {
            return List.of();
        }
        List<Path> sources = new ArrayList<>();
        try (Stream<Path> files = Files.walk(srcRoot)) {
            files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> {
                        String path = p.toString().replace('\\', '/');
                        return path.contains("/internal/client/")
                                || (path.contains("/internal/config/")
                                        && p.getFileName().toString().contains("Client"));
                    })
                    .forEach(sources::add);
        }
        sources.sort(Comparator.naturalOrder());
        return sources;
    }

    /**
     * Extract pos-* service references from code lines (comments stripped to reduce
     * noise).
     */
    private static Set<String> referencedServices(Path source) throws IOException {
        Set<String> targets = new LinkedHashSet<>();
        for (String line : Files.readAllLines(source)) {
            String code = line.strip();
            if (code.startsWith("*") || code.startsWith("//") || code.startsWith("/*")) {
                continue;
            }
            Matcher pos = POS_SERVICE_TOKEN.matcher(code);
            while (pos.find()) {
                targets.add(pos.group());
            }
            Matcher serviceId = SERVICE_ID_DEFAULT.matcher(code);
            while (serviceId.find()) {
                targets.add("pos-" + serviceId.group(1));
            }
            Matcher lb = LOAD_BALANCED_URI.matcher(code);
            while (lb.find()) {
                targets.add("pos-" + lb.group(1).toLowerCase(Locale.ROOT));
            }
        }
        return targets;
    }

    private static boolean isPosModule(Path dir) {
        return Files.isDirectory(dir)
                && dir.getFileName().toString().startsWith("pos-")
                && Files.exists(dir.resolve("pom.xml"));
    }

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.isDirectory(dir.resolve("pos-archunit")) && Files.exists(dir.resolve("pom.xml"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("repository root with pos-archunit not found above "
                + Path.of("").toAbsolutePath());
    }
}
