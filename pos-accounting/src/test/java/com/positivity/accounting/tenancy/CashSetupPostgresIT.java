package com.positivity.accounting.tenancy;

import static com.positivity.tenancy.testing.TenantTestSupport.asTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.accounting.internal.bankrec.dto.CloseReadinessResponse;
import com.positivity.accounting.internal.bankrec.enums.ReadinessCheckCode;
import com.positivity.accounting.internal.bankrec.enums.ReadinessSeverity;
import com.positivity.accounting.internal.bankrec.readmodel.BankReconciliationCloseReadiness;
import com.positivity.accounting.internal.dto.PettyExpenseCategoryCreateRequest;
import com.positivity.accounting.internal.dto.PettyExpenseCategoryDeactivateRequest;
import com.positivity.accounting.internal.dto.PettyExpenseCategoryRemapRequest;
import com.positivity.accounting.internal.dto.PettyExpenseCategoryResponse;
import com.positivity.accounting.internal.dto.PettyExpenseCategoryUpdateRequest;
import com.positivity.accounting.internal.dto.RegisterFloatChangeRequest;
import com.positivity.accounting.internal.dto.RegisterFloatGoLiveRequest;
import com.positivity.accounting.internal.entity.AccountingPeriod;
import com.positivity.accounting.internal.enums.AccountingPeriodStatus;
import com.positivity.accounting.internal.enums.PettyExpenseCategoryChangeType;
import com.positivity.accounting.internal.enums.PettyExpenseCategoryStatus;
import com.positivity.accounting.internal.exception.AccountingPeriodClosedException;
import com.positivity.accounting.internal.exception.CashSetupException;
import com.positivity.accounting.internal.repository.GLAccountRepository;
import com.positivity.accounting.internal.repository.RegisterFloatRepository;
import com.positivity.accounting.internal.service.CashAndPayablesSettings;
import com.positivity.accounting.internal.service.GLMappingResolver;
import com.positivity.accounting.internal.service.JournalEntryService;
import com.positivity.accounting.internal.service.PettyExpenseCategoryService;
import com.positivity.accounting.internal.service.RegisterFloatService;
import com.positivity.security.common.GatewaySecurityConstants;
import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.tenancy.TenantContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The chart, float and petty-expense categories on the full Flyway chain (#2511): what a fresh tenant
 * receives from the template, the float commands posting balanced entries through the period gate, the
 * reversal re-derivation, the category commands, the readiness warning and two-tenant isolation (ADR-0062).
 *
 * <p>Requires Docker.
 */
@DisplayName("Chart, register float and petty-expense categories (#2511, real Postgres)")
class CashSetupPostgresIT extends PostgresTenancyTestBase {

    private static final UUID LOCATION = UUID.fromString("019a0000-0000-7000-8000-00000000a001");
    private static final LocalDate GO_LIVE = LocalDate.of(2026, 10, 1);

    @Autowired
    private RegisterFloatService floats;

    @Autowired
    private PettyExpenseCategoryService categories;

    @Autowired
    private RegisterFloatRepository floatRows;

    @Autowired
    private JournalEntryService journalEntries;

    @Autowired
    private GLMappingResolver resolver;

    @Autowired
    private GLAccountRepository glAccounts;

    @Autowired
    private CashAndPayablesSettings settings;

    @Autowired
    private BankReconciliationCloseReadiness readiness;

    @Autowired
    private java.time.Clock clock;

    private final List<UUID> tenants = new ArrayList<>();

    @AfterEach
    void removeTestTenants() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
        JdbcTemplate owner = new JdbcTemplate(ownerDataSource());
        List<String> scoped = owner.queryForList(
                "SELECT table_name FROM information_schema.columns WHERE table_schema = 'public'"
                        + " AND column_name = 'tenant_id' AND table_name NOT LIKE 'pg_%' ORDER BY table_name",
                String.class);
        for (UUID tenant : tenants) {
            owner.update(
                    "UPDATE journal_entry SET reversal_journal_entry_id = NULL, reversed_by_journal_entry_id = NULL"
                            + " WHERE tenant_id = ?",
                    tenant);
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
    }

    @Test
    @DisplayName(
            "AC1/AC2: a fresh tenant holds S15's accounts, categories and mappings once, and the settings' defaults")
    void freshTenantReceivesTheChart() {
        UUID tenant = tenant();
        provisionAccounting(tenant);

        JdbcTemplate owner = new JdbcTemplate(ownerDataSource());
        Map<String, String> subtypes = new LinkedHashMap<>();
        owner.query(
                "SELECT account_code, coalesce(account_subtype, '-') FROM gl_account WHERE tenant_id = ?",
                rs -> {
                    subtypes.put(rs.getString(1), rs.getString(2));
                },
                tenant);
        assertThat(subtypes)
                .containsEntry("1080", "CASH_ON_HAND")
                .containsKeys("3000", "3900", "6040", "6295", "6375", "6380", "6100", "6340")
                .doesNotContainKeys("6115", "6010", "4940");
        Map<String, String> petty = asTenant(tenant, () -> {
            Map<String, String> accounts = new LinkedHashMap<>();
            for (String code : List.of(
                    "SHOP_SUPPLIES",
                    "SMALL_TOOLS",
                    "OFFICE_SUPPLIES",
                    "BUILDING_REPAIRS",
                    "EQUIPMENT_REPAIRS",
                    "POSTAGE_SHIPPING",
                    "CLEANING_JANITORIAL",
                    "STAFF_MEALS",
                    "VEHICLE_FUEL")) {
                accounts.put(
                        code,
                        code(resolver.resolveGLAccount(
                                "REGISTER_CASH_MOVEMENT", "PETTY_EXPENSE_" + code, GO_LIVE.atStartOfDay())));
            }
            return accounts;
        });
        assertThat(petty.values())
                .containsExactly("6340", "6430", "6370", "6210", "6410", "6380", "6375", "6295", "6250");
        assertThat(asTenant(
                        tenant,
                        () -> List.of(
                                code(resolver.resolveGLAccount(
                                        "REGISTER_FLOAT", "REGISTER_FLOAT", GO_LIVE.atStartOfDay())),
                                code(resolver.resolveGLAccount(
                                        "REGISTER_FLOAT", "OPENING_BALANCE_EQUITY", GO_LIVE.atStartOfDay())),
                                code(resolver.resolveGLAccount(
                                        "BANK_DEPOSIT", "UNDEPOSITED_FUNDS", GO_LIVE.atStartOfDay())),
                                code(resolver.resolveGLAccount(
                                        "BANK_DEPOSIT", "CASH_CLEARING", GO_LIVE.atStartOfDay())),
                                code(resolver.resolveGLAccount(
                                        "REGISTER_CASH_MOVEMENT", "ACCOUNTS_PAYABLE", GO_LIVE.atStartOfDay())),
                                code(resolver.resolveGLAccount(
                                        "REGISTER_OVER_SHORT", "CASH_SHORT", GO_LIVE.atStartOfDay())))))
                .containsExactly("1080", "3900", "1090", "1095", "2000", "6040");
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM petty_expense_category WHERE tenant_id = ? AND status = 'ACTIVE'",
                        Integer.class,
                        tenant))
                .isEqualTo(9);

        // A second run changes nothing.
        int accounts = count(tenant, "gl_account");
        int mappings = count(tenant, "gl_mapping");
        provisionAccounting(tenant);
        assertThat(count(tenant, "gl_account")).isEqualTo(accounts);
        assertThat(count(tenant, "gl_mapping")).isEqualTo(mappings);
        assertThat(count(tenant, "petty_expense_category")).isEqualTo(9);

        assertThat(asTenant(tenant, () -> settings.settings()))
                .isEqualTo(new CashAndPayablesSettings.Settings("NET30", null));
    }

    @Test
    @DisplayName("AC3-AC6, AC12: go-live, once only, reverse and re-run, change float, idempotency, readiness warning")
    void floatLifecycle() {
        UUID tenant = tenant();
        provisionAccounting(tenant);
        signIn("controller.cfo", "accounting:float:manage");

        var goLive = asTenant(tenant, () -> floats.establishGoLive("T-1", goLive("200.00")));
        Map<String, String> lines = lines(tenant, goLive.response().journalEntryId());
        assertThat(lines).containsOnly(Map.entry("1080", "D200.0000"), Map.entry("3900", "C200.0000"));
        assertThat(dimensions(tenant, goLive.response().journalEntryId(), "1080"))
                .contains("\"registerId\": \"T-1\"")
                .contains(LOCATION.toString());

        // AC12: 3900 is not zero at the period end: a warning, never a block.
        AccountingPeriod october = period("2026-10", GO_LIVE, LocalDate.of(2026, 10, 31));
        CloseReadinessResponse warned = asTenant(tenant, () -> readiness.evaluate(october));
        assertThat(warned.checks())
                .filteredOn(c -> c.code() == ReadinessCheckCode.OPENING_BALANCE_EQUITY_NOT_CLEARED)
                .singleElement()
                .satisfies(c -> assertThat(c.severity()).isEqualTo(ReadinessSeverity.WARNING));

        // AC4: once per register.
        int entries = entryCount(tenant);
        assertThatThrownBy(() -> asTenant(tenant, () -> floats.establishGoLive("T-1", goLive("250.00"))))
                .isInstanceOf(CashSetupException.class)
                .extracting(e -> ((CashSetupException) e).getCode())
                .isEqualTo(CashSetupException.Code.FLOAT_ALREADY_ESTABLISHED);
        assertThat(entryCount(tenant)).isEqualTo(entries);

        // AC4: correction = reverse and re-run.
        asTenant(
                tenant,
                () -> journalEntries.reverseJournalEntry(
                        goLive.response().journalEntryId(), "Wrong amount counted", GO_LIVE));
        assertThat(asTenant(
                        tenant,
                        () -> floatRows.findByRegisterId("T-1").orElseThrow().getAmount()))
                .isEqualByComparingTo("0");
        var again = asTenant(tenant, () -> floats.establishGoLive("T-1", goLive("200.00")));
        assertThat(again.response().amount()).isEqualByComparingTo("200.00");

        // AC5: change float against bank account 1000.
        UUID bank = asTenant(
                tenant, () -> glAccounts.findByAccountCode("1000").orElseThrow().getGlAccountId());
        var up = asTenant(tenant, () -> floats.changeFloat("T-1", change("300.00", bank, UUIDv7Generator.generate())));
        assertThat(lines(tenant, up.response().journalEntryId()))
                .containsOnly(Map.entry("1080", "D100.0000"), Map.entry("1000", "C100.0000"));
        UUID requestId = UUIDv7Generator.generate();
        var down = asTenant(tenant, () -> floats.changeFloat("T-1", change("150.00", bank, requestId)));
        assertThat(lines(tenant, down.response().journalEntryId()))
                .containsOnly(Map.entry("1000", "D150.0000"), Map.entry("1080", "C150.0000"));

        // AC6: replay and conflict.
        int beforeReplay = entryCount(tenant);
        var replay = asTenant(tenant, () -> floats.changeFloat("T-1", change("150.00", bank, requestId)));
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.response().journalEntryId()).isEqualTo(down.response().journalEntryId());
        assertThat(entryCount(tenant)).isEqualTo(beforeReplay);
        assertThatThrownBy(() -> asTenant(tenant, () -> floats.changeFloat("T-1", change("175.00", bank, requestId))))
                .extracting(e -> ((CashSetupException) e).getCode())
                .isEqualTo(CashSetupException.Code.IDEMPOTENCY_CONFLICT);

        // Two-tenant isolation (ADR-0062): another tenant sees no float of T-1.
        UUID other = tenant();
        assertThat(asTenant(other, () -> floatRows.findByRegisterId("T-1"))).isEmpty();
    }

    @Test
    @DisplayName("§9.4: a go-live in a CLOSED period is 422 PERIOD_CLOSED even with accounting:period:override")
    void goLiveNeedsAnOpenPeriod() {
        UUID tenant = tenant();
        provisionAccounting(tenant);
        new JdbcTemplate(ownerDataSource())
                .update(
                        "INSERT INTO accounting_period (tenant_id, period_id, period_code, start_date, end_date, status,"
                                + " created_at, created_by, modified_at, modified_by, version) VALUES (?, ?, '2026-08',"
                                + " DATE '2026-08-01', DATE '2026-08-31', 'CLOSED', TIMESTAMPTZ '2026-09-01 00:00:00+00',"
                                + " 't', TIMESTAMPTZ '2026-09-01 00:00:00+00', 't', 0)",
                        tenant,
                        UUIDv7Generator.generate());
        signIn("controller.cfo", "accounting:float:manage", "accounting:period:override");

        RegisterFloatGoLiveRequest closed = new RegisterFloatGoLiveRequest(
                LOCATION,
                new BigDecimal("200.00"),
                LocalDate.of(2026, 8, 15),
                "Counted float in drawer 1 at go-live",
                UUIDv7Generator.generate());
        assertThatThrownBy(() -> asTenant(tenant, () -> floats.establishGoLive("T-1", closed)))
                .isInstanceOf(AccountingPeriodClosedException.class);

        UUID bank = asTenant(
                tenant, () -> glAccounts.findByAccountCode("1000").orElseThrow().getGlAccountId());
        RegisterFloatChangeRequest overridden = new RegisterFloatChangeRequest(
                LOCATION,
                new BigDecimal("100.00"),
                bank,
                LocalDate.of(2026, 8, 15),
                "Float counted in the closed month",
                UUIDv7Generator.generate(),
                "Auditor asked for the August float");
        assertThat(asTenant(tenant, () -> floats.changeFloat("T-1", overridden))
                        .response()
                        .amount())
                .as("Change float takes the standard gate, override included")
                .isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("AC8-AC11: relabel with history, deactivate twice, no Other, a new category, an effective-dated remap")
    void pettyExpenseCategories() {
        UUID tenant = tenant();
        provisionAccounting(tenant);
        signIn(
                "controller.cfo",
                "accounting:mapping-key:create",
                "accounting:mapping-key:edit",
                "accounting:mapping-key:deactivate",
                "accounting:gl-mapping:create");

        // AC8: relabel; the code stays, the history names the caller from the security context.
        PettyExpenseCategoryUpdateRequest relabel = new PettyExpenseCategoryUpdateRequest(
                "Staff meals and coffee", null, null, "Cashiers asked for a clearer label", UUIDv7Generator.generate());
        PettyExpenseCategoryResponse relabelled = asTenant(tenant, () -> categories.update("STAFF_MEALS", relabel));
        assertThat(relabelled.code()).isEqualTo("STAFF_MEALS");
        assertThat(relabelled.label()).isEqualTo("Staff meals and coffee");
        assertThat(relabelled.history())
                .filteredOn(h -> h.changeType() == PettyExpenseCategoryChangeType.RELABEL)
                .singleElement()
                .satisfies(h -> {
                    assertThat(h.actor()).isEqualTo("controller.cfo");
                    assertThat(h.oldValue()).isEqualTo("Staff meals");
                    assertThat(h.newValue()).isEqualTo("Staff meals and coffee");
                });

        // AC9: deactivate; the mapping still resolves; a second deactivation is 409.
        PettyExpenseCategoryResponse inactive = asTenant(
                tenant,
                () -> categories.deactivate(
                        "VEHICLE_FUEL",
                        new PettyExpenseCategoryDeactivateRequest(
                                "We stopped buying fuel in cash", UUIDv7Generator.generate())));
        assertThat(inactive.status()).isEqualTo(PettyExpenseCategoryStatus.INACTIVE);
        assertThat(asTenant(
                        tenant,
                        () -> code(resolver.resolveGLAccount(
                                "REGISTER_CASH_MOVEMENT", "PETTY_EXPENSE_VEHICLE_FUEL", GO_LIVE.atStartOfDay()))))
                .isEqualTo("6250");
        assertThatThrownBy(() -> asTenant(
                        tenant,
                        () -> categories.deactivate(
                                "VEHICLE_FUEL",
                                new PettyExpenseCategoryDeactivateRequest(
                                        "We stopped buying fuel in cash", UUIDv7Generator.generate()))))
                .extracting(e -> ((CashSetupException) e).getCode())
                .isEqualTo(CashSetupException.Code.PETTY_EXPENSE_CATEGORY_INACTIVE);

        // AC10: no Other, no revenue account, and a new category that resolves.
        UUID revenue = asTenant(
                tenant, () -> glAccounts.findByAccountCode("4000").orElseThrow().getGlAccountId());
        UUID misc = asTenant(
                tenant, () -> glAccounts.findByAccountCode("6360").orElseThrow().getGlAccountId());
        assertThatThrownBy(() -> asTenant(tenant, () -> categories.create(create("OTHER", misc))))
                .extracting(e -> ((CashSetupException) e).getCode())
                .isEqualTo(CashSetupException.Code.PETTY_EXPENSE_CATEGORY_NOT_ALLOWED);
        assertThatThrownBy(() -> asTenant(tenant, () -> categories.create(create("TIRE_DISPOSAL", revenue))))
                .extracting(e -> ((CashSetupException) e).getCode())
                .isEqualTo(CashSetupException.Code.PETTY_EXPENSE_ACCOUNT_NOT_ELIGIBLE);
        PettyExpenseCategoryResponse created = asTenant(tenant, () -> categories.create(create("TIRE_DISPOSAL", misc)));
        assertThat(created.currentAccount().accountCode()).isEqualTo("6360");
        LocalDate today = LocalDate.ofInstant(clock.instant(), java.time.ZoneOffset.UTC); // the tenant's zone is UTC
        assertThat(asTenant(
                        tenant,
                        () -> code(resolver.resolveGLAccount(
                                "REGISTER_CASH_MOVEMENT", "PETTY_EXPENSE_TIRE_DISPOSAL", today.atStartOfDay()))))
                .isEqualTo("6360");
        assertThatThrownBy(() -> asTenant(tenant, () -> categories.create(create("TIRE_DISPOSAL", misc))))
                .extracting(e -> ((CashSetupException) e).getCode())
                .isEqualTo(CashSetupException.Code.PETTY_EXPENSE_CATEGORY_EXISTS);

        // AC11: SHOP_SUPPLIES moves from next month; this month still resolves 6340.
        LocalDate nextMonth = today.withDayOfMonth(1).plusMonths(1);
        PettyExpenseCategoryResponse moved = asTenant(
                tenant,
                () -> categories.remap(
                        "SHOP_SUPPLIES",
                        new PettyExpenseCategoryRemapRequest(
                                misc,
                                nextMonth,
                                "Supplies get their own account from next month",
                                UUIDv7Generator.generate())));
        assertThat(moved.currentAccount().accountCode()).isEqualTo("6340");
        assertThat(moved.laterAccount().accountCode()).isEqualTo("6360");
        assertThat(moved.laterAccount().effectiveFrom()).isEqualTo(nextMonth);
        assertThat(asTenant(
                        tenant,
                        () -> code(resolver.resolveGLAccount(
                                "REGISTER_CASH_MOVEMENT",
                                "PETTY_EXPENSE_SHOP_SUPPLIES",
                                nextMonth.minusDays(1).atStartOfDay()))))
                .isEqualTo("6340");
        assertThat(asTenant(
                        tenant,
                        () -> code(resolver.resolveGLAccount(
                                "REGISTER_CASH_MOVEMENT", "PETTY_EXPENSE_SHOP_SUPPLIES", nextMonth.atStartOfDay()))))
                .isEqualTo("6360");
        assertThatThrownBy(() -> asTenant(
                        tenant,
                        () -> categories.remap(
                                "SHOP_SUPPLIES",
                                new PettyExpenseCategoryRemapRequest(
                                        misc,
                                        nextMonth,
                                        "Supplies get their own account from next month",
                                        UUIDv7Generator.generate()))))
                .extracting(e -> ((CashSetupException) e).getCode())
                .isEqualTo(CashSetupException.Code.PETTY_EXPENSE_MAPPING_OVERLAP);

        // Two account changes by the same caller: only GL mappings change, yet the version (and so each fact's)
        // strictly rises (ADR-0044 §3).
        PettyExpenseCategoryResponse movedAgain = asTenant(
                tenant,
                () -> categories.remap(
                        "SHOP_SUPPLIES",
                        new PettyExpenseCategoryRemapRequest(
                                revenueFreeExpense(tenant),
                                nextMonth.plusMonths(1),
                                "Supplies move again the month after",
                                UUIDv7Generator.generate())));
        assertThat(movedAgain.version()).isGreaterThan(moved.version());

        // Never retroactive: an account change dated before today is refused.
        assertThatThrownBy(() -> asTenant(
                        tenant,
                        () -> categories.remap(
                                "OFFICE_SUPPLIES",
                                new PettyExpenseCategoryRemapRequest(
                                        misc,
                                        today.minusDays(1),
                                        "Back-dating the office supplies account",
                                        UUIDv7Generator.generate()))))
                .extracting(e -> ((CashSetupException) e).getCode())
                .isEqualTo(CashSetupException.Code.PETTY_EXPENSE_ACCOUNT_CHANGE_BACKDATED);

        // A replay returns the first result, whatever happened since.
        asTenant(
                tenant,
                () -> categories.update(
                        "STAFF_MEALS",
                        new PettyExpenseCategoryUpdateRequest(
                                "Team meals",
                                null,
                                null,
                                "Renamed again for the menu board",
                                UUIDv7Generator.generate())));
        PettyExpenseCategoryResponse replayed = asTenant(tenant, () -> categories.update("STAFF_MEALS", relabel));
        assertThat(replayed.replayed()).isTrue();
        assertThat(replayed.label()).isEqualTo("Staff meals and coffee");
        assertThat(replayed.version()).isEqualTo(relabelled.version());
        assertThat(replayed.history()).hasSameSizeAs(relabelled.history());

        assertThat(asTenant(tenant, () -> categories.list()).categories())
                .extracting(PettyExpenseCategoryResponse::code)
                .contains("SHOP_SUPPLIES", "TIRE_DISPOSAL", "VEHICLE_FUEL")
                .hasSize(10);
    }

    // ---- helpers --------------------------------------------------------------------------------------------

    private UUID tenant() {
        UUID tenant = tenantWithZone();
        tenants.add(tenant);
        return tenant;
    }

    private UUID revenueFreeExpense(UUID tenant) {
        return asTenant(
                tenant, () -> glAccounts.findByAccountCode("6370").orElseThrow().getGlAccountId());
    }

    private String code(UUID accountId) {
        return glAccounts.findById(accountId).orElseThrow().getAccountCode();
    }

    private static RegisterFloatGoLiveRequest goLive(String amount) {
        return new RegisterFloatGoLiveRequest(
                LOCATION,
                new BigDecimal(amount),
                GO_LIVE,
                "Counted float in drawer 1 at go-live",
                UUIDv7Generator.generate());
    }

    private static RegisterFloatChangeRequest change(String amount, UUID bank, UUID requestId) {
        return new RegisterFloatChangeRequest(
                LOCATION,
                new BigDecimal(amount),
                bank,
                GO_LIVE.plusDays(1),
                "More change for the weekend",
                requestId,
                null);
    }

    private static PettyExpenseCategoryCreateRequest create(String code, UUID account) {
        return new PettyExpenseCategoryCreateRequest(
                code,
                "Tire disposal",
                "Scrap tire hauler fees",
                account,
                "Cashiers pay the scrap hauler",
                UUIDv7Generator.generate());
    }

    private static AccountingPeriod period(String code, LocalDate start, LocalDate end) {
        AccountingPeriod period = new AccountingPeriod();
        period.setPeriodId(UUIDv7Generator.generate());
        period.setPeriodCode(code);
        period.setStartDate(start);
        period.setEndDate(end);
        period.setStatus(AccountingPeriodStatus.OPEN);
        return period;
    }

    /** Account code to "D|C amount" for the entry's lines. */
    private static Map<String, String> lines(UUID tenant, UUID entryId) {
        Map<String, String> lines = new LinkedHashMap<>();
        new JdbcTemplate(ownerDataSource())
                .query(
                        "SELECT g.account_code, l.debit_amount, l.credit_amount FROM journal_entry_line l JOIN gl_account g"
                                + " ON g.tenant_id = l.tenant_id AND g.gl_account_id = l.gl_account_id WHERE l.tenant_id = ?"
                                + " AND l.journal_entry_id = ?",
                        rs -> {
                            BigDecimal debit = rs.getBigDecimal(2);
                            lines.put(
                                    rs.getString(1),
                                    debit != null && debit.signum() > 0
                                            ? "D" + debit.toPlainString()
                                            : "C" + rs.getBigDecimal(3).toPlainString());
                        },
                        tenant,
                        entryId);
        return lines;
    }

    private static String dimensions(UUID tenant, UUID entryId, String accountCode) {
        return new JdbcTemplate(ownerDataSource())
                .queryForObject(
                        "SELECT l.dimensions::text FROM journal_entry_line l JOIN gl_account g ON g.tenant_id = l.tenant_id"
                                + " AND g.gl_account_id = l.gl_account_id WHERE l.tenant_id = ? AND l.journal_entry_id = ?"
                                + " AND g.account_code = ?",
                        String.class,
                        tenant,
                        entryId,
                        accountCode);
    }

    private static int entryCount(UUID tenant) {
        return count(tenant, "journal_entry");
    }

    private static int count(UUID tenant, String table) {
        return new JdbcTemplate(ownerDataSource())
                .queryForObject("SELECT count(*) FROM " + table + " WHERE tenant_id = ?", Integer.class, tenant);
    }

    private static void signIn(String username, String... authorities) {
        UsernamePasswordAuthenticationToken caller = new UsernamePasswordAuthenticationToken(
                username,
                "n/a",
                java.util.Arrays.stream(authorities)
                        .map(SimpleGrantedAuthority::new)
                        .toList());
        caller.setDetails(Map.of(GatewaySecurityConstants.DETAIL_USERNAME, username));
        SecurityContextHolder.getContext().setAuthentication(caller);
    }
}
