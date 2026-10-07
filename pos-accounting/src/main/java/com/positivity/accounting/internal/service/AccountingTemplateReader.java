package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.entity.DefaultGLMapping;
import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.accounting.internal.entity.GLMapping;
import com.positivity.accounting.internal.entity.MappingKey;
import com.positivity.accounting.internal.entity.PettyExpenseCategory;
import com.positivity.accounting.internal.entity.PostingCategory;
import com.positivity.accounting.internal.entity.StatementLineMapping;
import com.positivity.accounting.internal.repository.DefaultGLMappingRepository;
import com.positivity.accounting.internal.repository.GLAccountRepository;
import com.positivity.accounting.internal.repository.GLMappingRepository;
import com.positivity.accounting.internal.repository.MappingKeyRepository;
import com.positivity.accounting.internal.repository.PettyExpenseCategoryRepository;
import com.positivity.accounting.internal.repository.PostingCategoryRepository;
import com.positivity.accounting.internal.repository.StatementLineMappingRepository;
import com.positivity.tenancy.PlatformTenant;
import com.positivity.tenancy.TenantContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reads the accounting template out of the platform tenant (#2526; ADR-0062 §6) and is the generic
 * {@link AccountingTemplateSource}: everything in the template that no opt-in source owns.
 *
 * <p>The template changes only through Flyway, which runs before the application starts, so one
 * successful read lasts the life of the process. A read that fails or finds nothing is not kept:
 * the next caller reads again.
 *
 * <p><strong>Who may call {@link #snapshot()}.</strong> It binds the platform tenant for the length
 * of the read and opens its transaction inside that binding, because a Hibernate session fixes its
 * tenant when it opens. Call it from a thread that owns its binding (the {@code tenant.created}
 * listener, the startup sweep) and <em>before</em> binding the tenant being provisioned, never
 * inside that tenant's transaction. A request thread must not switch tenant: it asks
 * {@link #loaded()} and makes do with what an earlier read left.
 */
@Slf4j
@Component
public class AccountingTemplateReader implements AccountingTemplateSource {

    private final GLAccountRepository accounts;
    private final PostingCategoryRepository categories;
    private final MappingKeyRepository mappingKeys;
    private final GLMappingRepository glMappings;
    private final DefaultGLMappingRepository defaultGlMappings;
    private final StatementLineMappingRepository statementLines;
    private final PettyExpenseCategoryRepository pettyExpenseCategories;
    private final TransactionTemplate readOnly;
    private final AtomicReference<AccountingTemplate> loaded = new AtomicReference<>();

    public AccountingTemplateReader(
            GLAccountRepository accounts,
            PostingCategoryRepository categories,
            MappingKeyRepository mappingKeys,
            GLMappingRepository glMappings,
            DefaultGLMappingRepository defaultGlMappings,
            StatementLineMappingRepository statementLines,
            PettyExpenseCategoryRepository pettyExpenseCategories,
            PlatformTransactionManager transactionManager) {
        this.accounts = accounts;
        this.categories = categories;
        this.mappingKeys = mappingKeys;
        this.glMappings = glMappings;
        this.defaultGlMappings = defaultGlMappings;
        this.statementLines = statementLines;
        this.pettyExpenseCategories = pettyExpenseCategories;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
    }

    /**
     * The whole template, every source's entries included.
     *
     * @throws EmptyAccountingTemplateException when the platform tenant holds no template
     */
    public @NonNull AccountingTemplate snapshot() {
        AccountingTemplate known = loaded.get();
        if (known != null) {
            return known;
        }
        AccountingTemplate read = TenantContext.callAs(PlatformTenant.ID, () -> readOnly.execute(status -> read()));
        if (read == null || read.isEmpty()) {
            throw new EmptyAccountingTemplateException();
        }
        loaded.set(read);
        log.info(
                "Accounting template read from the platform tenant: {} entries, fingerprint {}",
                read.entries().size(),
                read.fingerprint());
        return read;
    }

    /** The template an earlier {@link #snapshot()} read, without reading: safe on a request thread. */
    public @NonNull Optional<AccountingTemplate> loaded() {
        return Optional.ofNullable(loaded.get());
    }

    @Override
    public String name() {
        return "generic";
    }

    @Override
    public boolean owns(AccountingTemplate.@NonNull Entry entry) {
        return !RetreadPlantAddOnSource.ENTRY_KEYS.contains(entry.entryKey());
    }

    @Override
    public boolean appliesTo(@NonNull UUID tenantId) {
        return true;
    }

    private AccountingTemplate read() {
        List<AccountingTemplate.Entry> entries = new ArrayList<>();
        for (GLAccount account : accounts.findAll()) {
            entries.add(new AccountingTemplate.Account(
                    account.getAccountCode(),
                    account.getAccountName(),
                    account.getAccountType(),
                    account.getAccountSubtype(),
                    account.isReconcilable(),
                    account.getDescription(),
                    account.getActivationDate()));
        }
        for (PostingCategory category : categories.findAll()) {
            entries.add(new AccountingTemplate.Category(category.getCategoryName(), category.getDescription()));
        }
        for (MappingKey key : mappingKeys.findAll()) {
            entries.add(new AccountingTemplate.Key(
                    key.getPostingCategory().getCategoryName(), key.getKeyName(), key.getDescription()));
        }
        for (GLMapping mapping : glMappings.findAll()) {
            boolean dimensioned =
                    mapping.getDimensions() != null && !mapping.getDimensions().isEmpty();
            if (mapping.getMappingKey() == null || dimensioned) {
                // The template maps each key once, without dimensions; anything else is not template data.
                continue;
            }
            MappingKey key = mapping.getMappingKey();
            entries.add(new AccountingTemplate.GlMapping(
                    key.getPostingCategory().getCategoryName(),
                    key.getKeyName(),
                    mapping.getSourceSystem(),
                    mapping.getExternalCode(),
                    mapping.getGlAccount().getAccountCode(),
                    mapping.getEffectiveStartDate(),
                    mapping.getEffectiveEndDate()));
        }
        for (DefaultGLMapping mapping : defaultGlMappings.findAll()) {
            entries.add(new AccountingTemplate.DefaultGlMapping(
                    mapping.getEventType(),
                    mapping.getDebitAccount().getAccountCode(),
                    mapping.getCreditAccount().getAccountCode(),
                    mapping.getDescription()));
        }
        for (StatementLineMapping line : statementLines.findAll()) {
            entries.add(new AccountingTemplate.StatementLine(
                    line.getStatementType(),
                    line.getGlAccount().getAccountCode(),
                    line.getStatementLineCode(),
                    line.getParentLineCode(),
                    line.getLineDescription(),
                    line.getDisplayOrder(),
                    line.getOperation()));
        }
        for (PettyExpenseCategory category : pettyExpenseCategories.findAllByOrderByCodeAsc()) {
            entries.add(new AccountingTemplate.PettyExpenseCategory(
                    category.getCode(), category.getLabel(), category.getExamples()));
        }
        return AccountingTemplate.of(entries);
    }
}
