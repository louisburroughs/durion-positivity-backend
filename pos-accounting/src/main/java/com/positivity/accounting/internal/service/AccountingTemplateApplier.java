package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.entity.AccountingAuditLog;
import com.positivity.accounting.internal.entity.AccountingTemplateEntry;
import com.positivity.accounting.internal.entity.AccountingTemplateState;
import com.positivity.accounting.internal.enums.TemplateEntryOutcome;
import com.positivity.accounting.internal.enums.TemplateEntryReason;
import com.positivity.accounting.internal.repository.AccountingAuditLogRepository;
import com.positivity.accounting.internal.repository.AccountingTemplateEntryRepository;
import com.positivity.accounting.internal.repository.AccountingTemplateStateRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Brings one tenant up to the accounting template (#2526; ADR-0062 §6-§7): add-only, idempotent,
 * conflict-aware, and recorded entry by entry.
 *
 * <h2>The contract: never overwrite</h2>
 *
 * A tenant owns what it receives. The applier creates a row the tenant lacks and otherwise leaves
 * the tenant's rows exactly as they are: it never renames, retypes, repoints, reactivates or
 * deletes one, and it writes no journal entry. What it did with each entry is kept in
 * {@code accounting_template_entry}, and that record is what makes the rule hold over time:
 *
 * <ul>
 *   <li>An entry recorded {@code CREATED}, {@code ADOPTED} or {@code REFRESHED} is <em>settled</em>
 *       and never applied again. A later change to the template does not reach it, and a tenant
 *       that renamed the account, deactivated the key, remapped it or deleted the row keeps its
 *       decision. Without the record, a deleted row would simply be created again on the next run.
 *   <li>An entry recorded {@code CONFLICT} or {@code WITHHELD} is <em>open</em> and looked at again
 *       on every run, so it settles once the tenant has resolved the clash.
 *   <li>An entry that has left the template is not looked at; nothing is removed from a tenant.
 * </ul>
 *
 * The one exception is presentation: a statement line still exactly as the template last wrote or
 * found it takes the template's new line code, description and display order ({@code REFRESHED}).
 * A line the tenant changed in any way is the tenant's and stays.
 *
 * <h2>Adoption</h2>
 *
 * A tenant may already hold what the template would create (the alpha default tenant holds all of
 * it, from the seeds that preceded this). A category, key, mapping, default mapping or statement
 * line that exists is adopted as it is. An account is adopted only when it is the same account:
 * same type, same name (trimmed, case-insensitive) and active. An account under the template's code
 * that is something else is a {@code CONFLICT}, and everything that would post or report through it
 * is {@code WITHHELD}: nothing reaches an account whose name or type differs from the one the
 * template meant.
 *
 * <h2>Transaction and locking</h2>
 *
 * {@link #apply} joins the caller's transaction, which must have been opened inside the tenant's
 * binding. It holds the tenant's state row for the length of the run
 * ({@link AccountingTemplateStateLock}), so concurrent runs for one tenant queue up and the later
 * ones find nothing left to do.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountingTemplateApplier {

    /** {@code AccountingAuditLog.operation} of a run that changed something. */
    public static final String AUDIT_OPERATION = "TENANT_TEMPLATE_APPLY";

    /** {@code AccountingAuditLog.entityType}; the entity id is the tenant's state row. */
    public static final String AUDIT_ENTITY_TYPE = "TENANT_TEMPLATE";

    private final AccountingTemplateStateLock stateLock;
    private final AccountingTemplateStateRepository states;
    private final AccountingTemplateEntryRepository entryRecords;
    private final AccountingAuditLogRepository auditLogs;
    private final TenantChart chart;
    private final Clock clock;

    /**
     * Applies {@code template} to the tenant bound to this thread.
     *
     * @param tenantId the bound tenant, for the logs
     * @param template the entries this tenant is to hold: the sources that apply to it, no others
     * @return what the run did
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public @NonNull Result apply(@NonNull UUID tenantId, @NonNull AccountingTemplate template) {
        AccountingTemplateState state = stateLock.acquire();

        Map<String, AccountingTemplateEntry> recorded = new HashMap<>();
        for (AccountingTemplateEntry entry : entryRecords.findAll()) {
            recorded.put(entry.getEntryKey(), entry);
        }
        boolean anythingOpen =
                recorded.values().stream().anyMatch(entry -> entry.getOutcome().needsAttention());
        if (template.fingerprint().equals(state.getTemplateFingerprint()) && !anythingOpen) {
            return Result.upToDate(counts(recorded));
        }

        Run run = new Run(recorded);
        for (AccountingTemplate.Entry entry : template.entries()) {
            run.visit(entry);
        }

        Map<TemplateEntryOutcome, Integer> totals = counts(recorded);
        boolean fingerprintMoved = !template.fingerprint().equals(state.getTemplateFingerprint());
        if (run.changes.isEmpty() && !fingerprintMoved) {
            // Open entries were looked at again and are still open: a second application writes nothing.
            return new Result(false, Map.of(), totals, run.attention);
        }

        state.setTemplateFingerprint(template.fingerprint());
        // Microseconds: what the column keeps, so the row reads back equal to what was written.
        state.setLastAppliedAt(Instant.now(clock).truncatedTo(ChronoUnit.MICROS));
        state.setCreatedCount(totals.getOrDefault(TemplateEntryOutcome.CREATED, 0));
        state.setAdoptedCount(totals.getOrDefault(TemplateEntryOutcome.ADOPTED, 0));
        state.setRefreshedCount(totals.getOrDefault(TemplateEntryOutcome.REFRESHED, 0));
        state.setConflictCount(totals.getOrDefault(TemplateEntryOutcome.CONFLICT, 0));
        state.setWithheldCount(totals.getOrDefault(TemplateEntryOutcome.WITHHELD, 0));
        states.save(state);

        if (!run.changes.isEmpty()) {
            AccountingAuditLog audit = new AccountingAuditLog();
            audit.setEntityType(AUDIT_ENTITY_TYPE);
            audit.setEntityId(state.getStateId());
            audit.setOperation(AUDIT_OPERATION);
            audit.setUserId(TenantChart.ACTOR);
            audit.setNewValue(
                    "fingerprint=" + template.fingerprint() + " changes=" + run.changes + " totals=" + totals);
            auditLogs.save(audit);
        }
        for (String key : run.attention) {
            log.warn("Accounting template entry {} needs attention in tenant {}", key, tenantId);
        }
        return new Result(true, Map.copyOf(run.changes), totals, List.copyOf(run.attention));
    }

    private static Map<TemplateEntryOutcome, Integer> counts(Map<String, AccountingTemplateEntry> recorded) {
        Map<TemplateEntryOutcome, Integer> counts = new EnumMap<>(TemplateEntryOutcome.class);
        for (AccountingTemplateEntry entry : recorded.values()) {
            counts.merge(entry.getOutcome(), 1, Integer::sum);
        }
        return counts;
    }

    /**
     * What one {@link #apply} did.
     *
     * @param changed false when the run wrote nothing at all
     * @param changes how many entries moved to each outcome in this run
     * @param totals how many recorded entries the tenant has in each outcome after the run
     * @param attention the entry keys in {@code CONFLICT} or {@code WITHHELD} after the run; empty
     *     when the run stopped early because the tenant was already up to date
     */
    public record Result(
            boolean changed,
            @NonNull Map<TemplateEntryOutcome, Integer> changes,
            @NonNull Map<TemplateEntryOutcome, Integer> totals,
            @NonNull List<String> attention) {

        static Result upToDate(Map<TemplateEntryOutcome, Integer> totals) {
            return new Result(false, Map.of(), totals, List.of());
        }

        /** True when any entry waits for the tenant to resolve a clash. */
        public boolean needsAttention() {
            return totals.getOrDefault(TemplateEntryOutcome.CONFLICT, 0) > 0
                    || totals.getOrDefault(TemplateEntryOutcome.WITHHELD, 0) > 0;
        }
    }

    /** What the applier decided for one entry. */
    private record Verdict(
            TemplateEntryOutcome outcome,
            @Nullable TemplateEntryReason reason,
            @Nullable UUID targetRowId,
            @Nullable String tenantValue) {

        static Verdict created(UUID id) {
            return new Verdict(TemplateEntryOutcome.CREATED, null, id, null);
        }

        static Verdict adopted(UUID id) {
            return new Verdict(TemplateEntryOutcome.ADOPTED, null, id, null);
        }

        static Verdict conflict(TemplateEntryReason reason, UUID id, String tenantValue) {
            return new Verdict(TemplateEntryOutcome.CONFLICT, reason, id, tenantValue);
        }

        static Verdict withheld(TemplateEntryReason reason, String tenantValue) {
            return new Verdict(TemplateEntryOutcome.WITHHELD, reason, null, tenantValue);
        }
    }

    /** An account an entry refers to: usable, or the reason the entry must be withheld. */
    private record AccountRef(@Nullable UUID id, @Nullable Verdict withheld) {}

    /** One walk over the template for one tenant. */
    private final class Run {

        private final Map<String, AccountingTemplateEntry> recorded;
        private final Map<TemplateEntryOutcome, Integer> changes = new EnumMap<>(TemplateEntryOutcome.class);
        private final List<String> attention = new ArrayList<>();

        Run(Map<String, AccountingTemplateEntry> recorded) {
            this.recorded = recorded;
        }

        void visit(AccountingTemplate.Entry entry) {
            AccountingTemplateEntry record = recorded.get(entry.entryKey());
            if (record != null && !record.getOutcome().needsAttention()) {
                // Settled: never applied again. Only an untouched statement line follows the template.
                if (entry instanceof AccountingTemplate.StatementLine line) {
                    refreshIfUntouched(line, record);
                }
                return;
            }
            Verdict verdict = evaluate(entry);
            if (verdict.outcome().needsAttention()) {
                attention.add(entry.entryKey());
            }
            record(entry, record, verdict);
        }

        private Verdict evaluate(AccountingTemplate.Entry entry) {
            return switch (entry) {
                case AccountingTemplate.Account account -> evaluate(account);
                case AccountingTemplate.Category category ->
                    chart.findCategory(category.name())
                            .map(Verdict::adopted)
                            .orElseGet(() -> Verdict.created(chart.createCategory(category)));
                case AccountingTemplate.Key key -> evaluate(key);
                case AccountingTemplate.GlMapping mapping -> evaluate(mapping);
                case AccountingTemplate.DefaultGlMapping mapping -> evaluate(mapping);
                case AccountingTemplate.StatementLine line -> evaluate(line);
                case AccountingTemplate.PettyExpenseCategory category -> evaluate(category);
            };
        }

        private Verdict evaluate(AccountingTemplate.Account account) {
            Optional<TenantChart.AccountRow> existing = chart.findAccount(account.code());
            if (existing.isEmpty()) {
                return Verdict.created(chart.createAccount(account));
            }
            TenantChart.AccountRow row = existing.get();
            boolean sameAccount = row.type() == account.type()
                    && row.name().trim().equalsIgnoreCase(account.name().trim());
            if (!sameAccount) {
                return Verdict.conflict(TemplateEntryReason.ACCOUNT_DIFFERS, row.id(), row.describe());
            }
            if (!row.active()) {
                return Verdict.conflict(TemplateEntryReason.ACCOUNT_INACTIVE, row.id(), row.describe());
            }
            return Verdict.adopted(row.id());
        }

        private Verdict evaluate(AccountingTemplate.Key key) {
            UUID categoryId = chart.findCategory(key.category()).orElseThrow(() -> danglingReference(key));
            return chart.findMappingKey(categoryId, key.keyName())
                    .map(Verdict::adopted)
                    .orElseGet(() -> Verdict.created(chart.createMappingKey(categoryId, key)));
        }

        private Verdict evaluate(AccountingTemplate.GlMapping mapping) {
            UUID categoryId = chart.findCategory(mapping.category()).orElseThrow(() -> danglingReference(mapping));
            UUID keyId =
                    chart.findMappingKey(categoryId, mapping.keyName()).orElseThrow(() -> danglingReference(mapping));
            Optional<UUID> existing = chart.findUndimensionedGlMapping(keyId);
            if (existing.isPresent()) {
                // Adopted whatever account it names: a tenant that remapped the key keeps its remap.
                return Verdict.adopted(existing.get());
            }
            AccountRef account = account(mapping.accountCode());
            if (account.withheld() != null) {
                return account.withheld();
            }
            return Verdict.created(chart.createGlMapping(categoryId, keyId, account.id(), mapping));
        }

        private Verdict evaluate(AccountingTemplate.DefaultGlMapping mapping) {
            Optional<UUID> existing = chart.findDefaultGlMapping(mapping.eventType());
            if (existing.isPresent()) {
                return Verdict.adopted(existing.get());
            }
            AccountRef debit = account(mapping.debitAccountCode());
            if (debit.withheld() != null) {
                return debit.withheld();
            }
            AccountRef credit = account(mapping.creditAccountCode());
            if (credit.withheld() != null) {
                return credit.withheld();
            }
            return Verdict.created(chart.createDefaultGlMapping(mapping, debit.id(), credit.id()));
        }

        private Verdict evaluate(AccountingTemplate.StatementLine line) {
            AccountRef account = account(line.accountCode());
            if (account.withheld() != null) {
                return account.withheld();
            }
            return chart.findStatementLine(line.statementType(), account.id())
                    .map(existing -> Verdict.adopted(existing.id()))
                    .orElseGet(() -> Verdict.created(chart.createStatementLine(line, account.id())));
        }

        private Verdict evaluate(AccountingTemplate.PettyExpenseCategory category) {
            Optional<UUID> existing = chart.findPettyExpenseCategory(category.code());
            if (existing.isPresent()) {
                return Verdict.adopted(existing.get());
            }
            UUID categoryId = chart.findCategory(AccountingTemplate.PettyExpenseCategory.POSTING_CATEGORY)
                    .orElseThrow(() -> danglingReference(category));
            UUID keyId = chart.findMappingKey(
                            categoryId, AccountingTemplate.PettyExpenseCategory.keyNameOf(category.code()))
                    .orElseThrow(() -> danglingReference(category));
            AccountingTemplateEntry mappingRecord = recorded.get(category.glMappingEntryKey());
            if (mappingRecord != null && mappingRecord.getOutcome().needsAttention()) {
                // The cashier would pick a category that posts nowhere the template meant.
                return Verdict.withheld(TemplateEntryReason.DEPENDS_ON_CONFLICT, mappingRecord.getTenantValue());
            }
            return Verdict.created(chart.createPettyExpenseCategory(keyId, category));
        }

        /**
         * The tenant account an entry refers to by code. An account whose own template entry is in
         * conflict is not the account the template meant, so nothing is attached to it even though
         * a row under that code exists.
         */
        private AccountRef account(String code) {
            AccountingTemplateEntry accountRecord = recorded.get(AccountingTemplate.Account.keyOf(code));
            Optional<TenantChart.AccountRow> row = chart.findAccount(code);
            if (accountRecord != null && accountRecord.getOutcome() == TemplateEntryOutcome.CONFLICT) {
                return new AccountRef(
                        null,
                        Verdict.withheld(
                                TemplateEntryReason.DEPENDS_ON_CONFLICT,
                                row.map(TenantChart.AccountRow::describe).orElse(null)));
            }
            if (row.isEmpty()) {
                return new AccountRef(null, Verdict.withheld(TemplateEntryReason.ACCOUNT_MISSING, null));
            }
            return new AccountRef(row.get().id(), null);
        }

        private void refreshIfUntouched(AccountingTemplate.StatementLine line, AccountingTemplateEntry record) {
            if (line.fingerprint().equals(record.getEntryFingerprint()) || record.getTargetRowId() == null) {
                return;
            }
            Optional<TenantChart.LineRow> current = chart.findStatementLine(record.getTargetRowId());
            boolean untouched = current.isPresent()
                    && current.get().asTemplateLine().fingerprint().equals(record.getEntryFingerprint());
            if (!untouched) {
                return;
            }
            chart.refreshStatementLine(record.getTargetRowId(), line);
            record.setOutcome(TemplateEntryOutcome.REFRESHED);
            record.setEntryFingerprint(line.fingerprint());
            record.setTemplateValue(line.describe());
            entryRecords.save(record);
            changes.merge(TemplateEntryOutcome.REFRESHED, 1, Integer::sum);
        }

        private void record(
                AccountingTemplate.Entry entry, @Nullable AccountingTemplateEntry existing, Verdict verdict) {
            AccountingTemplateEntry record = existing;
            if (record == null) {
                record = new AccountingTemplateEntry();
                record.setEntryKey(entry.entryKey());
                record.setKind(entry.kind());
                recorded.put(entry.entryKey(), record);
            } else if (record.getOutcome() == verdict.outcome()
                    && record.getReason() == verdict.reason()
                    && Objects.equals(record.getTenantValue(), verdict.tenantValue())
                    && entry.fingerprint().equals(record.getEntryFingerprint())) {
                // Still open, for the same reason, against the same template entry: nothing to write.
                return;
            }
            record.setOutcome(verdict.outcome());
            record.setReason(verdict.reason());
            record.setEntryFingerprint(entry.fingerprint());
            record.setTargetRowId(verdict.targetRowId());
            record.setTemplateValue(entry.describe());
            record.setTenantValue(verdict.tenantValue());
            entryRecords.save(record);
            changes.merge(verdict.outcome(), 1, Integer::sum);
        }

        private IllegalStateException danglingReference(AccountingTemplate.Entry entry) {
            // Categories and keys are always created or adopted before what refers to them, so this
            // is a template that names a category or key it does not itself hold.
            return new IllegalStateException("Accounting template entry " + entry.entryKey()
                    + " refers to a category or mapping key the template does not define");
        }
    }
}
