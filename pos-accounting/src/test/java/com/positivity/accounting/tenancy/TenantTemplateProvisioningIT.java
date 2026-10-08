package com.positivity.accounting.tenancy;

import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_A;
import static com.positivity.tenancy.testing.TenantTestSupport.asTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.positivity.accounting.internal.dto.EnableTemplateAddOnRequest;
import com.positivity.accounting.internal.dto.IncomeStatementReport;
import com.positivity.accounting.internal.dto.TenantTemplateStatusResponse;
import com.positivity.accounting.internal.entity.AccountingConfiguration;
import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.accounting.internal.entity.GLMapping;
import com.positivity.accounting.internal.entity.MappingKey;
import com.positivity.accounting.internal.entity.StatementLineMapping;
import com.positivity.accounting.internal.enums.AccountType;
import com.positivity.accounting.internal.enums.OperationType;
import com.positivity.accounting.internal.enums.StatementType;
import com.positivity.accounting.internal.enums.TemplateEntryReason;
import com.positivity.accounting.internal.enums.TenantTemplateState;
import com.positivity.accounting.internal.exception.GLMappingNotConfiguredException;
import com.positivity.accounting.internal.repository.AccountingConfigurationRepository;
import com.positivity.accounting.internal.repository.GLAccountRepository;
import com.positivity.accounting.internal.repository.GLMappingRepository;
import com.positivity.accounting.internal.repository.MappingKeyRepository;
import com.positivity.accounting.internal.repository.PostingCategoryRepository;
import com.positivity.accounting.internal.repository.ProcessedEventRepository;
import com.positivity.accounting.internal.repository.StatementLineMappingRepository;
import com.positivity.accounting.internal.service.AccountingTemplate;
import com.positivity.accounting.internal.service.AccountingTemplateApplier;
import com.positivity.accounting.internal.service.AccountingTemplateReader;
import com.positivity.accounting.internal.service.AccountingTemplateStartupSweep;
import com.positivity.accounting.internal.service.AccountingTemplateStateLock;
import com.positivity.accounting.internal.service.AccountingTenantProvisioner;
import com.positivity.accounting.internal.service.FinancialReportingService;
import com.positivity.accounting.internal.service.GLMappingResolver;
import com.positivity.accounting.internal.service.InvoiceRevenuePostingService;
import com.positivity.accounting.internal.service.RetreadPlantAddOnSource;
import com.positivity.accounting.internal.service.TenantEventsListener;
import com.positivity.accounting.internal.service.TenantTemplateService;
import com.positivity.domainevents.invoice.InvoiceUpdatedV1;
import com.positivity.security.common.GatewaySecurityConstants;
import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.tenancy.PlatformTenant;
import com.positivity.tenancy.TenantContext;
import com.positivity.tenancy.TenantIterator;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Tenant provisioning from the accounting template on the real schema (#2526; ADR-0062 §6-§7): the
 * whole Flyway chain, the template as the repeatable seed wrote it into the platform tenant, the
 * pool as {@code pos_app} with row-level security in force, and nothing bound unless a test binds it.
 *
 * <p>Kafka rails are off in the {@code pg} profile, so the tests drive
 * {@link AccountingTenantProvisioner} directly, the way the {@code tenant.created} listener and the
 * startup sweep do, and build the listener by hand where the listener is the subject.
 *
 * <p>Every test works on tenants of its own (fresh ids), commits, and removes its tenants' rows
 * afterwards. A template that "gains an account" is the real snapshot plus that account: the
 * provisioner takes the snapshot as an argument, exactly as it does after a deploy changed the seed.
 *
 * <p>Requires Docker.
 */
@DisplayName("Tenant provisioning from the accounting template (#2526, real Postgres)")
class TenantTemplateProvisioningIT extends PostgresTenancyTestBase {

    /** Every table a provisioning run may write for a tenant. */
    private static final List<String> PROVISIONED_TABLES = List.of(
            "gl_account",
            "posting_category",
            "mapping_key",
            "gl_mapping",
            "default_gl_mapping",
            "statement_line_mappings",
            "override_policy_threshold",
            "refund_policy_config",
            "accounting_configuration",
            "accounting_template_state",
            "accounting_template_entry",
            "accounting_audit_log");

    private static final Pattern UUID_TEXT =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private static final LocalDateTime TEMPLATE_EFFECTIVE = LocalDateTime.of(2020, 1, 1, 0, 0);

    @Autowired
    private AccountingTemplateReader templateReader;

    @Autowired
    private AccountingTemplateStateLock stateLock;

    @Autowired
    private AccountingConfigurationRepository configuration;

    @Autowired
    private AccountingTenantProvisioner provisioner;

    @Autowired
    private TenantTemplateService tenantTemplateService;

    @Autowired
    private GLAccountRepository glAccounts;

    @Autowired
    private PostingCategoryRepository postingCategories;

    @Autowired
    private MappingKeyRepository mappingKeys;

    @Autowired
    private GLMappingRepository glMappings;

    @Autowired
    private StatementLineMappingRepository statementLines;

    @Autowired
    private ProcessedEventRepository processedEvents;

    @Autowired
    private GLMappingResolver glMappingResolver;

    @Autowired
    private InvoiceRevenuePostingService invoiceRevenuePostingService;

    @Autowired
    private FinancialReportingService financialReportingService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Clock clock;

    @Autowired
    private ObjectProvider<MeterRegistry> meterRegistry;

    private final List<UUID> tenants = new ArrayList<>();
    private final List<String> eventIds = new ArrayList<>();

    @AfterEach
    void removeTestTenants() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
        JdbcTemplate owner = owner();
        eventIds.forEach(id -> owner.update("DELETE FROM processed_events WHERE event_id = ?", id));
        List<String> scoped = owner.queryForList(
                "SELECT table_name FROM information_schema.columns WHERE table_schema = 'public'"
                        + " AND column_name = 'tenant_id' ORDER BY table_name",
                String.class);
        for (UUID tenant : tenants) {
            // Foreign keys decide the order; a few passes settle it without naming it here.
            for (int pass = 0; pass < 8; pass++) {
                boolean blocked = false;
                for (String table : scoped) {
                    try {
                        owner.update("DELETE FROM " + table + " WHERE tenant_id = ?", tenant);
                    } catch (RuntimeException stillReferenced) {
                        blocked = true;
                    }
                }
                if (!blocked) {
                    break;
                }
            }
        }
        tenants.clear();
        eventIds.clear();
    }

    // ------------------------------------------------------------------------------------------
    // Criteria 1, 12, 13 (first half): what a new tenant receives.
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("tenant.created gives a new tenant the generic template: chart, mappings, lines, policies")
    void newTenantReceivesTheGenericTemplate() {
        UUID t2 = newTenant();
        String eventId = newEventId();
        AccountingTemplate snapshot = templateReader.snapshot();
        AccountingTemplate generic = snapshot.only(templateReader::owns);

        Optional<AccountingTemplateApplier.Result> result =
                asTenant(t2, () -> provisioner.provision(t2, eventId, snapshot));

        assertThat(result).isPresent();
        JdbcTemplate owner = owner();
        List<String> templateAccountCodes = generic.entries().stream()
                .filter(AccountingTemplate.Account.class::isInstance)
                .map(AccountingTemplate.Entry::naturalKey)
                .toList();
        assertThat(codes(t2)).containsExactlyInAnyOrderElementsOf(templateAccountCodes);
        assertThat(codes(t2))
                .as("no retread add-on account (AW30), and no CAD or petty account of a later story")
                .doesNotContainAnyElementsOf(RetreadPlantAddOnSource.ACCOUNT_CODES)
                .doesNotContain("4940", "1250", "1260", "2210", "2220", "2230", "6010", "6115")
                .contains("1000", "1080", "1090", "1200", "2200", "3900", "4000", "6040", "6100", "6340");
        assertThat(count(t2, "posting_category")).isEqualTo(count(generic, AccountingTemplate.Category.class));
        assertThat(count(t2, "mapping_key")).isEqualTo(count(generic, AccountingTemplate.Key.class));
        assertThat(count(t2, "gl_mapping")).isEqualTo(count(generic, AccountingTemplate.GlMapping.class));
        assertThat(count(t2, "statement_line_mappings"))
                .isEqualTo(count(generic, AccountingTemplate.StatementLine.class));
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM gl_mapping WHERE tenant_id = ? AND effective_start_date <> TIMESTAMP"
                                + " '2020-01-01 00:00:00'",
                        Integer.class,
                        t2))
                .as("every GL mapping is effective from 2020-01-01")
                .isZero();
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM default_gl_mapping WHERE tenant_id = ? AND event_type ="
                                + " 'ORDER_CART_CREATE' AND organization_id IS NULL AND active",
                        Integer.class,
                        t2))
                .isEqualTo(1);
        assertThat(count(t2, "override_policy_threshold")).isEqualTo(3);
        assertThat(count(t2, "refund_policy_config")).isEqualTo(1);
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM statement_line_mappings l JOIN gl_account a ON a.gl_account_id ="
                                + " l.gl_account_id WHERE l.tenant_id = ? AND (l.location_id IS NOT NULL OR"
                                + " l.account_name IS DISTINCT FROM a.account_name)",
                        Integer.class,
                        t2))
                .as("every created line is global and names its account by name")
                .isZero();

        // New UUIDv7 ids, never a template row's id, and the system actor on every row with an actor.
        for (Map.Entry<String, String> table : Map.of(
                        "gl_account", "gl_account_id",
                        "posting_category", "posting_category_id",
                        "mapping_key", "mapping_key_id",
                        "gl_mapping", "gl_mapping_id",
                        "default_gl_mapping", "mapping_id",
                        "statement_line_mappings", "mapping_id")
                .entrySet()) {
            List<UUID> ids = owner.queryForList(
                    "SELECT " + table.getValue() + " FROM " + table.getKey() + " WHERE tenant_id = ?", UUID.class, t2);
            assertThat(ids).as("%s ids are UUIDv7", table.getKey()).isNotEmpty().allMatch(id -> id.version() == 7);
            assertThat(owner.queryForObject(
                            "SELECT count(*) FROM " + table.getKey() + " WHERE tenant_id = ? AND " + table.getValue()
                                    + " IN (SELECT " + table.getValue() + " FROM " + table.getKey()
                                    + " WHERE tenant_id = ?)",
                            Integer.class,
                            PlatformTenant.ID,
                            t2))
                    .as("no %s id is shared with the template", table.getKey())
                    .isZero();
        }
        for (String table :
                List.of("gl_account", "posting_category", "mapping_key", "gl_mapping", "default_gl_mapping")) {
            assertThat(owner.queryForObject(
                            "SELECT count(*) FROM " + table
                                    + " WHERE tenant_id = ? AND created_by <> 'tenant-template'",
                            Integer.class,
                            t2))
                    .as("%s rows are created by tenant-template", table)
                    .isZero();
        }
        for (String table : List.of("gl_account", "posting_category", "mapping_key", "default_gl_mapping")) {
            assertThat(owner.queryForObject(
                            "SELECT count(*) FROM " + table
                                    + " WHERE tenant_id = ? AND modified_by <> 'tenant-template'",
                            Integer.class,
                            t2))
                    .as("%s rows are modified by tenant-template", table)
                    .isZero();
        }

        TenantTemplateStatusResponse status = status(t2);
        assertThat(status.state()).isEqualTo(TenantTemplateState.UP_TO_DATE);
        assertThat(status.lastAppliedAt()).isNotNull();
        assertThat(status.counts().created()).isEqualTo(generic.entries().size());
        assertThat(status.counts().adopted()).isZero();
        assertThat(status.retreadPlantAddOn()).isFalse();
        assertThat(status.attention()).isEmpty();

        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM processed_events WHERE event_id = ? AND owner = 'tenant' AND tenant_id ="
                                + " ?",
                        Integer.class,
                        eventId,
                        t2))
                .isEqualTo(1);
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM accounting_audit_log WHERE tenant_id = ? AND operation ="
                                + " 'TENANT_TEMPLATE_APPLY' AND user_id = 'tenant-template' AND new_value LIKE ?",
                        Integer.class,
                        t2,
                        "%" + generic.fingerprint() + "%"))
                .isEqualTo(1);
    }

    // ------------------------------------------------------------------------------------------
    // Criterion 2: a provisioned tenant posts.
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a provisioned tenant's finalized invoice posts Dr 1200 / Cr 4000 / Cr 2200 and shows as revenue")
    void provisionedTenantPostsAFinalizedInvoice() {
        UUID t2 = newTenant();
        provision(t2, templateReader.snapshot());
        Instant finalizedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        LocalDate day = LocalDate.ofInstant(finalizedAt, clock.getZone());

        asTenant(t2, () -> invoiceRevenuePostingService.postRevenue(invoiceFact(UUID.randomUUID(), finalizedAt)));

        Map<String, BigDecimal> debits = new LinkedHashMap<>();
        Map<String, BigDecimal> credits = new LinkedHashMap<>();
        owner().query(
                        "SELECT a.account_code, l.debit_amount, l.credit_amount FROM journal_entry_line l"
                                + " JOIN gl_account a ON a.gl_account_id = l.gl_account_id"
                                + " WHERE l.tenant_id = ? AND a.tenant_id = ?",
                        rs -> {
                            debits.merge(rs.getString(1), rs.getBigDecimal(2), BigDecimal::add);
                            credits.merge(rs.getString(1), rs.getBigDecimal(3), BigDecimal::add);
                        },
                        t2,
                        t2);
        assertThat(debits.keySet()).containsExactlyInAnyOrder("1200", "4000", "2200");
        assertThat(debits.get("1200")).isEqualByComparingTo("1080.00");
        assertThat(credits.get("4000")).isEqualByComparingTo("1000.00");
        assertThat(credits.get("2200")).isEqualByComparingTo("80.00");
        assertThat(credits.get("1200")).isEqualByComparingTo("0");

        IncomeStatementReport report = asTenant(t2, () -> financialReportingService.generateIncomeStatement(day, day));
        assertThat(report.getTotalRevenue()).isEqualByComparingTo("1000.00");
        assertThat(report.getLineItems().get("IS_SALES")).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("a tenant that was never provisioned has no mapping to post through (the gap this story closes)")
    void unprovisionedTenantCannotResolveAMapping() {
        UUID fresh = newTenant();

        assertThatThrownBy(() -> asTenant(
                        fresh,
                        () -> glMappingResolver.resolveGLAccount(
                                "INVOICE_REVENUE", "SERVICE_REVENUE", LocalDateTime.now(clock))))
                .isInstanceOf(GLMappingNotConfiguredException.class);
        assertThat(status(fresh).state()).isEqualTo(TenantTemplateState.NOT_PROVISIONED);
    }

    // ------------------------------------------------------------------------------------------
    // Criterion 3: idempotent on tenant, from every path, concurrently.
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a redelivered tenant.created, a restart and two instances sweeping at once write nothing")
    void redeliveryRestartAndConcurrentSweepsWriteNothing() throws Exception {
        UUID t2 = newTenant();
        String eventId = newEventId();
        AccountingTemplate snapshot = templateReader.snapshot();
        asTenant(t2, () -> provisioner.provision(t2, eventId, snapshot));
        List<String> before = rows(t2);

        // Redelivery: the eventId is known inside the transaction.
        assertThat(asTenant(t2, () -> provisioner.provision(t2, eventId, snapshot)))
                .isEmpty();
        // Restart: the sweep finds the fingerprint it applied.
        assertThat(asTenant(t2, () -> provisioner.provision(t2, null, snapshot))
                        .orElseThrow()
                        .changed())
                .isFalse();
        // A second instance sweeping at the same moment.
        runTogether(
                () -> asTenant(t2, () -> provisioner.provision(t2, null, snapshot)),
                () -> asTenant(t2, () -> provisioner.provision(t2, null, snapshot)));

        assertThat(rows(t2)).isEqualTo(before);
        assertThat(owner().queryForObject(
                                "SELECT count(*) FROM processed_events WHERE event_id = ?", Integer.class, eventId))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("two instances provisioning a brand-new tenant at once leave one chart, one state row")
    void concurrentFirstProvisioningCreatesEachRowOnce() throws Exception {
        UUID t4 = newTenant();
        AccountingTemplate snapshot = templateReader.snapshot();
        AccountingTemplate generic = snapshot.only(templateReader::owns);

        List<Optional<AccountingTemplateApplier.Result>> results = runTogether(
                () -> asTenant(t4, () -> provisioner.provision(t4, null, snapshot)),
                () -> asTenant(t4, () -> provisioner.provision(t4, null, snapshot)));

        assertThat(results.stream().filter(result -> result.orElseThrow().changed()))
                .as("exactly one of the two runs did the work")
                .hasSize(1);
        assertThat(count(t4, "accounting_template_state")).isEqualTo(1);
        assertThat(count(t4, "accounting_template_entry"))
                .isEqualTo(generic.entries().size());
        assertThat(count(t4, "gl_account")).isEqualTo(count(generic, AccountingTemplate.Account.class));
        assertThat(count(t4, "gl_mapping")).isEqualTo(count(generic, AccountingTemplate.GlMapping.class));
        assertThat(count(t4, "override_policy_threshold")).isEqualTo(3);
        assertThat(count(t4, "refund_policy_config")).isEqualTo(1);
        assertThat(status(t4).state()).isEqualTo(TenantTemplateState.UP_TO_DATE);
    }

    // ------------------------------------------------------------------------------------------
    // Criterion 4: add-only; a tenant's changes stay.
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "a template addition is added and a renamed account, a deactivated key and a remap stay as the tenant left"
                    + " them")
    void tenantChangesSurviveATemplateAddition() {
        UUID t2 = newTenant();
        AccountingTemplate snapshot = templateReader.snapshot();
        provision(t2, snapshot);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        UUID adjustments = asTenant(
                t2,
                () -> tx.execute(status -> {
                    GLAccount undeposited = glAccounts.findByAccountCode("1090").orElseThrow();
                    undeposited.setAccountName("Till float");
                    undeposited.setModifiedBy("carol.controller");
                    glAccounts.save(undeposited);

                    MappingKey key = mappingKey("PAYMENT_APPLICATION", "UNDEPOSITED_FUNDS");
                    key.setIsActive(false);
                    key.setModifiedBy("carol.controller");
                    mappingKeys.save(key);

                    GLAccount other = glAccounts.findByAccountCode("4900").orElseThrow();
                    GLMapping revenue = glMappings
                            .findByMappingKey_MappingKeyId(mappingKey("INVOICE_REVENUE", "SERVICE_REVENUE")
                                    .getMappingKeyId())
                            .getFirst();
                    revenue.setGlAccount(other);
                    glMappings.save(revenue);
                    return other.getGlAccountId();
                }));
        List<String> afterTenantChanges = rows(t2, "gl_account", "mapping_key", "gl_mapping");

        // The template gains an account, and the service restarts.
        AccountingTemplate.Account pettyFloat = new AccountingTemplate.Account(
                "1085", "Petty Cash Float", AccountType.ASSET, null, false, null, TEMPLATE_EFFECTIVE);
        AccountingTemplateApplier.Result result = provision(t2, plus(snapshot, pettyFloat));

        assertThat(result.changed()).isTrue();
        assertThat(codes(t2)).contains("1085");
        List<String> afterRestart = rows(t2, "gl_account", "mapping_key", "gl_mapping");
        assertThat(afterRestart)
                .as("every row the tenant held is exactly as the tenant left it; one account was added")
                .containsAll(afterTenantChanges)
                .hasSize(afterTenantChanges.size() + 1);
        asTenant(
                t2,
                () -> tx.executeWithoutResult(status -> {
                    assertThat(glAccounts
                                    .findByAccountCode("1090")
                                    .orElseThrow()
                                    .getAccountName())
                            .isEqualTo("Till float");
                    assertThat(mappingKey("PAYMENT_APPLICATION", "UNDEPOSITED_FUNDS")
                                    .getIsActive())
                            .isFalse();
                    assertThat(glMappingResolver.resolveGLAccount(
                                    "INVOICE_REVENUE", "SERVICE_REVENUE", LocalDateTime.now(clock)))
                            .isEqualTo(adjustments);
                }));
        // The status read compares with the template this process read at start, so against a
        // template a test extended it says PENDING where a restarted service would say UP_TO_DATE.
        // What matters here is that nothing the tenant changed has become a conflict.
        TenantTemplateStatusResponse status = status(t2);
        assertThat(status.state())
                .as("a change the tenant made to what it received is not a conflict")
                .isNotEqualTo(TenantTemplateState.NEEDS_ATTENTION);
        assertThat(status.counts().conflict() + status.counts().withheld()).isZero();
        assertThat(status.attention()).isEmpty();
        assertThat(owner().queryForObject(
                                "SELECT outcome FROM accounting_template_entry WHERE tenant_id = ? AND entry_key ="
                                        + " 'ACCOUNT:1090'",
                                String.class,
                                t2))
                .isEqualTo("CREATED");
    }

    // ------------------------------------------------------------------------------------------
    // Criterion 5: a clash is left alone, reported in plain words, and heals.
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "the tenant's own 6295 is untouched, the template's is a CONFLICT, its mapping WITHHELD; renumbering heals"
                    + " both")
    void aClashingAccountIsLeftAloneAndHealsWhenRenumbered() {
        UUID t3 = newTenant();
        // The template as it was before S15 (#2511) put 6295 Staff Meals & Refreshments and its category in it.
        AccountingTemplate withStaffMeals = templateReader.snapshot();
        AccountingTemplate snapshot = withStaffMeals.only(
                entry -> !entry.entryKey().contains("6295") && !entry.entryKey().contains("STAFF_MEALS"));
        provision(t3, snapshot);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        // T3 was provisioned before S15 and has since opened its own 6295.
        UUID tireDisposal = asTenant(
                t3,
                () -> tx.execute(status -> {
                    GLAccount own = new GLAccount(UUIDv7Generator.generate());
                    own.setAccountCode("6295");
                    own.setAccountName("Tire disposal");
                    own.setAccountType(AccountType.EXPENSE);
                    own.setCreatedBy("carol.controller");
                    own.setModifiedBy("carol.controller");
                    return glAccounts.save(own).getGlAccountId();
                }));
        List<String> ownAccountBefore = accountRow(t3, tireDisposal);

        // S15: the template adds 6295 Staff Meals & Refreshments, the petty-expense mapping to it and the
        // STAFF_MEALS category.
        provision(t3, withStaffMeals);

        assertThat(accountRow(t3, tireDisposal)).as("T3's 6295 is unchanged").isEqualTo(ownAccountBefore);
        // Two mappings post to 6295 since #2509: the petty expense and the VENDOR_BILL expense key, both withheld.
        assertThat(count(t3, "gl_mapping"))
                .as("every mapping but the two withheld ones")
                .isEqualTo(count(withStaffMeals.only(templateReader::owns), AccountingTemplate.GlMapping.class) - 2);
        TenantTemplateStatusResponse status = status(t3);
        assertThat(status.state()).isEqualTo(TenantTemplateState.NEEDS_ATTENTION);
        assertThat(status.counts().conflict()).isEqualTo(1);
        assertThat(status.counts().withheld()).isEqualTo(3);
        assertThat(status.attention())
                .extracting(
                        TenantTemplateStatusResponse.AttentionItem::entryKey,
                        TenantTemplateStatusResponse.AttentionItem::reason,
                        TenantTemplateStatusResponse.AttentionItem::templateValue,
                        TenantTemplateStatusResponse.AttentionItem::tenantValue)
                .containsExactly(
                        tuple(
                                "ACCOUNT:6295",
                                TemplateEntryReason.ACCOUNT_DIFFERS,
                                "6295 Staff Meals & Refreshments, expense",
                                "6295 Tire disposal, expense"),
                        tuple(
                                "GL_MAPPING:REGISTER_CASH_MOVEMENT/PETTY_EXPENSE_STAFF_MEALS",
                                TemplateEntryReason.DEPENDS_ON_CONFLICT,
                                "REGISTER_CASH_MOVEMENT / PETTY_EXPENSE_STAFF_MEALS posts to account 6295",
                                "6295 Tire disposal, expense"),
                        tuple(
                                "GL_MAPPING:VENDOR_BILL/EXPENSE_STAFF_MEALS",
                                TemplateEntryReason.DEPENDS_ON_CONFLICT,
                                "VENDOR_BILL / EXPENSE_STAFF_MEALS posts to account 6295",
                                "6295 Tire disposal, expense"),
                        tuple(
                                "PETTY_EXPENSE_CATEGORY:STAFF_MEALS",
                                TemplateEntryReason.DEPENDS_ON_CONFLICT,
                                "petty-expense category STAFF_MEALS (Staff meals)",
                                "6295 Tire disposal, expense"));
        assertThat(status.attention())
                .as("business text, never ids")
                .allSatisfy(item -> assertThat(item.entryKey() + item.templateValue() + item.tenantValue())
                        .doesNotContainPattern(UUID_TEXT));
        assertThatThrownBy(() -> asTenant(
                        t3,
                        () -> glMappingResolver.resolveGLAccount(
                                "REGISTER_CASH_MOVEMENT", "PETTY_EXPENSE_STAFF_MEALS", LocalDateTime.now(clock))))
                .as("a posting through the withheld key fails rather than reaching Tire disposal")
                .isInstanceOf(GLMappingNotConfiguredException.class);

        // T3 renumbers its own account, and the service restarts.
        asTenant(
                t3,
                () -> tx.executeWithoutResult(s -> {
                    GLAccount own = glAccounts.findById(tireDisposal).orElseThrow();
                    own.setAccountCode("6299");
                    glAccounts.save(own);
                }));
        provision(t3, withStaffMeals);

        TenantTemplateStatusResponse healed = status(t3);
        assertThat(healed.state()).isNotEqualTo(TenantTemplateState.NEEDS_ATTENTION);
        assertThat(healed.attention()).isEmpty();
        assertThat(healed.counts().conflict() + healed.counts().withheld()).isZero();
        asTenant(
                t3,
                () -> tx.executeWithoutResult(s -> {
                    GLAccount staffMeals = glAccounts.findByAccountCode("6295").orElseThrow();
                    assertThat(staffMeals.getAccountName()).isEqualTo("Staff Meals & Refreshments");
                    assertThat(staffMeals.getGlAccountId()).isNotEqualTo(tireDisposal);
                    assertThat(glAccounts.findById(tireDisposal).orElseThrow().getAccountName())
                            .isEqualTo("Tire disposal");
                    assertThat(glMappingResolver.resolveGLAccount(
                                    "REGISTER_CASH_MOVEMENT", "PETTY_EXPENSE_STAFF_MEALS", LocalDateTime.now(clock)))
                            .isEqualTo(staffMeals.getGlAccountId());
                }));
    }

    // ------------------------------------------------------------------------------------------
    // Criterion 6 (reset database) and 13 (the default tenant keeps the add-on).
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("on a reset database the sweep gives the default tenant the template and adopts its V2 rows, add-on"
            + " included")
    void theSweepProvisionsTheDefaultTenantAndAdoptsItsV2Rows() {
        AccountingTemplateStartupSweep sweep = new AccountingTemplateStartupSweep(
                new TenantIterator(() -> List.of(TENANT_A)), templateReader, provisioner, meterRegistry);
        JdbcTemplate owner = owner();
        List<String> v2Before = owner.queryForList(
                "SELECT row_to_json(a)::text FROM gl_account a WHERE tenant_id = ? AND gl_account_id::text LIKE"
                        + " 'a1000000-%' ORDER BY 1",
                String.class, TENANT_A);
        assertThat(v2Before)
                .as("V2 gave the default tenant its 42 labour and overhead accounts")
                .hasSize(42);

        sweep.run(new DefaultApplicationArguments());

        TenantTemplateStatusResponse status = status(TENANT_A);
        assertThat(status.state()).isEqualTo(TenantTemplateState.UP_TO_DATE);
        assertThat(status.retreadPlantAddOn())
                .as("V5 recorded the default tenant's choice")
                .isTrue();
        assertThat(status.counts().created() + status.counts().adopted())
                .as("the default tenant holds the whole template, add-on included")
                .isEqualTo(templateReader.snapshot().entries().size());
        assertThat(codes(TENANT_A)).contains("1000", "1200", "4000").containsAll(RetreadPlantAddOnSource.ACCOUNT_CODES);
        assertThat(owner.queryForList(
                        "SELECT row_to_json(a)::text FROM gl_account a WHERE tenant_id = ? AND gl_account_id::text"
                                + " LIKE 'a1000000-%' ORDER BY 1",
                        String.class, TENANT_A))
                .as("nothing was written to a V2 row")
                .isEqualTo(v2Before);
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM accounting_template_entry e JOIN gl_account a ON a.gl_account_id ="
                                + " e.target_row_id WHERE e.tenant_id = ? AND e.kind = 'ACCOUNT' AND e.outcome ="
                                + " 'ADOPTED' AND a.gl_account_id::text LIKE 'a1000000-%'",
                        Integer.class, TENANT_A))
                .as("every V2 account is adopted, the seven add-on accounts among them")
                .isEqualTo(42);
        for (String code : RetreadPlantAddOnSource.ACCOUNT_CODES) {
            assertThat(owner.queryForObject(
                            "SELECT outcome FROM accounting_template_entry WHERE tenant_id = ? AND entry_key = ?",
                            String.class,
                            TENANT_A,
                            "STATEMENT_LINE:LABOR_OVERHEAD:" + code))
                    .as("the default tenant's Labor & Overhead line for add-on account %s", code)
                    .isEqualTo("ADOPTED");
        }
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM statement_line_mappings WHERE tenant_id = ? AND statement_type ="
                                + " 'LABOR_OVERHEAD'",
                        Integer.class,
                        TENANT_A))
                .as("no Labor & Overhead line was added beside V2's")
                .isEqualTo(42);
    }

    // ------------------------------------------------------------------------------------------
    // Criterion 7: the statement-line refresh.
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a recoded income-statement line reaches an untouched tenant line, not a renamed one, one line each")
    void statementLineRecodeReachesUntouchedLinesOnly() {
        UUID untouched = newTenant();
        UUID renamed = newTenant();
        AccountingTemplate snapshot = templateReader.snapshot();
        provision(untouched, snapshot);
        provision(renamed, snapshot);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        asTenant(
                renamed,
                () -> tx.executeWithoutResult(status -> {
                    StatementLineMapping line = incomeLine("4000");
                    line.setLineDescription("Shop sales");
                    statementLines.save(line);
                }));

        // The seed ships 4000 on IS_SALES (S35, #2524); a later recode of that line, as S35 itself did
        // from REVENUE, must reach only the untouched tenant.
        AccountingTemplate.StatementLine recoded = new AccountingTemplate.StatementLine(
                StatementType.INCOME_STATEMENT, "4000", "IS_REVENUE", null, "Revenue", 10, OperationType.SUM);
        AccountingTemplate recodedTemplate = AccountingTemplate.of(snapshot.entries().stream()
                .map(entry -> entry.entryKey().equals(recoded.entryKey()) ? recoded : entry)
                .toList());
        provision(untouched, recodedTemplate);
        provision(renamed, recodedTemplate);

        asTenant(
                untouched,
                () -> tx.executeWithoutResult(status -> {
                    StatementLineMapping line = incomeLine("4000");
                    assertThat(line.getStatementLineCode()).isEqualTo("IS_REVENUE");
                    assertThat(line.getLineDescription()).isEqualTo("Revenue");
                    assertThat(line.getDisplayOrder()).isEqualTo(10);
                }));
        asTenant(
                renamed,
                () -> tx.executeWithoutResult(status -> {
                    StatementLineMapping line = incomeLine("4000");
                    assertThat(line.getStatementLineCode()).isEqualTo("IS_SALES");
                    assertThat(line.getLineDescription()).isEqualTo("Shop sales");
                }));
        for (UUID tenant : List.of(untouched, renamed)) {
            assertThat(owner().queryForObject(
                                    "SELECT count(*) FROM statement_line_mappings l JOIN gl_account a ON"
                                            + " a.gl_account_id = l.gl_account_id WHERE l.tenant_id = ? AND"
                                            + " l.statement_type = 'INCOME_STATEMENT' AND a.account_code = '4000'",
                                    Integer.class,
                                    tenant))
                    .as("exactly one income-statement line for 4000")
                    .isEqualTo(1);
        }
        assertThat(status(untouched).counts().refreshed()).isEqualTo(1);
        assertThat(status(renamed).counts().refreshed()).isZero();
        assertThat(status(renamed).attention())
                .as("a line the tenant relabelled is not a conflict")
                .isEmpty();
    }

    // ------------------------------------------------------------------------------------------
    // Criterion 8: the repeatable seed.
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("the seed run twice keeps one platform row per entry, touches no other tenant, and shares no id")
    void theSeedIsRepeatableAndWritesOnlyThePlatformTenant() throws SQLException {
        UUID t2 = newTenant();
        provision(t2, templateReader.snapshot());
        JdbcTemplate owner = owner();
        Map<String, Integer> platformBefore = countsByTable(PlatformTenant.ID);
        List<String> othersBefore = rowsOfEveryOtherTenant();

        runSeed();
        runSeed();

        assertThat(countsByTable(PlatformTenant.ID))
                .as("each template row once, however often the seed runs")
                .isEqualTo(platformBefore);
        assertThat(rowsOfEveryOtherTenant())
                .as("no other tenant gains, loses or sees a row change")
                .isEqualTo(othersBefore);
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM (SELECT account_code FROM gl_account WHERE tenant_id = ? GROUP BY"
                                + " account_code HAVING count(*) > 1) d",
                        Integer.class,
                        PlatformTenant.ID))
                .isZero();
        String prefix = "accounting-template:" + PlatformTenant.ID + ":";
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM gl_account WHERE tenant_id = ? AND gl_account_id <> md5(? ||"
                                + " 'ACCOUNT:' || account_code)::uuid",
                        Integer.class,
                        PlatformTenant.ID,
                        prefix))
                .as("template account ids are the platform-qualified md5 of the natural key")
                .isZero();
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM gl_mapping m JOIN gl_account a ON a.gl_account_id = m.gl_account_id"
                                + " WHERE m.tenant_id = ? AND a.tenant_id <> ?",
                        Integer.class,
                        PlatformTenant.ID,
                        PlatformTenant.ID))
                .as("a template mapping refers to a template account")
                .isZero();
        for (Map.Entry<String, String> table : Map.of(
                        "gl_account", "gl_account_id",
                        "posting_category", "posting_category_id",
                        "mapping_key", "mapping_key_id",
                        "gl_mapping", "gl_mapping_id",
                        "default_gl_mapping", "mapping_id",
                        "statement_line_mappings", "mapping_id")
                .entrySet()) {
            assertThat(owner.queryForObject(
                            "SELECT count(*) FROM " + table.getKey() + " p WHERE p.tenant_id = ? AND EXISTS (SELECT 1"
                                    + " FROM " + table.getKey() + " o WHERE o.tenant_id <> ? AND o."
                                    + table.getValue() + " = p." + table.getValue() + ")",
                            Integer.class,
                            PlatformTenant.ID,
                            PlatformTenant.ID))
                    .as("no template %s id equals a tenant row's id", table.getKey())
                    .isZero();
        }
        // #2511 (S15) adds 1080, 3000, 3900, 6295, 6375 and 6380 (6040 takes 6115's place), three categories,
        // fifteen keys and mappings, and the balance-sheet lines of 1080, 3000 and 3900. #2572 adds the
        // OPENING_BALANCE category with its OPENING_BALANCE_EQUITY key and mapping (3900). #2509 (S12, AW38-AW40)
        // adds 2100, 5050 and 5060, the GOODS_RECEIPT and VENDOR_BILL categories with their 3 + 13 keys and
        // mappings, and the statement lines of 2100, 5050 and 5060.
        assertThat(platformBefore.get("gl_account")).isEqualTo(71);
        assertThat(platformBefore.get("posting_category")).isEqualTo(19);
        assertThat(platformBefore.get("mapping_key")).isEqualTo(63);
        assertThat(platformBefore.get("gl_mapping")).isEqualTo(63);
        assertThat(platformBefore.get("default_gl_mapping")).isEqualTo(1);
        // 42 L&O + 12 (#2524) + 3 (#2511) + 3 (#2509)
        assertThat(platformBefore.get("statement_line_mappings")).isEqualTo(60);
    }

    // ------------------------------------------------------------------------------------------
    // Criterion 9: the listener, built by hand (Kafka rails are off in this profile).
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("the listener provisions a created tenant, and records but never provisions the platform tenant")
    void theListenerProvisionsACreatedTenantButNeverThePlatformTenant() {
        TenantEventsListener listener =
                new TenantEventsListener(new ObjectMapper(), processedEvents, templateReader, provisioner);
        UUID t6 = newTenant();
        String createdEvent = newEventId();
        String platformEvent = newEventId();

        // The record interceptor binds the tenant of the message header; pos-tenant's facts carry the platform tenant.
        asTenant(PlatformTenant.ID, () -> {
            listener.onTenantEvent(created(createdEvent, t6));
            listener.onTenantEvent(created(createdEvent, t6));
            listener.onTenantEvent(created(platformEvent, PlatformTenant.ID));
        });

        assertThat(status(t6).state()).isEqualTo(TenantTemplateState.UP_TO_DATE);
        assertThat(count(t6, "accounting_template_state")).isEqualTo(1);
        assertThat(TenantContext.current())
                .as("the listener leaves no binding behind")
                .isEmpty();
        JdbcTemplate owner = owner();
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM processed_events WHERE event_id IN (?, ?) AND owner = 'tenant'",
                        Integer.class,
                        createdEvent,
                        platformEvent))
                .isEqualTo(2);
        assertThat(count(PlatformTenant.ID, "accounting_template_state"))
                .as("the platform tenant holds the template and is never provisioned")
                .isZero();
        assertThat(count(PlatformTenant.ID, "accounting_template_entry")).isZero();
        assertThat(count(PlatformTenant.ID, "override_policy_threshold")).isZero();
    }

    // ------------------------------------------------------------------------------------------
    // Criterion 10: isolation.
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("provisioning B writes only B's rows; A sees none of them through a repository or raw JDBC")
    void provisioningOneTenantWritesOnlyItsRows() {
        UUID a = newTenant();
        UUID b = newTenant();
        AccountingTemplate snapshot = templateReader.snapshot();
        provision(a, snapshot);
        List<String> aBefore = rows(a);
        Map<String, Integer> platformBefore = countsByTable(PlatformTenant.ID);
        List<String> platformRowsBefore =
                rows(PlatformTenant.ID, "gl_account", "gl_mapping", "statement_line_mappings");

        provision(b, snapshot);

        assertThat(rows(a)).as("A's rows are untouched").isEqualTo(aBefore);
        assertThat(countsByTable(PlatformTenant.ID))
                .as("the template was only read")
                .isEqualTo(platformBefore);
        assertThat(rows(PlatformTenant.ID, "gl_account", "gl_mapping", "statement_line_mappings"))
                .isEqualTo(platformRowsBefore);
        Map<String, Integer> bRows = countsByTable(b);
        assertThat(bRows.get("gl_account")).isEqualTo(countsByTable(a).get("gl_account"));

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        asTenant(
                a,
                () -> tx.executeWithoutResult(status -> {
                    assertThat(glAccounts.findAll())
                            .as("the repository, as A, returns A's accounts only")
                            .hasSize(countsByTable(a).get("gl_account"))
                            .allMatch(account -> a.equals(account.getTenantId()));
                    JdbcTemplate pool = new JdbcTemplate(dataSource);
                    for (String table : PROVISIONED_TABLES) {
                        assertThat(pool.queryForObject(
                                        "SELECT count(*) FROM " + table + " WHERE tenant_id = ?", Integer.class, b))
                                .as("raw JDBC as A sees none of B's %s rows", table)
                                .isZero();
                        assertThat(pool.queryForObject(
                                        "SELECT count(*) FROM " + table + " WHERE tenant_id = ?",
                                        Integer.class,
                                        PlatformTenant.ID))
                                .as("raw JDBC as A sees no template %s row", table)
                                .isZero();
                    }
                }));
    }

    // ------------------------------------------------------------------------------------------
    // Criterion 13: the retread add-on.
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "the retread add-on is absent until a controller turns it on, audited with the caller; a replay changes"
                    + " nothing")
    void retreadAddOnIsCreatedOnlyOnTheTenantsChoice() {
        UUID t2 = newTenant();
        AccountingTemplate snapshot = templateReader.snapshot();
        provision(t2, snapshot);
        JdbcTemplate owner = owner();
        assertThat(codes(t2))
                .doesNotContainAnyElementsOf(RetreadPlantAddOnSource.ACCOUNT_CODES)
                .doesNotContain("4940");
        int linesBefore = count(t2, "statement_line_mappings");
        UUID requestId = UUIDv7Generator.generate();
        EnableTemplateAddOnRequest request =
                new EnableTemplateAddOnRequest("We run a retread plant at the Tulsa shop", requestId);
        signInAs("carol.controller");

        TenantTemplateStatusResponse enabled =
                asTenant(t2, () -> tenantTemplateService.enableRetreadPlantAddOn(request));

        assertThat(enabled.retreadPlantAddOn()).isTrue();
        assertThat(enabled.state()).isEqualTo(TenantTemplateState.UP_TO_DATE);
        assertThat(codes(t2)).containsAll(RetreadPlantAddOnSource.ACCOUNT_CODES);
        assertThat(count(t2, "statement_line_mappings"))
                .isEqualTo(linesBefore + RetreadPlantAddOnSource.ACCOUNT_CODES.size());
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM gl_account WHERE tenant_id = ? AND created_by = 'tenant-template' AND"
                                + " account_code IN ('6350', '6450', '6470', '6510', '6520', '6530', '4940')",
                        Integer.class,
                        t2))
                .as("the add-on's accounts are the template's, whoever asked for them")
                .isEqualTo(7);
        assertThat(owner.queryForMap(
                        "SELECT config_value, created_by FROM accounting_configuration WHERE tenant_id = ? AND"
                                + " config_key = 'RETREAD_PLANT_ADD_ON'",
                        t2))
                .containsEntry("config_value", "true")
                .containsEntry("created_by", "carol.controller");
        assertThat(owner.queryForMap(
                        "SELECT user_id, justification, new_value FROM accounting_audit_log WHERE tenant_id = ? AND"
                                + " operation = 'TENANT_TEMPLATE_ADD_ON_ENABLE'",
                        t2))
                .containsEntry("user_id", "carol.controller")
                .containsEntry("justification", "We run a retread plant at the Tulsa shop")
                .extractingByKey("new_value")
                .asString()
                .contains(requestId.toString());

        List<String> afterFirst = rows(t2);
        TenantTemplateStatusResponse replayed =
                asTenant(t2, () -> tenantTemplateService.enableRetreadPlantAddOn(request));

        assertThat(rows(t2)).as("a replay changes nothing").isEqualTo(afterFirst);
        assertThat(replayed).isEqualTo(enabled);
    }

    // ------------------------------------------------------------------------------------------
    // Review round 1: location overrides, and the lock before any decision.
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "a tenant holding only a location override of a new template line gets the global line; the override is"
                    + " untouched")
    void locationOverrideIsNotAdoptedAsTheGlobalLine() {
        UUID t2 = newTenant();
        AccountingTemplate snapshot = templateReader.snapshot();
        provision(t2, snapshot);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        // T2 opened its own 4100 and reports it on the income statement for one location only.
        UUID overrideId = asTenant(
                t2,
                () -> tx.execute(status -> {
                    GLAccount parts = new GLAccount(UUIDv7Generator.generate());
                    parts.setAccountCode("4100");
                    parts.setAccountName("Parts Revenue");
                    parts.setAccountType(AccountType.REVENUE);
                    parts.setCreatedBy("carol.controller");
                    parts.setModifiedBy("carol.controller");
                    glAccounts.save(parts);
                    return statementLines
                            .save(StatementLineMapping.builder()
                                    .glAccount(parts)
                                    .accountName("Parts Revenue")
                                    .statementType(StatementType.INCOME_STATEMENT)
                                    .statementLineCode("TULSA_PARTS")
                                    .lineDescription("Tulsa parts counter")
                                    .displayOrder(5)
                                    .operation(OperationType.SUM)
                                    .locationId("TULSA")
                                    .build())
                            .getMappingId();
                }));
        List<String> overrideBefore = owner().queryForList(
                        "SELECT row_to_json(l)::text FROM statement_line_mappings l WHERE tenant_id = ? AND mapping_id"
                                + " = ?",
                        String.class,
                        t2,
                        overrideId);

        // The template gains 4100 and its income-statement line.
        provision(
                t2,
                plus(
                        snapshot,
                        new AccountingTemplate.Account(
                                "4100", "Parts Revenue", AccountType.REVENUE, null, false, null, TEMPLATE_EFFECTIVE),
                        new AccountingTemplate.StatementLine(
                                StatementType.INCOME_STATEMENT,
                                "4100",
                                "REVENUE",
                                null,
                                "REVENUE",
                                2,
                                OperationType.SUM)));

        List<Map<String, Object>> lines = owner().queryForList(
                        "SELECT l.location_id, l.statement_line_code, l.account_name FROM statement_line_mappings l"
                                + " JOIN gl_account a ON a.gl_account_id = l.gl_account_id WHERE l.tenant_id = ? AND"
                                + " a.account_code = '4100' AND l.statement_type = 'INCOME_STATEMENT' ORDER BY"
                                + " l.location_id NULLS FIRST",
                        t2);
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0))
                .containsEntry("location_id", null)
                .containsEntry("statement_line_code", "REVENUE")
                .containsEntry("account_name", "Parts Revenue");
        assertThat(lines.get(1))
                .containsEntry("location_id", "TULSA")
                .containsEntry("statement_line_code", "TULSA_PARTS");
        assertThat(owner().queryForList(
                                "SELECT row_to_json(l)::text FROM statement_line_mappings l WHERE tenant_id = ? AND"
                                        + " mapping_id = ?",
                                String.class,
                                t2,
                                overrideId))
                .as("the override is exactly as the tenant left it")
                .isEqualTo(overrideBefore);
        assertThat(owner().queryForObject(
                                "SELECT outcome FROM accounting_template_entry WHERE tenant_id = ? AND entry_key ="
                                        + " 'STATEMENT_LINE:INCOME_STATEMENT:4100'",
                                String.class,
                                t2))
                .isEqualTo("CREATED");
        assertThat(owner().queryForObject(
                                "SELECT outcome FROM accounting_template_entry WHERE tenant_id = ? AND entry_key ="
                                        + " 'ACCOUNT:4100'",
                                String.class,
                                t2))
                .isEqualTo("ADOPTED");
    }

    @Test
    @DisplayName(
            "a sweep that arrives while an add-on choice is being committed applies the full template, not the stale"
                    + " generic one")
    void sweepWaitingBehindAnAddOnChoiceAppliesTheFullTemplate() throws Exception {
        UUID t2 = newTenant();
        AccountingTemplate snapshot = templateReader.snapshot();
        provision(t2, snapshot);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CountDownLatch lockHeld = new CountDownLatch(1);

        // The add-on choice: the state row locked first, the choice written, a slow commit.
        Callable<Boolean> choice = () -> asTenant(
                t2,
                () -> tx.execute(status -> {
                    stateLock.acquire();
                    AccountingConfiguration row = new AccountingConfiguration();
                    row.setConfigKey(RetreadPlantAddOnSource.CONFIG_KEY);
                    row.setConfigValue(RetreadPlantAddOnSource.ON);
                    configuration.saveAndFlush(row);
                    lockHeld.countDown();
                    try {
                        Thread.sleep(1500);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return true;
                }));
        // The sweep: started once the choice holds the lock, so it must wait for the commit.
        Callable<Boolean> sweep = () -> {
            assertThat(lockHeld.await(30, TimeUnit.SECONDS)).isTrue();
            return asTenant(t2, () -> provisioner.provision(t2, null, snapshot))
                    .orElseThrow()
                    .changed();
        };

        List<Boolean> results = runTogether(choice, sweep);

        assertThat(results.get(1)).as("the sweep applied the add-on").isTrue();
        assertThat(codes(t2)).containsAll(RetreadPlantAddOnSource.ACCOUNT_CODES);
        TenantTemplateStatusResponse status = status(t2);
        assertThat(status.retreadPlantAddOn()).isTrue();
        assertThat(status.state())
                .as("the fingerprint recorded is the full template's")
                .isEqualTo(TenantTemplateState.UP_TO_DATE);
    }

    // ------------------------------------------------------------------------------------------
    // Helpers.
    // ------------------------------------------------------------------------------------------

    private UUID newTenant() {
        UUID tenant = UUIDv7Generator.generate();
        tenants.add(tenant);
        return tenant;
    }

    private String newEventId() {
        String eventId = UUIDv7Generator.generate().toString();
        eventIds.add(eventId);
        return eventId;
    }

    private AccountingTemplateApplier.Result provision(UUID tenant, AccountingTemplate snapshot) {
        return asTenant(tenant, () -> provisioner.provision(tenant, null, snapshot))
                .orElseThrow();
    }

    private TenantTemplateStatusResponse status(UUID tenant) {
        return asTenant(tenant, () -> tenantTemplateService.status());
    }

    private static AccountingTemplate plus(AccountingTemplate template, AccountingTemplate.Entry... extra) {
        List<AccountingTemplate.Entry> entries = new ArrayList<>(template.entries());
        entries.addAll(List.of(extra));
        return AccountingTemplate.of(entries);
    }

    private static int count(AccountingTemplate template, Class<? extends AccountingTemplate.Entry> kind) {
        return (int) template.entries().stream().filter(kind::isInstance).count();
    }

    private MappingKey mappingKey(String category, String keyName) {
        UUID categoryId =
                postingCategories.findByCategoryName(category).orElseThrow().getPostingCategoryId();
        return mappingKeys
                .findByPostingCategory_PostingCategoryIdAndKeyName(categoryId, keyName)
                .orElseThrow();
    }

    private StatementLineMapping incomeLine(String accountCode) {
        UUID accountId = glAccounts.findByAccountCode(accountCode).orElseThrow().getGlAccountId();
        return statementLines.findByGlAccount_GlAccountId(accountId).stream()
                .filter(line -> line.getStatementType() == StatementType.INCOME_STATEMENT)
                .findFirst()
                .orElseThrow();
    }

    private static JdbcTemplate owner() {
        return new JdbcTemplate(ownerDataSource());
    }

    private int count(UUID tenant, String table) {
        return owner().queryForObject("SELECT count(*) FROM " + table + " WHERE tenant_id = ?", Integer.class, tenant);
    }

    private List<String> codes(UUID tenant) {
        return owner().queryForList(
                        "SELECT account_code FROM gl_account WHERE tenant_id = ? ORDER BY account_code",
                        String.class,
                        tenant);
    }

    private Map<String, Integer> countsByTable(UUID tenant) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String table : PROVISIONED_TABLES) {
            counts.put(table, count(tenant, table));
        }
        return counts;
    }

    /** Every row a provisioning run may write for the tenant, column for column: equal lists mean nothing changed. */
    private List<String> rows(UUID tenant) {
        return rows(tenant, PROVISIONED_TABLES.toArray(String[]::new));
    }

    private List<String> rows(UUID tenant, String... tables) {
        List<String> rows = new ArrayList<>();
        for (String table : tables) {
            owner().queryForList(
                            "SELECT row_to_json(t)::text FROM " + table + " t WHERE tenant_id = ? ORDER BY 1",
                            String.class,
                            tenant)
                    .forEach(row -> rows.add(table + " " + row));
        }
        return rows;
    }

    private List<String> accountRow(UUID tenant, UUID accountId) {
        return owner().queryForList(
                        "SELECT row_to_json(a)::text FROM gl_account a WHERE tenant_id = ? AND gl_account_id = ?",
                        String.class,
                        tenant,
                        accountId);
    }

    private List<String> rowsOfEveryOtherTenant() {
        List<String> rows = new ArrayList<>();
        for (String table : List.of(
                "gl_account",
                "posting_category",
                "mapping_key",
                "gl_mapping",
                "default_gl_mapping",
                "statement_line_mappings")) {
            owner().queryForList(
                            "SELECT row_to_json(t)::text FROM " + table + " t WHERE tenant_id <> ? ORDER BY 1",
                            String.class,
                            PlatformTenant.ID)
                    .forEach(row -> rows.add(table + " " + row));
        }
        return rows;
    }

    /** Runs the repeatable seed the way Flyway does: as the owner, in one transaction. */
    private static void runSeed() throws SQLException {
        try (Connection connection = ownerDataSource().getConnection()) {
            connection.setAutoCommit(false);
            ScriptUtils.executeSqlScript(
                    connection, new ClassPathResource("db/migration/R__seed_reference_accounting.sql"));
            connection.commit();
        }
    }

    @SafeVarargs
    private static <T> List<T> runTogether(Callable<T>... work) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(work.length);
        try {
            CyclicBarrier start = new CyclicBarrier(work.length);
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : work) {
                futures.add(pool.submit(() -> {
                    start.await(30, TimeUnit.SECONDS);
                    return task.call();
                }));
            }
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(120, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private static void signInAs(String username) {
        UsernamePasswordAuthenticationToken caller = new UsernamePasswordAuthenticationToken(
                username, "n/a", List.of(new SimpleGrantedAuthority("accounting:coa:create")));
        caller.setDetails(Map.of(GatewaySecurityConstants.DETAIL_USERNAME, username));
        SecurityContextHolder.getContext().setAuthentication(caller);
    }

    private static String created(String eventId, UUID tenantId) {
        return """
            {"eventId":"%s","eventType":"tenant.created","aggregateVersion":1,
             "payload":{"tenantId":"%s","slug":"acme","displayName":"Acme","status":"PENDING",
                        "initialAdminEmail":"owner@acme.example"}}
            """.formatted(eventId, tenantId);
    }

    /** An {@code invoice.invoice.updated} fact for a 1,000.00 sale plus 80.00 tax, finalized. */
    private static InvoiceUpdatedV1 invoiceFact(UUID invoiceId, Instant finalizedAt) {
        return new InvoiceUpdatedV1(
                invoiceId,
                "INV-2526-0001",
                null,
                null,
                null,
                null,
                "FINALIZED",
                new BigDecimal("1000.00"),
                new BigDecimal("80.00"),
                new BigDecimal("1080.00"),
                BigDecimal.ZERO,
                finalizedAt,
                finalizedAt,
                null,
                null,
                null,
                null,
                null,
                null);
    }
}
