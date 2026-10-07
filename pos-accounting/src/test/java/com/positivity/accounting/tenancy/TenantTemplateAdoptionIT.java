package com.positivity.accounting.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.accounting.AccountingPostgresContainer;
import com.positivity.accounting.PostgresCommittingTestBase;
import com.positivity.accounting.internal.dto.TenantTemplateStatusResponse;
import com.positivity.accounting.internal.enums.TenantTemplateState;
import com.positivity.accounting.internal.service.AccountingTemplateReader;
import com.positivity.accounting.internal.service.AccountingTemplateStartupSweep;
import com.positivity.accounting.internal.service.AccountingTenantProvisioner;
import com.positivity.accounting.internal.service.TenantTemplateService;
import com.positivity.tenancy.PlatformTenant;
import com.positivity.tenancy.TenantIterator;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The deploy of #2526 onto a database that ran the old seeds (criterion 6): the alpha default
 * tenant already holds everything the template would give it, under the fixed ids the old
 * repeatable seed and {@code V2} wrote. The first sweep must record every one of those rows
 * {@code ADOPTED} and write nothing to any of them.
 *
 * <p>The old database is made here: this IT's own database gets the whole Flyway chain (so the
 * template is in the platform tenant and {@code V2}'s rows are in the default tenant), then the
 * repeatable seed <em>as it was before this story</em> ({@code db/legacy/}, a verbatim copy, which
 * binds the default tenant) is run over it. The startup sweep is switched off for the context so
 * that it meets the old rows, as it would on alpha, instead of an empty tenant.
 *
 * <p>Requires Docker.
 */
@DisplayName("Adopting a database that ran the old seeds (#2526, real Postgres)")
class TenantTemplateAdoptionIT extends PostgresCommittingTestBase {

    private static final String DATABASE = "tenant-template-adoption";

    /**
     * Statement lines in the template that the old seeds never wrote (CAP:550 S35, #2524): the eight
     * balance-sheet lines and the income-statement lines for 5000, 5100 and 6000. The old seed's REVENUE line for
     * 4000 is adopted as it is.
     */
    private static final int S35_STATEMENT_LINES = 11;

    /**
     * Template entries of #2511 (S15) the old seeds never wrote: accounts 1080, 3000, 3900, 6040 (the old seed's
     * 6115 Cash Short stays as it is), 6295, 6375 and 6380; the three posting categories; fifteen mapping keys and
     * their GL mappings; the balance-sheet lines of 1080, 3000 and 3900; the nine petty-expense categories.
     */
    private static final int S15_ACCOUNTS = 7;

    private static final int S15_CHART_ROWS = S15_ACCOUNTS + 3 + 15 + 15 + 3;
    private static final int S15_ENTRIES = S15_CHART_ROWS + 9;

    /**
     * Template entries of #2572 (bank opening balances) the old seeds never wrote: the OPENING_BALANCE posting
     * category, its OPENING_BALANCE_EQUITY key and that key's GL mapping to 3900.
     */
    private static final int S39_CHART_ROWS = 3;

    /**
     * Template entries of #2509 (S12, AW38-AW40) the old seeds never wrote: accounts 2100, 5050 and 5060; the
     * GOODS_RECEIPT and VENDOR_BILL categories; their 3 + 13 mapping keys and GL mappings; the balance-sheet line of
     * 2100 and the income-statement lines of 5050 and 5060.
     */
    private static final int S12_CHART_ROWS = 3 + 2 + 16 + 16 + 3;

    /** {@code 1000 Cash} as the old seed wrote it for the default tenant. */
    private static final UUID LEGACY_CASH_ID = UUID.fromString("5eed0acc-0000-4000-8000-000000001000");

    private static final List<String> CHART_TABLES = List.of(
            "gl_account",
            "posting_category",
            "mapping_key",
            "gl_mapping",
            "default_gl_mapping",
            "statement_line_mappings");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        AccountingPostgresContainer.registerIsolatedDatabase(registry, DATABASE);
        registerCommonProperties(registry);
        registry.add("pos.accounting.tenant-template.startup-sweep.enabled", () -> "false");
    }

    @Autowired
    private TenantIterator tenantIterator;

    @Autowired
    private AccountingTemplateReader templateReader;

    @Autowired
    private AccountingTenantProvisioner provisioner;

    @Autowired
    private TenantTemplateService tenantTemplateService;

    @Autowired
    private ObjectProvider<MeterRegistry> meterRegistry;

    @Autowired
    private ObjectProvider<AccountingTemplateStartupSweep> startupSweep;

    @Test
    @DisplayName(
            "the first sweep adopts every default-tenant row the old seeds wrote and writes nothing to them, creates"
                    + " only the lines the old seeds never had; the second does nothing")
    void firstSweepAdoptsEveryRowTheOldSeedsWrote() throws SQLException {
        assertThat(startupSweep.getIfAvailable())
                .as("the property switches the startup sweep off")
                .isNull();
        JdbcTemplate owner = new JdbcTemplate(ownerDataSource());
        assertThat(count(owner, "gl_account"))
                .as("before the old seed: V2's 42 accounts and nothing else")
                .isEqualTo(42);
        // The old database: the default tenant's rows as the old seed wrote them, and no template yet.
        // (The old seed looks accounts up by code across every tenant, as the owner may, so it only
        // ever ran while the default tenant was the one holding a chart.)
        removePlatformTemplate(owner);
        runSeed("db/legacy/seed_reference_accounting_before_2526.sql");
        List<String> before = chartRows(owner);
        assertThat(count(owner, "gl_account"))
                .as("the old seed's 20 accounts beside V2's 42")
                .isEqualTo(62);
        // The deploy: the repeatable seed's checksum changed, so Flyway runs the new one. It writes the
        // template into the platform tenant and must leave the default tenant's rows alone.
        runSeed("db/migration/R__seed_reference_accounting.sql");
        assertThat(chartRows(owner))
                .as("the new seed wrote no default-tenant row")
                .isEqualTo(before);
        int templateEntries = templateReader.snapshot().entries().size();

        AccountingTemplateStartupSweep sweep =
                new AccountingTemplateStartupSweep(tenantIterator, templateReader, provisioner, meterRegistry);
        sweep.run(new DefaultApplicationArguments());

        // Every row the old seeds wrote is still exactly as it was (the adopted 4000 line keeps its REVENUE
        // code: a presentation the tenant held before adoption is the tenant's). The only additions are
        // the statement lines S35 (#2524) put in the template that the old seeds never had: eight
        // balance-sheet lines and the income-statement lines for 5000, 5100 and 6000.
        List<String> after = chartRows(owner);
        assertThat(after).as("no default-tenant row was removed or modified").containsAll(before);
        List<String> added = new ArrayList<>(after);
        added.removeAll(before);
        assertThat(added)
                .as("only S35's statement lines, S15's chart (#2511), the opening-balance mapping (#2572) and S12's"
                        + " chart (#2509) were added")
                .hasSize(S35_STATEMENT_LINES + S15_CHART_ROWS + S39_CHART_ROWS + S12_CHART_ROWS);
        assertThat(added.stream()
                        .filter(row -> row.contains("\"BALANCE_SHEET\""))
                        .count())
                .isEqualTo(8 + 3 + 1);
        assertThat(owner.queryForObject(
                        "SELECT l.statement_line_code FROM statement_line_mappings l JOIN gl_account a ON"
                                + " a.gl_account_id = l.gl_account_id WHERE l.tenant_id = ? AND a.tenant_id = ? AND"
                                + " a.account_code = '4000' AND l.statement_type = 'INCOME_STATEMENT'",
                        String.class,
                        TENANT,
                        TENANT))
                .as("the adopted 4000 line keeps the REVENUE code the old seed gave it: adoption never rewrites a"
                        + " line the tenant held before")
                .isEqualTo("REVENUE");
        assertThat(owner.queryForObject(
                        "SELECT account_code FROM gl_account WHERE tenant_id = ? AND gl_account_id = ?",
                        String.class,
                        TENANT,
                        LEGACY_CASH_ID))
                .as("1000 Cash keeps the id the old seed gave it")
                .isEqualTo("1000");
        assertThat(owner.queryForList(
                        "SELECT outcome || ' ' || count(*) FROM accounting_template_entry WHERE tenant_id = ? GROUP BY"
                                + " outcome",
                        String.class,
                        TENANT))
                .as("every template entry the old seeds wrote, the retread add-on included, is ADOPTED; the rest"
                        + " CREATED")
                .containsExactlyInAnyOrder(
                        "ADOPTED " + (templateEntries - S35_STATEMENT_LINES - S15_ENTRIES - S39_CHART_ROWS - S12_CHART_ROWS),
                        "CREATED " + (S35_STATEMENT_LINES + S15_ENTRIES + S39_CHART_ROWS + S12_CHART_ROWS));
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM accounting_template_entry WHERE tenant_id = ? AND target_row_id IS NULL",
                        Integer.class,
                        TENANT))
                .isZero();
        TenantTemplateStatusResponse status = tenantTemplateService.status();
        assertThat(status.state()).isEqualTo(TenantTemplateState.UP_TO_DATE);
        assertThat(status.counts().adopted())
                .isEqualTo(templateEntries - S35_STATEMENT_LINES - S15_ENTRIES - S39_CHART_ROWS - S12_CHART_ROWS);
        assertThat(status.counts().created()).isEqualTo(S35_STATEMENT_LINES + S15_ENTRIES + S39_CHART_ROWS + S12_CHART_ROWS);
        assertThat(status.retreadPlantAddOn()).isTrue();
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM gl_account WHERE tenant_id = ?", Integer.class, PlatformTenant.ID))
                .as("the template stays in the platform tenant")
                // 62 + 1080, 3000, 3900, 6295, 6375, 6380 (6040 took 6115's place) + 2100, 5050, 5060 (#2509)
                .isEqualTo(71);

        List<String> recordsAfterFirst = templateRows(owner);
        sweep.run(new DefaultApplicationArguments());

        assertThat(chartRows(owner)).isEqualTo(after);
        assertThat(templateRows(owner)).as("a second sweep writes nothing").isEqualTo(recordsAfterFirst);
    }

    private static DataSource ownerDataSource() {
        return AccountingPostgresContainer.ownerDataSource(DATABASE);
    }

    private static int count(JdbcTemplate owner, String table) {
        return owner.queryForObject("SELECT count(*) FROM " + table + " WHERE tenant_id = ?", Integer.class, TENANT);
    }

    private static List<String> chartRows(JdbcTemplate owner) {
        return rows(owner, CHART_TABLES);
    }

    private static List<String> templateRows(JdbcTemplate owner) {
        return rows(owner, List.of("accounting_template_state", "accounting_template_entry", "accounting_audit_log"));
    }

    private static List<String> rows(JdbcTemplate owner, List<String> tables) {
        List<String> rows = new ArrayList<>();
        for (String table : tables) {
            owner.queryForList(
                            "SELECT row_to_json(t)::text FROM " + table + " t WHERE tenant_id = ? ORDER BY 1",
                            String.class,
                            TENANT)
                    .forEach(row -> rows.add(table + " " + row));
        }
        return rows;
    }

    private static void removePlatformTemplate(JdbcTemplate owner) {
        for (String table : List.of(
                "petty_expense_category_change",
                "petty_expense_category",
                "statement_line_mappings",
                "default_gl_mapping",
                "gl_mapping",
                "mapping_key",
                "posting_category",
                "gl_account")) {
            owner.update("DELETE FROM " + table + " WHERE tenant_id = ?", PlatformTenant.ID);
        }
    }

    /** Runs a seed the way Flyway does: as the owner, in one transaction (its tenant binding is transaction-local). */
    private static void runSeed(String resource) throws SQLException {
        try (Connection connection = ownerDataSource().getConnection()) {
            connection.setAutoCommit(false);
            ScriptUtils.executeSqlScript(connection, new ClassPathResource(resource));
            connection.commit();
        }
    }
}
