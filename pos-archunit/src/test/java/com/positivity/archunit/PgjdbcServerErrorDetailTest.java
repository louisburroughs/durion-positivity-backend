package com.positivity.archunit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * ADR-0072 (CAP:550 S24), issue #2650 build-time guard: every YAML document that configures a Postgres-capable
 * {@code spring.datasource} must set {@code hikari.data-source-properties.logServerErrorDetail: false} in that same
 * document. pgjdbc's default ({@code true}) appends the server's DETAIL ({@code Failing row contains (...)}, every
 * column of the refused row) to each {@code SQLException} message, which Hibernate logs at ERROR before Spring
 * translates the exception, and which a dead-letter record then carries.
 *
 * <p>Main and test resources are both checked because a test {@code application.yml} or {@code application-pg.yml}
 * shadows or redefines the main datasource, and every {@code ---} document is checked because a profile document
 * replaces the datasource block it names. {@code SupplierVendorCopyIT#aRefusedCopyRowLeaksNoColumnValue} (pos-order)
 * is the Postgres proof of the pattern; this test keeps the setting from being dropped or forgotten.
 */
class PgjdbcServerErrorDetailTest {

    @Test
    void everyPostgresDatasourceDocumentDisablesPgjdbcServerErrorDetail() throws IOException {
        List<String> offenders = new ArrayList<>();
        int checked = 0;
        try (Stream<Path> modules = Files.list(RepoLayout.repoRoot())) {
            for (Path module : modules.filter(RepoLayout::isPosModule).sorted().toList()) {
                for (String dir : List.of("src/main/resources", "src/test/resources")) {
                    for (Path file : applicationYmls(module.resolve(dir))) {
                        List<Object> docs = load(file);
                        for (int i = 0; i < docs.size(); i++) {
                            Map<?, ?> datasource = datasource(docs.get(i));
                            if (datasource == null || !postgresCapable(datasource)) {
                                continue;
                            }
                            checked++;
                            if (!flagOff(datasource)) {
                                offenders.add(module.getFileName() + "/" + dir + "/" + file.getFileName() + "#" + i);
                            }
                        }
                    }
                }
            }
        }
        assertTrue(checked > 0, "no Postgres spring.datasource document found: the walk is broken");
        assertTrue(
                offenders.isEmpty(),
                "ADR-0072 (#2650): these documents define a Postgres spring.datasource without "
                        + "hikari.data-source-properties.logServerErrorDetail: false: " + offenders);
    }

    private static List<Path> applicationYmls(Path resources) throws IOException {
        if (!Files.isDirectory(resources)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(resources)) {
            return files.filter(p -> {
                        String n = p.getFileName().toString();
                        return n.startsWith("application") && n.endsWith(".yml");
                    })
                    .sorted()
                    .toList();
        }
    }

    private static List<Object> load(Path file) throws IOException {
        List<Object> docs = new ArrayList<>();
        try (Reader reader = Files.newBufferedReader(file)) {
            new Yaml().loadAll(reader).forEach(docs::add);
        }
        return docs;
    }

    private static Map<?, ?> datasource(Object doc) {
        if (doc instanceof Map<?, ?> root
                && root.get("spring") instanceof Map<?, ?> spring
                && spring.get("datasource") instanceof Map<?, ?> ds) {
            return ds;
        }
        return null;
    }

    /** Postgres named, or neither url nor driver named but a hikari block present (default driver is pgjdbc). */
    private static boolean postgresCapable(Map<?, ?> datasource) {
        String url = text(datasource.get("url"));
        String driver = text(datasource.get("driver-class-name"));
        String named = url + " " + driver;
        if (named.contains("jdbc:h2:") || named.contains("org.h2.Driver")) {
            return false;
        }
        if (named.toLowerCase().contains("postgres")) {
            return true;
        }
        return url.isBlank() && driver.isBlank() && datasource.containsKey("hikari")
                || url.startsWith("${") && !named.contains("h2");
    }

    private static boolean flagOff(Map<?, ?> datasource) {
        if (datasource.get("hikari") instanceof Map<?, ?> hikari
                && hikari.get("data-source-properties") instanceof Map<?, ?> props) {
            Object flag = props.get("logServerErrorDetail");
            return Boolean.FALSE.equals(flag) || "false".equals(String.valueOf(flag));
        }
        return false;
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }
}
