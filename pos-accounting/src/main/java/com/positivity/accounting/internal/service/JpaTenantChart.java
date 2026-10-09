package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.entity.DefaultGLMapping;
import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.accounting.internal.entity.GLMapping;
import com.positivity.accounting.internal.entity.MappingKey;
import com.positivity.accounting.internal.entity.PettyExpenseCategory;
import com.positivity.accounting.internal.entity.PettyExpenseCategoryChange;
import com.positivity.accounting.internal.entity.PettyExpenseCategoryTaxSetting;
import com.positivity.accounting.internal.entity.PettyExpenseCategoryTaxSettingChange;
import com.positivity.accounting.internal.entity.PostingCategory;
import com.positivity.accounting.internal.entity.StatementLineMapping;
import com.positivity.accounting.internal.enums.GLAccountStatus;
import com.positivity.accounting.internal.enums.PettyExpenseCategoryChangeType;
import com.positivity.accounting.internal.enums.PettyExpenseCategoryStatus;
import com.positivity.accounting.internal.enums.StatementType;
import com.positivity.accounting.internal.repository.DefaultGLMappingRepository;
import com.positivity.accounting.internal.repository.GLAccountRepository;
import com.positivity.accounting.internal.repository.GLMappingRepository;
import com.positivity.accounting.internal.repository.MappingKeyRepository;
import com.positivity.accounting.internal.repository.PettyExpenseCategoryChangeRepository;
import com.positivity.accounting.internal.repository.PettyExpenseCategoryRepository;
import com.positivity.accounting.internal.repository.PettyExpenseCategoryTaxSettingChangeRepository;
import com.positivity.accounting.internal.repository.PettyExpenseCategoryTaxSettingRepository;
import com.positivity.accounting.internal.repository.PostingCategoryRepository;
import com.positivity.accounting.internal.repository.StatementLineMappingRepository;
import com.positivity.shared.id.UUIDv7Generator;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link TenantChart} over the module's repositories. Hibernate scopes every query to the bound
 * tenant and stamps it on every row created here; row-level security enforces the same in Postgres.
 *
 * <p>Created rows get a UUIDv7 id (ADR-0013), the actor {@link TenantChart#ACTOR}, the template's
 * dates, and no {@code organization_id} (ADR-0062 §4).
 */
@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class JpaTenantChart implements TenantChart {

    /** The history row's justification for a category the template created. */
    static final String TEMPLATE_JUSTIFICATION = "Provisioned from the accounting tenant template";

    /** The template's effective date: accounts are active and mappings effective from it. */
    static final Instant TEMPLATE_EFFECTIVE_FROM = Instant.parse("2020-01-01T00:00:00Z");

    private final GLAccountRepository accounts;
    private final PostingCategoryRepository categories;
    private final MappingKeyRepository mappingKeys;
    private final GLMappingRepository glMappings;
    private final DefaultGLMappingRepository defaultGlMappings;
    private final StatementLineMappingRepository statementLines;
    private final PettyExpenseCategoryRepository pettyExpenseCategories;
    private final PettyExpenseCategoryChangeRepository pettyExpenseCategoryChanges;
    private final PettyExpenseCategoryFacts pettyExpenseCategoryFacts;
    private final PettyExpenseCategoryTaxSettingRepository pettyExpenseTaxSettings;
    private final PettyExpenseCategoryTaxSettingChangeRepository pettyExpenseTaxSettingChanges;
    private final EntityManager entityManager;
    private final Clock clock;

    @Override
    public Optional<AccountRow> findAccount(@NonNull String code) {
        return accounts.findByAccountCode(code)
                .map(account -> new AccountRow(
                        account.getGlAccountId(),
                        account.getAccountCode(),
                        account.getAccountName(),
                        account.getAccountType(),
                        GLAccountStatus.ACTIVE.name().equals(account.getDerivedStatus())));
    }

    @Override
    public UUID createAccount(AccountingTemplate.@NonNull Account template) {
        GLAccount account = new GLAccount(UUIDv7Generator.generate());
        account.setAccountCode(template.code());
        account.setAccountName(template.name());
        account.setAccountType(template.type());
        account.setAccountSubtype(template.subtype());
        account.setReconcilable(template.reconcilable());
        account.setDescription(template.description());
        account.setActivationDate(template.activationDate());
        account.setCreatedBy(ACTOR);
        account.setModifiedBy(ACTOR);
        return accounts.save(account).getGlAccountId();
    }

    @Override
    public Optional<UUID> findCategory(@NonNull String name) {
        return categories.findByCategoryName(name).map(PostingCategory::getPostingCategoryId);
    }

    @Override
    public UUID createCategory(AccountingTemplate.@NonNull Category template) {
        PostingCategory category = new PostingCategory();
        category.setCategoryName(template.name());
        category.setDescription(template.description());
        category.setIsActive(true);
        category.setCreatedBy(ACTOR);
        category.setModifiedBy(ACTOR);
        return categories.save(category).getPostingCategoryId();
    }

    @Override
    public Optional<UUID> findMappingKey(@NonNull UUID categoryId, @NonNull String keyName) {
        return mappingKeys
                .findByPostingCategory_PostingCategoryIdAndKeyName(categoryId, keyName)
                .map(MappingKey::getMappingKeyId);
    }

    @Override
    public UUID createMappingKey(@NonNull UUID categoryId, AccountingTemplate.@NonNull Key template) {
        MappingKey key = new MappingKey();
        key.setPostingCategory(categories.getReferenceById(categoryId));
        key.setKeyName(template.keyName());
        key.setDescription(template.description());
        key.setIsActive(true);
        key.setCreatedBy(ACTOR);
        key.setModifiedBy(ACTOR);
        return mappingKeys.save(key).getMappingKeyId();
    }

    @Override
    public Optional<UUID> findUndimensionedGlMapping(@NonNull UUID mappingKeyId) {
        return glMappings.findByMappingKey_MappingKeyId(mappingKeyId).stream()
                .filter(mapping -> mapping.getDimensions() == null
                        || mapping.getDimensions().isEmpty())
                .min(Comparator.comparing(GLMapping::getEffectiveStartDate))
                .map(GLMapping::getGlMappingId);
    }

    @Override
    public UUID createGlMapping(
            @NonNull UUID categoryId,
            @NonNull UUID mappingKeyId,
            @NonNull UUID accountId,
            AccountingTemplate.@NonNull GlMapping template) {
        GLMapping mapping = new GLMapping();
        mapping.setSourceSystem(template.sourceSystem());
        mapping.setExternalCode(template.externalCode());
        mapping.setPostingCategory(categories.getReferenceById(categoryId));
        mapping.setMappingKey(mappingKeys.getReferenceById(mappingKeyId));
        mapping.setGlAccount(accounts.getReferenceById(accountId));
        mapping.setEffectiveStartDate(template.effectiveStart());
        mapping.setEffectiveEndDate(template.effectiveEnd());
        mapping.setCreatedBy(ACTOR);
        return glMappings.save(mapping).getGlMappingId();
    }

    @Override
    public Optional<UUID> findDefaultGlMapping(@NonNull String eventType) {
        return defaultGlMappings.findByEventType(eventType).stream()
                .min(Comparator.comparing(DefaultGLMapping::getCreatedAt))
                .map(DefaultGLMapping::getMappingId);
    }

    @Override
    public UUID createDefaultGlMapping(
            AccountingTemplate.@NonNull DefaultGlMapping template,
            @NonNull UUID debitAccountId,
            @NonNull UUID creditAccountId) {
        DefaultGLMapping mapping = new DefaultGLMapping();
        mapping.setEventType(template.eventType());
        mapping.setDebitAccount(accounts.getReferenceById(debitAccountId));
        mapping.setCreditAccount(accounts.getReferenceById(creditAccountId));
        mapping.setDescription(template.description());
        mapping.setActive(true);
        mapping.setCreatedBy(ACTOR);
        mapping.setModifiedBy(ACTOR);
        return defaultGlMappings.save(mapping).getMappingId();
    }

    /**
     * The account's <em>global</em> line on the statement. A line with a {@code locationId} is a
     * per-location override (#731), a tenant's own refinement of its report: it is never what the
     * template adopts (a tenant holding only an override still needs the global line, or every other
     * location omits the account) and never what a refresh rewrites.
     */
    @Override
    public Optional<LineRow> findStatementLine(@NonNull StatementType statementType, @NonNull UUID accountId) {
        return statementLines.findByGlAccount_GlAccountId(accountId).stream()
                .filter(line -> line.getStatementType() == statementType && line.getLocationId() == null)
                .min(Comparator.comparing(StatementLineMapping::getMappingId))
                .map(JpaTenantChart::lineRow);
    }

    @Override
    public Optional<LineRow> findStatementLine(@NonNull UUID lineId) {
        return statementLines
                .findById(lineId)
                .filter(line -> line.getLocationId() == null)
                .map(JpaTenantChart::lineRow);
    }

    @Override
    public UUID createStatementLine(AccountingTemplate.@NonNull StatementLine template, @NonNull UUID accountId) {
        GLAccount account = accounts.findById(accountId).orElseThrow();
        StatementLineMapping line = StatementLineMapping.builder()
                .glAccount(account)
                // The account's name, which the drill-down shows; not part of the entry fingerprint.
                .accountName(account.getAccountName())
                .statementType(template.statementType())
                .statementLineCode(template.lineCode())
                .parentLineCode(template.parentLineCode())
                .lineDescription(template.lineDescription())
                .displayOrder(template.displayOrder())
                .operation(template.operation())
                .build();
        return statementLines.save(line).getMappingId();
    }

    @Override
    public void refreshStatementLine(@NonNull UUID lineId, AccountingTemplate.@NonNull StatementLine template) {
        StatementLineMapping line = statementLines
                .findById(lineId)
                .filter(row -> row.getLocationId() == null)
                .orElseThrow(() -> new IllegalStateException("statement line " + lineId + " is not a global line"));
        line.setStatementLineCode(template.lineCode());
        line.setParentLineCode(template.parentLineCode());
        line.setLineDescription(template.lineDescription());
        line.setDisplayOrder(template.displayOrder());
        statementLines.save(line);
    }

    @Override
    public Optional<UUID> findPettyExpenseCategory(@NonNull String code) {
        return pettyExpenseCategories.findByCode(code).map(PettyExpenseCategory::getPettyExpenseCategoryId);
    }

    @Override
    public UUID createPettyExpenseCategory(
            @NonNull UUID mappingKeyId, AccountingTemplate.@NonNull PettyExpenseCategory template) {
        PettyExpenseCategory category = new PettyExpenseCategory();
        category.setMappingKeyId(mappingKeyId);
        category.setCode(template.code());
        category.setLabel(template.label());
        category.setExamples(template.examples());
        category.setStatus(PettyExpenseCategoryStatus.ACTIVE);
        category.setCreatedBy(ACTOR);
        category.setModifiedBy(ACTOR);
        PettyExpenseCategory saved = pettyExpenseCategories.saveAndFlush(category);

        PettyExpenseCategoryChange change = new PettyExpenseCategoryChange();
        change.setPettyExpenseCategoryId(saved.getPettyExpenseCategoryId());
        change.setCode(saved.getCode());
        change.setChangeType(PettyExpenseCategoryChangeType.CREATE);
        change.setNewValue(template.describe());
        change.setActor(ACTOR);
        change.setJustification(TEMPLATE_JUSTIFICATION);
        change.setChangedAt(Instant.now(clock));
        pettyExpenseCategoryChanges.save(change);

        pettyExpenseCategoryFacts.changed(saved, ACTOR);
        return saved.getPettyExpenseCategoryId();
    }

    @Override
    public Optional<UUID> findPettyExpenseTaxRecovery(@NonNull String code) {
        return pettyExpenseTaxSettings.findByCode(code).map(PettyExpenseCategoryTaxSetting::getTaxSettingId);
    }

    @Override
    public UUID createPettyExpenseTaxRecovery(
            @NonNull UUID pettyExpenseCategoryId, AccountingTemplate.@NonNull PettyExpenseTaxRecovery template) {
        PettyExpenseCategory category = pettyExpenseCategories
                .findById(pettyExpenseCategoryId)
                .orElseThrow(() -> new IllegalStateException(
                        "Petty-expense category " + template.code() + " vanished while its tax recovery was applied"));
        BigDecimal percent = template.taxRecoverable() ? template.recoverablePercent() : null;
        PettyExpenseCategoryTaxSetting setting = new PettyExpenseCategoryTaxSetting();
        setting.setPettyExpenseCategoryId(pettyExpenseCategoryId);
        setting.setCode(template.code());
        setting.setTaxRecoverable(template.taxRecoverable());
        setting.setRecoverablePercent(percent);
        setting.setCreatedBy(ACTOR);
        setting.setModifiedBy(ACTOR);
        PettyExpenseCategoryTaxSetting saved = pettyExpenseTaxSettings.saveAndFlush(setting);

        PettyExpenseCategoryTaxSettingChange change = new PettyExpenseCategoryTaxSettingChange();
        change.setPettyExpenseCategoryId(pettyExpenseCategoryId);
        change.setCode(template.code());
        // In force from the template's date, so a movement recorded before provisioning finds it too.
        change.setEffectiveFrom(TEMPLATE_EFFECTIVE_FROM);
        change.setNewTaxRecoverable(template.taxRecoverable());
        change.setNewRecoverablePercent(percent);
        change.setActor(ACTOR);
        change.setJustification(TEMPLATE_JUSTIFICATION);
        pettyExpenseTaxSettingChanges.save(change);

        entityManager.lock(category, LockModeType.PESSIMISTIC_FORCE_INCREMENT);
        pettyExpenseCategoryFacts.changed(category, ACTOR);
        return saved.getTaxSettingId();
    }

    private static LineRow lineRow(StatementLineMapping line) {
        return new LineRow(
                line.getMappingId(),
                new AccountingTemplate.StatementLine(
                        line.getStatementType(),
                        line.getGlAccount().getAccountCode(),
                        line.getStatementLineCode(),
                        line.getParentLineCode(),
                        line.getLineDescription(),
                        line.getDisplayOrder(),
                        line.getOperation()));
    }
}
