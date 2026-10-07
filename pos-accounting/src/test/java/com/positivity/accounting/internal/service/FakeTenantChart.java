package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.enums.AccountType;
import com.positivity.accounting.internal.enums.StatementType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * An in-memory tenant chart for {@link AccountingTemplateApplierTest}: what a tenant holds, plus a
 * log of every row the applier created or refreshed, so a test can say "nothing was written".
 */
final class FakeTenantChart implements TenantChart {

    /** A GL mapping row: which key it maps, to which account. */
    record Mapping(UUID id, UUID keyId, UUID accountId) {}

    /** A default GL mapping row. */
    record DefaultMapping(UUID id, String eventType, UUID debitAccountId, UUID creditAccountId) {}

    final Map<String, AccountRow> accounts = new LinkedHashMap<>();
    final Map<String, UUID> categories = new LinkedHashMap<>();
    final Map<String, UUID> keys = new LinkedHashMap<>();
    final List<Mapping> mappings = new ArrayList<>();
    final List<DefaultMapping> defaultMappings = new ArrayList<>();
    final Map<UUID, AccountingTemplate.StatementLine> lines = new LinkedHashMap<>();
    final Map<UUID, UUID> lineAccounts = new LinkedHashMap<>();
    final Map<UUID, String> lineLocations = new LinkedHashMap<>();
    final Map<String, UUID> pettyExpenseCategories = new LinkedHashMap<>();

    /** Every write, in order: {@code "create ACCOUNT:1000"}, {@code "refresh STATEMENT_LINE:..."}. */
    final List<String> writes = new ArrayList<>();

    /** How many times the applier looked anything up. */
    int lookups;

    // --- what a tenant does to its own chart, outside the applier ---

    UUID holdAccount(String code, String name, AccountType type) {
        return holdAccount(code, name, type, true);
    }

    UUID holdAccount(String code, String name, AccountType type, boolean active) {
        UUID id = UUID.randomUUID();
        accounts.put(code, new AccountRow(id, code, name, type, active));
        return id;
    }

    void renameAccount(String code, String name) {
        AccountRow row = accounts.get(code);
        accounts.put(code, new AccountRow(row.id(), code, name, row.type(), row.active()));
    }

    void renumberAccount(String from, String to) {
        AccountRow row = accounts.remove(from);
        accounts.put(to, new AccountRow(row.id(), to, row.name(), row.type(), row.active()));
    }

    UUID holdCategory(String name) {
        return categories.computeIfAbsent(name, key -> UUID.randomUUID());
    }

    UUID holdKey(String category, String keyName) {
        return keys.computeIfAbsent(holdCategory(category) + "/" + keyName, key -> UUID.randomUUID());
    }

    void holdMapping(String category, String keyName, String accountCode) {
        mappings.add(new Mapping(
                UUID.randomUUID(),
                holdKey(category, keyName),
                accounts.get(accountCode).id()));
    }

    UUID holdLine(AccountingTemplate.StatementLine line) {
        UUID id = UUID.randomUUID();
        lines.put(id, line);
        lineAccounts.put(id, accounts.get(line.accountCode()).id());
        return id;
    }

    /** A per-location override of a line (#731): never what the template looks for or writes. */
    UUID holdLocationOverride(AccountingTemplate.StatementLine line, String locationId) {
        UUID id = holdLine(line);
        lineLocations.put(id, locationId);
        return id;
    }

    AccountingTemplate.StatementLine lineOf(StatementType type, String accountCode) {
        return lines.values().stream()
                .filter(line ->
                        line.statementType() == type && line.accountCode().equals(accountCode))
                .findFirst()
                .orElseThrow();
    }

    UUID lineIdOf(StatementType type, String accountCode) {
        return lines.entrySet().stream()
                .filter(entry -> entry.getValue().statementType() == type
                        && entry.getValue().accountCode().equals(accountCode))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow();
    }

    String accountCodeOfMapping(String category, String keyName) {
        UUID keyId = keys.get(categories.get(category) + "/" + keyName);
        UUID accountId = mappings.stream()
                .filter(mapping -> mapping.keyId().equals(keyId))
                .findFirst()
                .orElseThrow()
                .accountId();
        return accounts.values().stream()
                .filter(row -> row.id().equals(accountId))
                .findFirst()
                .orElseThrow()
                .code();
    }

    // --- TenantChart ---

    @Override
    public Optional<AccountRow> findAccount(String code) {
        lookups++;
        return Optional.ofNullable(accounts.get(code));
    }

    @Override
    public UUID createAccount(AccountingTemplate.Account account) {
        writes.add("create " + account.entryKey());
        return holdAccount(account.code(), account.name(), account.type());
    }

    @Override
    public Optional<UUID> findCategory(String name) {
        lookups++;
        return Optional.ofNullable(categories.get(name));
    }

    @Override
    public UUID createCategory(AccountingTemplate.Category category) {
        writes.add("create " + category.entryKey());
        return holdCategory(category.name());
    }

    @Override
    public Optional<UUID> findMappingKey(UUID categoryId, String keyName) {
        lookups++;
        return Optional.ofNullable(keys.get(categoryId + "/" + keyName));
    }

    @Override
    public UUID createMappingKey(UUID categoryId, AccountingTemplate.Key key) {
        writes.add("create " + key.entryKey());
        UUID id = UUID.randomUUID();
        keys.put(categoryId + "/" + key.keyName(), id);
        return id;
    }

    @Override
    public Optional<UUID> findUndimensionedGlMapping(UUID mappingKeyId) {
        lookups++;
        return mappings.stream()
                .filter(mapping -> mapping.keyId().equals(mappingKeyId))
                .map(Mapping::id)
                .findFirst();
    }

    @Override
    public UUID createGlMapping(
            UUID categoryId, UUID mappingKeyId, UUID accountId, AccountingTemplate.GlMapping mapping) {
        writes.add("create " + mapping.entryKey());
        UUID id = UUID.randomUUID();
        mappings.add(new Mapping(id, mappingKeyId, accountId));
        return id;
    }

    @Override
    public Optional<UUID> findDefaultGlMapping(String eventType) {
        lookups++;
        return defaultMappings.stream()
                .filter(mapping -> mapping.eventType().equals(eventType))
                .map(DefaultMapping::id)
                .findFirst();
    }

    @Override
    public UUID createDefaultGlMapping(
            AccountingTemplate.DefaultGlMapping mapping, UUID debitAccountId, UUID creditAccountId) {
        writes.add("create " + mapping.entryKey());
        UUID id = UUID.randomUUID();
        defaultMappings.add(new DefaultMapping(id, mapping.eventType(), debitAccountId, creditAccountId));
        return id;
    }

    @Override
    public Optional<LineRow> findStatementLine(StatementType statementType, UUID accountId) {
        lookups++;
        return lines.entrySet().stream()
                .filter(entry -> entry.getValue().statementType() == statementType
                        && accountId.equals(lineAccounts.get(entry.getKey()))
                        && !lineLocations.containsKey(entry.getKey()))
                .map(entry -> new LineRow(entry.getKey(), entry.getValue()))
                .findFirst();
    }

    @Override
    public Optional<LineRow> findStatementLine(UUID lineId) {
        lookups++;
        if (lineLocations.containsKey(lineId)) {
            return Optional.empty();
        }
        return Optional.ofNullable(lines.get(lineId)).map(line -> new LineRow(lineId, line));
    }

    @Override
    public UUID createStatementLine(AccountingTemplate.StatementLine line, UUID accountId) {
        writes.add("create " + line.entryKey());
        UUID id = UUID.randomUUID();
        lines.put(id, line);
        lineAccounts.put(id, accountId);
        return id;
    }

    @Override
    public void refreshStatementLine(UUID lineId, AccountingTemplate.StatementLine line) {
        writes.add("refresh " + line.entryKey());
        AccountingTemplate.StatementLine current = lines.get(lineId);
        lines.put(
                lineId,
                new AccountingTemplate.StatementLine(
                        current.statementType(),
                        current.accountCode(),
                        line.lineCode(),
                        line.parentLineCode(),
                        line.lineDescription(),
                        line.displayOrder(),
                        current.operation()));
    }

    @Override
    public Optional<UUID> findPettyExpenseCategory(String code) {
        lookups++;
        return Optional.ofNullable(pettyExpenseCategories.get(code));
    }

    @Override
    public UUID createPettyExpenseCategory(UUID mappingKeyId, AccountingTemplate.PettyExpenseCategory category) {
        writes.add("create " + category.entryKey());
        UUID id = UUID.randomUUID();
        pettyExpenseCategories.put(category.code(), id);
        return id;
    }
}
