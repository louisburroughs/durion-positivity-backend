package com.positivity.accounting.internal.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.accounting.internal.service.RetreadPlantAddOnSource;
import com.positivity.tenancy.PlatformTenant;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Guards the issue #731 acceptance criterion that <b>every</b> canonical CAP-316 leaf line carries
 * an authoritative GL-account mapping, by parsing the seeds and cross-checking their LABOR_OVERHEAD
 * line codes against {@link LaborOverheadTaxonomy#leafCodes()}. Contract ITs run on H2 without
 * Flyway, so the seed content is verified statically here.
 *
 * <p>Two seeds carry the lines. {@code V2__seed_accounting.sql} gave them to the alpha default
 * tenant and is immutable. {@code R__seed_reference_accounting.sql} is the tenant template in the
 * platform tenant (#2526), which every other tenant is provisioned from: its generic chart and the
 * retread-plant add-on <em>together</em> must cover every leaf, and the add-on section must hold
 * exactly the accounts {@link RetreadPlantAddOnSource} keeps out of the generic chart.
 */
class LaborOverheadMappingSeedTest {

    private static final String MIGRATION = "/db/migration/V2__seed_accounting.sql";
    private static final String TEMPLATE = "/db/migration/R__seed_reference_accounting.sql";

    /** The comment that opens the add-on section of the template seed. */
    private static final String ADD_ON_SECTION = "-- Retread-plant add-on (AW30";

    /**
     * A statement_line_mappings seed row, column order {@code (..., operation, statement_type,
     * parent_line_code, statement_line_code, ...)}: the line code is the second literal after
     * the statement type; the parent between them is NULL on top-level lines.
     */
    private static final Pattern MAPPING_ROW = Pattern.compile("'LABOR_OVERHEAD',\\s*(?:NULL|'[^']*'),\\s*'([^']+)'");

    /** A template statement line row: {@code ('LABOR_OVERHEAD', '<account code>', '<line code>', ...}. */
    private static final Pattern TEMPLATE_ROW = Pattern.compile("\\('LABOR_OVERHEAD',\\s*'(\\d+)',\\s*'([^']+)'");

    private static final Pattern UUID_LITERAL =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    @Test
    void seedProvidesAGlobalMappingForEveryCanonicalLeafLine() throws IOException {
        String sql = read(MIGRATION);
        Set<String> seededCodes = new LinkedHashSet<>();
        Matcher matcher = MAPPING_ROW.matcher(sql);
        while (matcher.find()) {
            seededCodes.add(matcher.group(1));
        }

        assertThat(seededCodes).containsExactlyInAnyOrderElementsOf(LaborOverheadTaxonomy.leafCodes());
    }

    @Test
    void templateMapsEveryCanonicalLeafLineAcrossTheGenericChartAndTheRetreadAddOnTogether() throws IOException {
        String sql = read(TEMPLATE);
        int addOnStart = sql.indexOf(ADD_ON_SECTION);
        assertThat(addOnStart)
                .as("the template seed has a retread add-on section")
                .isPositive();
        Map<String, String> generic = lineCodesByAccount(sql.substring(0, addOnStart));
        Map<String, String> addOn = lineCodesByAccount(sql.substring(addOnStart));

        assertThat(generic.keySet())
                .as("no retread add-on account is in the generic chart")
                .doesNotContainAnyElementsOf(RetreadPlantAddOnSource.ACCOUNT_CODES);
        assertThat(addOn.keySet())
                .as("the add-on section holds exactly the accounts RetreadPlantAddOnSource owns")
                .containsExactlyInAnyOrderElementsOf(RetreadPlantAddOnSource.ACCOUNT_CODES);

        Set<String> lineCodes = new LinkedHashSet<>(generic.values());
        lineCodes.addAll(addOn.values());
        assertThat(lineCodes).containsExactlyInAnyOrderElementsOf(LaborOverheadTaxonomy.leafCodes());
        assertThat(generic.size() + addOn.size())
                .as("one leaf line per account, none mapped twice")
                .isEqualTo(LaborOverheadTaxonomy.leafCodes().size());
    }

    @Test
    void templateBindsThePlatformTenantAndNamesNoOtherId() throws IOException {
        String sql = read(TEMPLATE);
        Set<String> ids = new LinkedHashSet<>();
        Matcher matcher = UUID_LITERAL.matcher(sql);
        while (matcher.find()) {
            ids.add(matcher.group());
        }

        // The binding, and the prefix of every md5 id expression: nothing else. A literal row id or
        // another tenant's id here would be a template row that can collide with a tenant's row, or
        // a seed writing into a tenant's books.
        assertThat(ids).containsExactly(PlatformTenant.ID.toString());
        assertThat(sql).contains("set_config('app.current_tenant', '" + PlatformTenant.ID + "', true)");
        assertThat(sql)
                .as("references are by natural key through the id expression, never a sub-select over every tenant")
                .doesNotContain("SELECT gl_account_id FROM gl_account");
    }

    private static Map<String, String> lineCodesByAccount(String sql) {
        Map<String, String> byAccount = new LinkedHashMap<>();
        Matcher matcher = TEMPLATE_ROW.matcher(sql);
        while (matcher.find()) {
            assertThat(byAccount.put(matcher.group(1), matcher.group(2)))
                    .as("account %s has one LABOR_OVERHEAD line", matcher.group(1))
                    .isNull();
        }
        return byAccount;
    }

    private static String read(String migration) throws IOException {
        try (InputStream stream = LaborOverheadMappingSeedTest.class.getResourceAsStream(migration)) {
            assertThat(stream).as("migration %s on classpath", migration).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
