package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.entity.AccountingAuditLog;
import com.positivity.accounting.internal.entity.AccountingTemplateEntry;
import com.positivity.accounting.internal.entity.AccountingTemplateState;
import com.positivity.accounting.internal.enums.AccountType;
import com.positivity.accounting.internal.enums.OperationType;
import com.positivity.accounting.internal.enums.StatementType;
import com.positivity.accounting.internal.enums.TemplateEntryOutcome;
import com.positivity.accounting.internal.enums.TemplateEntryReason;
import com.positivity.accounting.internal.repository.AccountingAuditLogRepository;
import com.positivity.accounting.internal.repository.AccountingTemplateEntryRepository;
import com.positivity.accounting.internal.repository.AccountingTemplateStateRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The applier's contract (#2526), against an in-memory tenant chart: what it creates, what it
 * adopts, what it refuses to touch, and that a settled entry is never applied again.
 */
@DisplayName("AccountingTemplateApplier")
class AccountingTemplateApplierTest {

    private static final UUID TENANT = UUID.fromString("01900000-0000-7000-8000-0000000000aa");
    private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");
    private static final LocalDateTime EFFECTIVE = LocalDateTime.of(2020, 1, 1, 0, 0);

    private static final AccountingTemplate.Account AR = account("1200", "Accounts Receivable", AccountType.ASSET);
    private static final AccountingTemplate.Account UNDEPOSITED =
            account("1090", "Undeposited Funds", AccountType.ASSET);
    private static final AccountingTemplate.Account REVENUE = account("4000", "Service Revenue", AccountType.REVENUE);
    private static final AccountingTemplate.Account STAFF_MEALS =
            account("6295", "Staff Meals & Refreshments", AccountType.EXPENSE);
    private static final AccountingTemplate.Category INVOICE_REVENUE =
            new AccountingTemplate.Category("INVOICE_REVENUE", "Invoice revenue recognition");
    private static final AccountingTemplate.Key AR_KEY =
            new AccountingTemplate.Key("INVOICE_REVENUE", "ACCOUNTS_RECEIVABLE", "Debit side");
    private static final AccountingTemplate.Key REVENUE_KEY =
            new AccountingTemplate.Key("INVOICE_REVENUE", "SERVICE_REVENUE", "Credit side");
    private static final AccountingTemplate.GlMapping AR_MAPPING =
            mapping("INVOICE_REVENUE", "ACCOUNTS_RECEIVABLE", "1200");
    private static final AccountingTemplate.GlMapping REVENUE_MAPPING =
            mapping("INVOICE_REVENUE", "SERVICE_REVENUE", "4000");
    private static final AccountingTemplate.DefaultGlMapping CART_DEFAULT =
            new AccountingTemplate.DefaultGlMapping("ORDER_CART_CREATE", "1200", "4000", "default");
    private static final AccountingTemplate.StatementLine REVENUE_LINE = new AccountingTemplate.StatementLine(
            StatementType.INCOME_STATEMENT, "4000", "REVENUE", null, "REVENUE", 1, OperationType.SUM);

    private static final AccountingTemplate.Category CASH_MOVEMENT =
            new AccountingTemplate.Category("REGISTER_CASH_MOVEMENT", "Drawer cash in and out");
    private static final AccountingTemplate.Key STAFF_MEALS_KEY =
            new AccountingTemplate.Key("REGISTER_CASH_MOVEMENT", "PETTY_EXPENSE_STAFF_MEALS", "Staff meals");
    private static final AccountingTemplate.GlMapping STAFF_MEALS_MAPPING =
            mapping("REGISTER_CASH_MOVEMENT", "PETTY_EXPENSE_STAFF_MEALS", "6295");

    private final FakeTenantChart chart = new FakeTenantChart();
    private final Map<String, AccountingTemplateEntry> entries = new LinkedHashMap<>();
    private final AccountingTemplateState state = new AccountingTemplateState();
    private final AccountingTemplateStateLock stateLock = mock(AccountingTemplateStateLock.class);
    private final AccountingTemplateStateRepository states = mock(AccountingTemplateStateRepository.class);
    private final AccountingTemplateEntryRepository entryRecords = mock(AccountingTemplateEntryRepository.class);
    private final AccountingAuditLogRepository auditLogs = mock(AccountingAuditLogRepository.class);

    private AccountingTemplateApplier applier;

    @BeforeEach
    void setUp() {
        when(stateLock.acquire()).thenReturn(state);
        when(entryRecords.findAll()).thenAnswer(invocation -> new ArrayList<>(entries.values()));
        when(entryRecords.save(any())).thenAnswer(invocation -> {
            AccountingTemplateEntry entry = invocation.getArgument(0);
            entries.put(entry.getEntryKey(), entry);
            return entry;
        });
        when(states.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        applier = new AccountingTemplateApplier(
                stateLock, states, entryRecords, auditLogs, chart, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static final AccountingTemplate.PettyExpenseCategory STAFF_MEALS_CATEGORY =
            new AccountingTemplate.PettyExpenseCategory("STAFF_MEALS", "Staff meals", null);

    @Test
    @DisplayName("#2511: a petty-expense category is created after its key and GL mapping")
    void pettyExpenseCategoryComesAfterItsMapping() {
        AccountingTemplateApplier.Result result = applier.apply(
                TENANT,
                AccountingTemplate.of(List.of(
                        STAFF_MEALS_CATEGORY, STAFF_MEALS_MAPPING, STAFF_MEALS_KEY, CASH_MOVEMENT, STAFF_MEALS)));

        assertThat(chart.writes)
                .containsExactly(
                        "create ACCOUNT:6295",
                        "create CATEGORY:REGISTER_CASH_MOVEMENT",
                        "create MAPPING_KEY:REGISTER_CASH_MOVEMENT/PETTY_EXPENSE_STAFF_MEALS",
                        "create GL_MAPPING:REGISTER_CASH_MOVEMENT/PETTY_EXPENSE_STAFF_MEALS",
                        "create PETTY_EXPENSE_CATEGORY:STAFF_MEALS");
        assertThat(result.changes()).containsOnly(Map.entry(TemplateEntryOutcome.CREATED, 5));
        assertThat(chart.pettyExpenseCategories).containsKey("STAFF_MEALS");
    }

    @Test
    @DisplayName("#2511: a category the tenant already holds is adopted, never rewritten")
    void pettyExpenseCategoryIsAdopted() {
        UUID held = UUID.randomUUID();
        chart.pettyExpenseCategories.put("STAFF_MEALS", held);

        applier.apply(
                TENANT,
                AccountingTemplate.of(List.of(
                        STAFF_MEALS_CATEGORY, STAFF_MEALS_MAPPING, STAFF_MEALS_KEY, CASH_MOVEMENT, STAFF_MEALS)));

        assertThat(chart.writes).doesNotContain("create PETTY_EXPENSE_CATEGORY:STAFF_MEALS");
        assertThat(entries.get("PETTY_EXPENSE_CATEGORY:STAFF_MEALS").getOutcome())
                .isEqualTo(TemplateEntryOutcome.ADOPTED);
        assertThat(entries.get("PETTY_EXPENSE_CATEGORY:STAFF_MEALS").getTargetRowId())
                .isEqualTo(held);
    }

    @Test
    @DisplayName("#2511: a category whose GL mapping is withheld is withheld too: it would post nowhere meant")
    void pettyExpenseCategoryWaitsForItsMapping() {
        chart.holdAccount("6295", "Team lunches", AccountType.EXPENSE);

        AccountingTemplateApplier.Result result = applier.apply(
                TENANT,
                AccountingTemplate.of(List.of(
                        STAFF_MEALS_CATEGORY, STAFF_MEALS_MAPPING, STAFF_MEALS_KEY, CASH_MOVEMENT, STAFF_MEALS)));

        assertThat(chart.writes).doesNotContain("create PETTY_EXPENSE_CATEGORY:STAFF_MEALS");
        assertThat(entries.get("PETTY_EXPENSE_CATEGORY:STAFF_MEALS").getOutcome())
                .isEqualTo(TemplateEntryOutcome.WITHHELD);
        assertThat(result.attention()).contains("PETTY_EXPENSE_CATEGORY:STAFF_MEALS");
    }

    @Test
    @DisplayName("an empty tenant receives every entry, in dependency order whatever order the template lists them in")
    void createsEverythingInDependencyOrder() {
        List<AccountingTemplate.Entry> shuffled = new ArrayList<>(List.of(
                REVENUE_LINE,
                CART_DEFAULT,
                REVENUE_MAPPING,
                AR_MAPPING,
                REVENUE_KEY,
                AR_KEY,
                INVOICE_REVENUE,
                REVENUE,
                AR));
        Collections.reverse(shuffled);
        Collections.swap(shuffled, 0, 8);

        AccountingTemplateApplier.Result result = applier.apply(TENANT, AccountingTemplate.of(shuffled));

        assertThat(chart.writes)
                .containsExactly(
                        "create ACCOUNT:1200",
                        "create ACCOUNT:4000",
                        "create CATEGORY:INVOICE_REVENUE",
                        "create MAPPING_KEY:INVOICE_REVENUE/ACCOUNTS_RECEIVABLE",
                        "create MAPPING_KEY:INVOICE_REVENUE/SERVICE_REVENUE",
                        "create GL_MAPPING:INVOICE_REVENUE/ACCOUNTS_RECEIVABLE",
                        "create GL_MAPPING:INVOICE_REVENUE/SERVICE_REVENUE",
                        "create DEFAULT_GL_MAPPING:ORDER_CART_CREATE",
                        "create STATEMENT_LINE:INCOME_STATEMENT:4000");
        assertThat(result.changed()).isTrue();
        assertThat(result.changes()).containsOnly(Map.entry(TemplateEntryOutcome.CREATED, 9));
        assertThat(result.needsAttention()).isFalse();
        assertThat(entries.values()).allSatisfy(entry -> {
            assertThat(entry.getOutcome()).isEqualTo(TemplateEntryOutcome.CREATED);
            assertThat(entry.getTargetRowId()).isNotNull();
            assertThat(entry.getReason()).isNull();
        });
        assertThat(chart.accountCodeOfMapping("INVOICE_REVENUE", "SERVICE_REVENUE"))
                .isEqualTo("4000");
        assertThat(state.getTemplateFingerprint())
                .isEqualTo(AccountingTemplate.of(shuffled).fingerprint());
        assertThat(state.getLastAppliedAt()).isEqualTo(NOW);
        assertThat(state.getCreatedCount()).isEqualTo(9);
    }

    @Test
    @DisplayName("a run that changed something writes one audit row as tenant-template, with fingerprint and counts")
    void auditsARunThatChangedSomething() {
        AccountingTemplate template = AccountingTemplate.of(List.of(AR, REVENUE));

        applier.apply(TENANT, template);

        ArgumentCaptor<AccountingAuditLog> audit = ArgumentCaptor.forClass(AccountingAuditLog.class);
        verify(auditLogs, times(1)).save(audit.capture());
        assertThat(audit.getValue().getOperation()).isEqualTo("TENANT_TEMPLATE_APPLY");
        assertThat(audit.getValue().getUserId()).isEqualTo("tenant-template");
        assertThat(audit.getValue().getNewValue())
                .contains(template.fingerprint())
                .contains("CREATED=2");
    }

    @Test
    @DisplayName("what the tenant already holds is adopted and nothing is written to it")
    void adoptsWhatTheTenantHolds() {
        chart.holdAccount("1200", "  accounts RECEIVABLE ", AccountType.ASSET);
        chart.holdAccount("4000", "Service Revenue", AccountType.REVENUE);
        chart.holdAccount("4100", "Parts Revenue", AccountType.REVENUE);
        chart.holdMapping("INVOICE_REVENUE", "ACCOUNTS_RECEIVABLE", "1200");
        // The tenant remapped the revenue key to its own account: adopted whatever account it names.
        chart.holdMapping("INVOICE_REVENUE", "SERVICE_REVENUE", "4100");
        chart.holdLine(new AccountingTemplate.StatementLine(
                StatementType.INCOME_STATEMENT, "4000", "SALES", null, "Our sales", 7, OperationType.SUM));

        AccountingTemplateApplier.Result result = applier.apply(
                TENANT,
                AccountingTemplate.of(List.of(
                        AR, REVENUE, INVOICE_REVENUE, AR_KEY, REVENUE_KEY, AR_MAPPING, REVENUE_MAPPING, REVENUE_LINE)));

        assertThat(chart.writes).isEmpty();
        assertThat(result.changes()).containsOnly(Map.entry(TemplateEntryOutcome.ADOPTED, 8));
        assertThat(chart.accountCodeOfMapping("INVOICE_REVENUE", "SERVICE_REVENUE"))
                .isEqualTo("4100");
        assertThat(chart.lineOf(StatementType.INCOME_STATEMENT, "4000").lineCode())
                .isEqualTo("SALES");
        assertThat(entries.get("ACCOUNT:1200").getTargetRowId())
                .isEqualTo(chart.accounts.get("1200").id());
    }

    @Test
    @DisplayName(
            "an account under the template's code with another name is a CONFLICT and what depends on it is WITHHELD")
    void conflictingAccountWithholdsItsDependants() {
        UUID tireDisposal = chart.holdAccount("6295", "Tire disposal", AccountType.EXPENSE);
        AccountingTemplate.StatementLine line = new AccountingTemplate.StatementLine(
                StatementType.LABOR_OVERHEAD, "6295", "2.4.9", "2.4", "Staff meals", 12, OperationType.SUM);

        AccountingTemplateApplier.Result result = applier.apply(
                TENANT,
                AccountingTemplate.of(List.of(STAFF_MEALS, CASH_MOVEMENT, STAFF_MEALS_KEY, STAFF_MEALS_MAPPING, line)));

        assertThat(chart.accounts.get("6295"))
                .isEqualTo(
                        new TenantChart.AccountRow(tireDisposal, "6295", "Tire disposal", AccountType.EXPENSE, true));
        assertThat(chart.writes)
                .containsExactly(
                        "create CATEGORY:REGISTER_CASH_MOVEMENT",
                        "create MAPPING_KEY:REGISTER_CASH_MOVEMENT/PETTY_EXPENSE_STAFF_MEALS");
        AccountingTemplateEntry account = entries.get("ACCOUNT:6295");
        assertThat(account.getOutcome()).isEqualTo(TemplateEntryOutcome.CONFLICT);
        assertThat(account.getReason()).isEqualTo(TemplateEntryReason.ACCOUNT_DIFFERS);
        assertThat(account.getTemplateValue()).isEqualTo("6295 Staff Meals & Refreshments, expense");
        assertThat(account.getTenantValue()).isEqualTo("6295 Tire disposal, expense");
        AccountingTemplateEntry mapping = entries.get("GL_MAPPING:REGISTER_CASH_MOVEMENT/PETTY_EXPENSE_STAFF_MEALS");
        assertThat(mapping.getOutcome()).isEqualTo(TemplateEntryOutcome.WITHHELD);
        assertThat(mapping.getReason()).isEqualTo(TemplateEntryReason.DEPENDS_ON_CONFLICT);
        assertThat(mapping.getTargetRowId()).isNull();
        assertThat(entries.get("STATEMENT_LINE:LABOR_OVERHEAD:6295").getOutcome())
                .isEqualTo(TemplateEntryOutcome.WITHHELD);
        assertThat(result.needsAttention()).isTrue();
        assertThat(result.attention())
                .containsExactly(
                        "ACCOUNT:6295",
                        "GL_MAPPING:REGISTER_CASH_MOVEMENT/PETTY_EXPENSE_STAFF_MEALS",
                        "STATEMENT_LINE:LABOR_OVERHEAD:6295");
        assertThat(state.getConflictCount()).isEqualTo(1);
        assertThat(state.getWithheldCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("an account of another type is a CONFLICT; a matching but inactive one is ACCOUNT_INACTIVE")
    void accountOfAnotherTypeOrInactiveIsAConflict() {
        chart.holdAccount("6295", "Staff Meals & Refreshments", AccountType.LIABILITY);
        chart.holdAccount("4000", "Service Revenue", AccountType.REVENUE, false);

        applier.apply(TENANT, AccountingTemplate.of(List.of(STAFF_MEALS, REVENUE)));

        assertThat(entries.get("ACCOUNT:6295").getReason()).isEqualTo(TemplateEntryReason.ACCOUNT_DIFFERS);
        assertThat(entries.get("ACCOUNT:6295").getTenantValue())
                .isEqualTo("6295 Staff Meals & Refreshments, liability");
        assertThat(entries.get("ACCOUNT:4000").getOutcome()).isEqualTo(TemplateEntryOutcome.CONFLICT);
        assertThat(entries.get("ACCOUNT:4000").getReason()).isEqualTo(TemplateEntryReason.ACCOUNT_INACTIVE);
        assertThat(chart.writes).isEmpty();
    }

    @Test
    @DisplayName("an entry that refers to an account the tenant does not hold is WITHHELD as ACCOUNT_MISSING")
    void missingAccountWithholds() {
        // The template maps to 4000 but this tenant's template does not carry that account.
        applier.apply(TENANT, AccountingTemplate.of(List.of(INVOICE_REVENUE, REVENUE_KEY, REVENUE_MAPPING)));

        AccountingTemplateEntry mapping = entries.get("GL_MAPPING:INVOICE_REVENUE/SERVICE_REVENUE");
        assertThat(mapping.getOutcome()).isEqualTo(TemplateEntryOutcome.WITHHELD);
        assertThat(mapping.getReason()).isEqualTo(TemplateEntryReason.ACCOUNT_MISSING);
        assertThat(chart.mappings).isEmpty();
    }

    @Test
    @DisplayName("a conflict heals once the tenant renumbers its account: the account and its dependants are created")
    void conflictHealsWhenTheTenantResolvesIt() {
        chart.holdAccount("6295", "Tire disposal", AccountType.EXPENSE);
        AccountingTemplate template =
                AccountingTemplate.of(List.of(STAFF_MEALS, CASH_MOVEMENT, STAFF_MEALS_KEY, STAFF_MEALS_MAPPING));
        applier.apply(TENANT, template);
        chart.writes.clear();

        chart.renumberAccount("6295", "6290");
        AccountingTemplateApplier.Result healed = applier.apply(TENANT, template);

        assertThat(chart.writes)
                .containsExactly(
                        "create ACCOUNT:6295", "create GL_MAPPING:REGISTER_CASH_MOVEMENT/PETTY_EXPENSE_STAFF_MEALS");
        assertThat(chart.accounts.get("6290").name()).isEqualTo("Tire disposal");
        assertThat(chart.accounts.get("6295").name()).isEqualTo("Staff Meals & Refreshments");
        assertThat(chart.accountCodeOfMapping("REGISTER_CASH_MOVEMENT", "PETTY_EXPENSE_STAFF_MEALS"))
                .isEqualTo("6295");
        assertThat(entries.get("ACCOUNT:6295").getOutcome()).isEqualTo(TemplateEntryOutcome.CREATED);
        assertThat(entries.get("ACCOUNT:6295").getReason()).isNull();
        assertThat(entries.get("ACCOUNT:6295").getTenantValue()).isNull();
        assertThat(healed.needsAttention()).isFalse();
        assertThat(healed.changes()).containsOnly(Map.entry(TemplateEntryOutcome.CREATED, 2));
    }

    @Test
    @DisplayName("an open entry that is still open is looked at again but nothing is written")
    void unresolvedConflictWritesNothingOnTheNextRun() {
        chart.holdAccount("6295", "Tire disposal", AccountType.EXPENSE);
        AccountingTemplate template = AccountingTemplate.of(List.of(STAFF_MEALS, CASH_MOVEMENT));
        applier.apply(TENANT, template);
        chart.writes.clear();
        int lookupsBefore = chart.lookups;

        AccountingTemplateApplier.Result again = applier.apply(TENANT, template);

        assertThat(chart.lookups).as("the conflict was re-evaluated").isGreaterThan(lookupsBefore);
        assertThat(again.changed()).isFalse();
        assertThat(again.attention()).containsExactly("ACCOUNT:6295");
        assertThat(chart.writes).isEmpty();
        verify(entryRecords, times(2)).save(any());
        verify(states, times(1)).save(any());
        verify(auditLogs, times(1)).save(any());
    }

    @Test
    @DisplayName("a settled entry is never applied again: template changes do not reach it and tenant changes stay")
    void settledEntriesAreNeverReapplied() {
        applier.apply(
                TENANT,
                AccountingTemplate.of(List.of(UNDEPOSITED, REVENUE, INVOICE_REVENUE, REVENUE_KEY, REVENUE_MAPPING)));
        chart.writes.clear();

        // The tenant renames an account it received, and deletes a mapping it received.
        chart.renameAccount("1090", "Till float");
        chart.mappings.clear();
        // The template renames the same account, and gains one.
        AccountingTemplate.Account renamedInTemplate = account("1090", "Undeposited Receipts", AccountType.ASSET);
        AccountingTemplateApplier.Result result = applier.apply(
                TENANT,
                AccountingTemplate.of(List.of(
                        renamedInTemplate, REVENUE, INVOICE_REVENUE, REVENUE_KEY, REVENUE_MAPPING, STAFF_MEALS)));

        assertThat(chart.writes).containsExactly("create ACCOUNT:6295");
        assertThat(chart.accounts.get("1090").name()).isEqualTo("Till float");
        assertThat(chart.mappings)
                .as("a row the tenant deleted is not created again")
                .isEmpty();
        assertThat(entries.get("ACCOUNT:1090").getOutcome()).isEqualTo(TemplateEntryOutcome.CREATED);
        assertThat(entries.get("ACCOUNT:1090").getEntryFingerprint()).isEqualTo(UNDEPOSITED.fingerprint());
        assertThat(result.changes()).containsOnly(Map.entry(TemplateEntryOutcome.CREATED, 1));
        assertThat(result.needsAttention()).isFalse();
    }

    @Test
    @DisplayName(
            "an untouched statement line takes the template's new code, description and order; a touched one stays")
    void refreshesOnlyAnUntouchedStatementLine() {
        AccountingTemplate.Account parts = account("4100", "Parts Revenue", AccountType.REVENUE);
        AccountingTemplate.StatementLine partsLine = new AccountingTemplate.StatementLine(
                StatementType.INCOME_STATEMENT, "4100", "REVENUE", null, "REVENUE", 2, OperationType.SUM);
        applier.apply(TENANT, AccountingTemplate.of(List.of(REVENUE, parts, REVENUE_LINE, partsLine)));
        chart.writes.clear();

        // The tenant relabels the parts line; the service-revenue line is left as it was written.
        UUID partsLineId = chart.lineIdOf(StatementType.INCOME_STATEMENT, "4100");
        chart.lines.put(
                partsLineId,
                new AccountingTemplate.StatementLine(
                        StatementType.INCOME_STATEMENT,
                        "4100",
                        "REVENUE",
                        null,
                        "Parts counter",
                        2,
                        OperationType.SUM));
        // S35: the template recodes both lines from REVENUE to IS_SALES.
        AccountingTemplate.StatementLine recoded = new AccountingTemplate.StatementLine(
                StatementType.INCOME_STATEMENT, "4000", "IS_SALES", "IS", "Sales", 10, OperationType.SUM);
        AccountingTemplate.StatementLine partsRecoded = new AccountingTemplate.StatementLine(
                StatementType.INCOME_STATEMENT, "4100", "IS_SALES", "IS", "Sales", 11, OperationType.SUM);

        AccountingTemplateApplier.Result result =
                applier.apply(TENANT, AccountingTemplate.of(List.of(REVENUE, parts, recoded, partsRecoded)));

        assertThat(chart.writes).containsExactly("refresh STATEMENT_LINE:INCOME_STATEMENT:4000");
        assertThat(chart.lineOf(StatementType.INCOME_STATEMENT, "4000")).isEqualTo(recoded);
        assertThat(chart.lineOf(StatementType.INCOME_STATEMENT, "4100").lineDescription())
                .isEqualTo("Parts counter");
        assertThat(chart.lineOf(StatementType.INCOME_STATEMENT, "4100").lineCode())
                .isEqualTo("REVENUE");
        assertThat(chart.lines).as("still one line per account per statement").hasSize(2);
        assertThat(entries.get("STATEMENT_LINE:INCOME_STATEMENT:4000").getOutcome())
                .isEqualTo(TemplateEntryOutcome.REFRESHED);
        assertThat(entries.get("STATEMENT_LINE:INCOME_STATEMENT:4000").getEntryFingerprint())
                .isEqualTo(recoded.fingerprint());
        assertThat(entries.get("STATEMENT_LINE:INCOME_STATEMENT:4100").getOutcome())
                .isEqualTo(TemplateEntryOutcome.CREATED);
        assertThat(result.changes()).containsOnly(Map.entry(TemplateEntryOutcome.REFRESHED, 1));
    }

    @Test
    @DisplayName("an adopted statement line that differed from the template is the tenant's and is not refreshed")
    void adoptedLineThatDifferedIsNotRefreshed() {
        chart.holdAccount("4000", "Service Revenue", AccountType.REVENUE);
        chart.holdLine(new AccountingTemplate.StatementLine(
                StatementType.INCOME_STATEMENT, "4000", "SALES", null, "Our sales", 7, OperationType.SUM));
        applier.apply(TENANT, AccountingTemplate.of(List.of(REVENUE, REVENUE_LINE)));
        AccountingTemplate.StatementLine recoded = new AccountingTemplate.StatementLine(
                StatementType.INCOME_STATEMENT, "4000", "IS_SALES", null, "Sales", 10, OperationType.SUM);

        applier.apply(TENANT, AccountingTemplate.of(List.of(REVENUE, recoded)));

        assertThat(chart.writes).isEmpty();
        assertThat(chart.lineOf(StatementType.INCOME_STATEMENT, "4000").lineCode())
                .isEqualTo("SALES");
        assertThat(entries.get("STATEMENT_LINE:INCOME_STATEMENT:4000").getOutcome())
                .isEqualTo(TemplateEntryOutcome.ADOPTED);
    }

    @Test
    @DisplayName(
            "a tenant holding only a location override of a line gets the global line created; the override is untouched")
    void locationOverrideIsNotTheTemplatesLine() {
        chart.holdAccount("4000", "Service Revenue", AccountType.REVENUE);
        AccountingTemplate.StatementLine override = new AccountingTemplate.StatementLine(
                StatementType.INCOME_STATEMENT, "4000", "TULSA_SALES", null, "Tulsa sales", 3, OperationType.SUM);
        UUID overrideId = chart.holdLocationOverride(override, "TULSA");

        applier.apply(TENANT, AccountingTemplate.of(List.of(REVENUE, REVENUE_LINE)));

        assertThat(chart.writes).containsExactly("create STATEMENT_LINE:INCOME_STATEMENT:4000");
        assertThat(chart.lines).hasSize(2);
        assertThat(chart.lines.get(overrideId)).isEqualTo(override);
        assertThat(entries.get("STATEMENT_LINE:INCOME_STATEMENT:4000").getTargetRowId())
                .isNotEqualTo(overrideId);
    }

    @Test
    @DisplayName("an unchanged template with nothing open stops at the fingerprint: no look-up, no write")
    void shortCircuitsWhenUpToDate() {
        AccountingTemplate template = AccountingTemplate.of(List.of(AR, REVENUE, INVOICE_REVENUE, AR_KEY, AR_MAPPING));
        applier.apply(TENANT, template);
        chart.writes.clear();
        int lookupsBefore = chart.lookups;

        AccountingTemplateApplier.Result again = applier.apply(TENANT, template);

        assertThat(again.changed()).isFalse();
        assertThat(again.changes()).isEmpty();
        assertThat(again.totals()).containsOnly(Map.entry(TemplateEntryOutcome.CREATED, 5));
        assertThat(chart.lookups).isEqualTo(lookupsBefore);
        assertThat(chart.writes).isEmpty();
        verify(entryRecords, times(5)).save(any());
        verify(states, times(1)).save(any());
        verify(auditLogs, times(1)).save(any());
    }

    @Test
    @DisplayName("an entry removed from the template leaves the tenant's row alone")
    void removedEntriesLeaveTenantRowsAlone() {
        applier.apply(TENANT, AccountingTemplate.of(List.of(AR, REVENUE)));
        chart.writes.clear();

        applier.apply(TENANT, AccountingTemplate.of(List.of(AR)));

        assertThat(chart.accounts).containsKeys("1200", "4000");
        assertThat(chart.writes).isEmpty();
        assertThat(entries).containsKey("ACCOUNT:4000");
    }

    private static AccountingTemplate.Account account(String code, String name, AccountType type) {
        return new AccountingTemplate.Account(code, name, type, null, false, null, EFFECTIVE);
    }

    private static AccountingTemplate.GlMapping mapping(String category, String key, String accountCode) {
        return new AccountingTemplate.GlMapping(
                category, key, "ACCOUNTING", category + "_" + key, accountCode, EFFECTIVE, null);
    }
}
