package com.positivity.accounting.internal.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Guards the issue #731 acceptance criterion that <b>every</b> canonical CAP-316 leaf line carries
 * an authoritative GL-account mapping: parses the seed migration (the flattened successor of the
 * V25 seed) and cross-checks its LABOR_OVERHEAD line codes against
 * {@link LaborOverheadTaxonomy#leafCodes()}. Contract ITs run on H2 without Flyway, so the seed
 * content is verified statically here.
 */
class LaborOverheadMappingSeedTest {

    private static final String MIGRATION = "/db/migration/V2__seed_accounting.sql";

    /**
     * A statement_line_mappings seed row, column order {@code (..., operation, statement_type,
     * parent_line_code, statement_line_code, ...)}: the line code is the second literal after
     * the statement type; the parent between them is NULL on top-level lines.
     */
    private static final Pattern MAPPING_ROW = Pattern.compile("'LABOR_OVERHEAD',\\s*(?:NULL|'[^']*'),\\s*'([^']+)'");

    @Test
    void seedProvidesAGlobalMappingForEveryCanonicalLeafLine() throws IOException {
        String sql = readMigration();

        Set<String> seededCodes = new LinkedHashSet<>();
        Matcher matcher = MAPPING_ROW.matcher(sql);
        while (matcher.find()) {
            seededCodes.add(matcher.group(1));
        }

        assertThat(seededCodes).containsExactlyInAnyOrderElementsOf(LaborOverheadTaxonomy.leafCodes());
    }

    private static String readMigration() throws IOException {
        try (InputStream stream = LaborOverheadMappingSeedTest.class.getResourceAsStream(MIGRATION)) {
            assertThat(stream).as("migration %s on classpath", MIGRATION).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
