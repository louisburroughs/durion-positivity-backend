package com.positivity.archunit;

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
 * ADR-0044 domain-wall check: {@code internal.client} (and client-config) sources must not target
 * other <em>domain</em> modules — synchronous REST is allowed only toward the utility whitelist.
 *
 * <p>Detection is source-based (RestClient base URLs, {@code *.service-id} / {@code *.base-url}
 * config keys, {@code lb://} URIs) because the targets live in string literals and {@code @Value}
 * defaults rather than in the type graph.
 *
 * <p><strong>Build-failing since Phase 5.6 (#902):</strong> the ADR-0044 migration is complete
 * and any new synchronous domain→domain client fails the build. The utility whitelist below is
 * the single source of truth; changing it requires amending ADR-0044.
 */
class DomainWallsTest {

    /** ADR-0044 §1 utility modules (plus this repo's module-name aliases for them). */
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

    /** Startup-infra classes exempt per ADR-0044 R2 (registration calls, best-effort at boot). */
    private static final Pattern EXEMPT_FILES =
            Pattern.compile(".*(EventTypeInitializer|PermissionRegistration|PermissionInitializer|PermissionRegistry"
                    + "|TemplateInitializer|PermissionVersionStartupCheck)\\.java$");

    private static final Pattern POS_SERVICE_TOKEN = Pattern.compile("\"[^\"]*?(pos-[a-z][a-z0-9-]*+)[^\"]*?\"");
    private static final Pattern SERVICE_ID_DEFAULT = Pattern.compile("\\$\\{[a-z0-9.-]*service-id:([a-z][a-z0-9-]*)}");
    private static final Pattern LOAD_BALANCED_URI = Pattern.compile("lb://([A-Za-z0-9-]+)");

    @Test
    void internalClientsShouldOnlyTargetUtilityModules() throws IOException {
        Path repoRoot = repoRoot();
        Map<String, Set<String>> violations = new TreeMap<>();

        try (Stream<Path> modules = Files.list(repoRoot)) {
            for (Path module : modules.filter(DomainWallsTest::isPosModule).toList()) {
                String originModule = module.getFileName().toString();
                for (Path source : clientSources(module)) {
                    if (EXEMPT_FILES.matcher(source.toString()).matches()) {
                        continue;
                    }
                    Set<String> targets = referencedServices(source);
                    targets.removeIf(target -> target.equals(originModule)
                            || UTILITY_MODULES.contains(target)
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

    /** internal/client sources plus internal/config client wiring (base URLs often live there). */
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

    /** Extract pos-* service references from code lines (comments stripped to reduce noise). */
    private static Set<String> referencedServices(Path source) throws IOException {
        Set<String> targets = new LinkedHashSet<>();
        for (String line : Files.readAllLines(source)) {
            String code = line.strip();
            if (code.startsWith("*") || code.startsWith("//") || code.startsWith("/*")) {
                continue;
            }
            Matcher pos = POS_SERVICE_TOKEN.matcher(code);
            while (pos.find()) {
                targets.add(pos.group(1));
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
