package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.enums.AccountType;
import com.positivity.accounting.internal.enums.StatementType;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * What {@link AccountingTemplateApplier} needs of the bound tenant's chart (#2526): look a row up
 * by the natural key the template knows it by, and create it when the tenant lacks it.
 *
 * <p>There is deliberately no update or delete beyond {@link #refreshStatementLine}: the applier
 * never renames, retypes, repoints, reactivates or deletes a tenant row, and an interface without
 * the verbs cannot grow the habit. Every method acts on the tenant bound to the calling thread and
 * runs inside the caller's transaction.
 */
public interface TenantChart {

    /** The actor recorded on every row the template creates (ADR-0018). */
    String ACTOR = "tenant-template";

    /** The tenant's account under this code. */
    @NonNull
    Optional<AccountRow> findAccount(@NonNull String code);

    /** Creates the account and returns its id. */
    @NonNull
    UUID createAccount(AccountingTemplate.@NonNull Account account);

    /** The id of the tenant's posting category with this name. */
    @NonNull
    Optional<UUID> findCategory(@NonNull String name);

    /** Creates the posting category and returns its id. */
    @NonNull
    UUID createCategory(AccountingTemplate.@NonNull Category category);

    /** The id of the tenant's mapping key with this name in this category. */
    @NonNull
    Optional<UUID> findMappingKey(@NonNull UUID categoryId, @NonNull String keyName);

    /** Creates the mapping key in the category and returns its id. */
    @NonNull
    UUID createMappingKey(@NonNull UUID categoryId, AccountingTemplate.@NonNull Key key);

    /** The id of any mapping of this key that carries no dimensions, whatever account it names. */
    @NonNull
    Optional<UUID> findUndimensionedGlMapping(@NonNull UUID mappingKeyId);

    /** Creates the mapping of the key to the account and returns its id. */
    @NonNull
    UUID createGlMapping(
            @NonNull UUID categoryId,
            @NonNull UUID mappingKeyId,
            @NonNull UUID accountId,
            AccountingTemplate.@NonNull GlMapping mapping);

    /** The id of any default mapping of this event type. */
    @NonNull
    Optional<UUID> findDefaultGlMapping(@NonNull String eventType);

    /** Creates the default mapping and returns its id. */
    @NonNull
    UUID createDefaultGlMapping(
            AccountingTemplate.@NonNull DefaultGlMapping mapping,
            @NonNull UUID debitAccountId,
            @NonNull UUID creditAccountId);

    /** The account's line on the statement. */
    @NonNull
    Optional<LineRow> findStatementLine(@NonNull StatementType statementType, @NonNull UUID accountId);

    /** The statement line with this id, if the tenant still holds it. */
    @NonNull
    Optional<LineRow> findStatementLine(@NonNull UUID lineId);

    /** Creates the account's line on the statement and returns its id. */
    @NonNull
    UUID createStatementLine(AccountingTemplate.@NonNull StatementLine line, @NonNull UUID accountId);

    /**
     * Gives the line the template's line code, parent line code, description and display order.
     * Presentation only: the account, the statement and the operation stay as they are.
     */
    void refreshStatementLine(@NonNull UUID lineId, AccountingTemplate.@NonNull StatementLine line);

    /** The id of the tenant's petty-expense category with this code (#2511). */
    @NonNull
    Optional<UUID> findPettyExpenseCategory(@NonNull String code);

    /**
     * Creates the petty-expense category on the key, records its {@code CREATE} history row and
     * queues its {@code accounting.petty-expense-category.changed} fact; returns its id.
     */
    @NonNull
    UUID createPettyExpenseCategory(
            @NonNull UUID mappingKeyId, AccountingTemplate.@NonNull PettyExpenseCategory category);

    /** Whether the tenant has set the tax recovery of the petty-expense category with this code (CAP:550 S32d). */
    @NonNull
    Optional<UUID> findPettyExpenseTaxRecovery(@NonNull String code);

    /**
     * Sets a petty-expense category's tax recovery from the template, in force from the template's date, records its
     * history row and queues the category's fact again with the new values; returns the setting's id.
     */
    @NonNull
    UUID createPettyExpenseTaxRecovery(
            @NonNull UUID pettyExpenseCategoryId, AccountingTemplate.@NonNull PettyExpenseTaxRecovery recovery);

    /**
     * A tenant account as the adoption test sees it.
     *
     * @param active true when the account can be posted to today
     */
    record AccountRow(
            @NonNull UUID id,
            @NonNull String code,
            @NonNull String name,
            @NonNull AccountType type,
            boolean active) {

        /** The account in business words. */
        public @NonNull String describe() {
            return AccountingTemplate.describeAccount(code, name, type) + (active ? "" : ", inactive");
        }
    }

    /**
     * A tenant statement line.
     *
     * @param asTemplateLine the line's present values in the template's shape, so its fingerprint
     *     can be compared with the one recorded when the template last wrote it
     */
    record LineRow(@NonNull UUID id, AccountingTemplate.@NonNull StatementLine asTemplateLine) {}
}
