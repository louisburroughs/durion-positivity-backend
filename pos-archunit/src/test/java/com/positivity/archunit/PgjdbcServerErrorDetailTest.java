package com.positivity.archunit;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * ADR-0072 (CAP:550 S24), issue #2650 build-time guard: every module whose {@code application.yml} defines a
 * {@code spring.datasource} must set {@code logServerErrorDetail: false} under
 * {@code hikari.data-source-properties}. pgjdbc's default ({@code true}) appends the server's DETAIL
 * ({@code Failing row contains (...)}, every column of the refused row) to each {@code SQLException} message, which
 * Hibernate logs at ERROR before Spring translates the exception, and which a dead-letter record then carries.
 *
 * <p>pos-order's {@code SupplierVendorCopyIT#aRefusedCopyRowLeaksNoColumnValue} is the Postgres proof of the pattern;
 * this test only keeps the setting from being dropped from, or forgotten in, a module. The check is textual: a
 * {@code spring:} root with a two-space {@code datasource:} key, and a {@code logServerErrorDetail: false} line.
 */
class PgjdbcServerErrorDetailTest {

    private static final Pattern SPRING_ROOT = Pattern.compile("(?m)^spring:\\s*$");
    private static final Pattern DATASOURCE_KEY = Pattern.compile("(?m)^ {2}datasource:\\s*$");
    private static final Pattern FLAG_OFF = Pattern.compile("(?m)^\\s+logServerErrorDetail:\\s*false\\s*$");

    @Test
    void everyDatasourceModuleDisablesPgjdbcServerErrorDetail() throws IOException {
        List<String> offenders = new ArrayList<>();
        int checked = 0;
        try (Stream<Path> modules = Files.list(repoRoot())) {
            for (Path module : modules.filter(PgjdbcServerErrorDetailTest::isPosModule)
                    .sorted()
                    .toList()) {
                Path yaml = module.resolve("src/main/resources/application.yml");
                if (!Files.isRegularFile(yaml)) {
                    continue;
                }
                String text = Files.readString(yaml);
                if (!SPRING_ROOT.matcher(text).find()
                        || !DATASOURCE_KEY.matcher(text).find()) {
                    continue;
                }
                checked++;
                if (!FLAG_OFF.matcher(text).find()) {
                    offenders.add(module.getFileName().toString());
                }
            }
        }
        assertTrue(checked > 0, "no module with a spring.datasource found: the walk is broken");
        assertTrue(
                offenders.isEmpty(),
                "ADR-0072 (#2650): these modules define spring.datasource without "
                        + "hikari.data-source-properties.logServerErrorDetail: false in application.yml: " + offenders);
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
