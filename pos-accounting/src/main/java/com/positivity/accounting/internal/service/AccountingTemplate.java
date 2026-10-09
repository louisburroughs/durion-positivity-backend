package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.enums.AccountSubtype;
import com.positivity.accounting.internal.enums.AccountType;
import com.positivity.accounting.internal.enums.OperationType;
import com.positivity.accounting.internal.enums.StatementType;
import com.positivity.accounting.internal.enums.TemplateEntryKind;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * An immutable snapshot of accounting template entries (#2526; ADR-0062 §6), with a SHA-256
 * fingerprint over all of them.
 *
 * <p>Entries hold no ids: everything refers to everything else by natural key (an account by its
 * code, a mapping key by category and key name), because a template row's id in the platform
 * tenant can never be a tenant row's id. The entries are kept in the order the applier walks them:
 * by {@link TemplateEntryKind}, then by entry key, so an entry only refers to entries before it and
 * the fingerprint does not depend on the order the rows were read in.
 *
 * @param entries the entries, in apply order
 * @param fingerprint SHA-256 (hex) over every entry's fingerprint; two snapshots with the same
 *     entries have the same fingerprint
 */
public record AccountingTemplate(
        @NonNull List<Entry> entries, @NonNull String fingerprint) {

    private static final Comparator<Entry> APPLY_ORDER =
            Comparator.comparing(Entry::kind).thenComparing(Entry::entryKey);

    /** Builds a snapshot from entries in any order. */
    public static @NonNull AccountingTemplate of(@NonNull Collection<? extends Entry> entries) {
        List<Entry> ordered =
                entries.stream().map(Entry.class::cast).sorted(APPLY_ORDER).toList();
        StringBuilder all = new StringBuilder();
        for (Entry entry : ordered) {
            all.append(entry.fingerprint()).append('\n');
        }
        return new AccountingTemplate(ordered, sha256(all.toString()));
    }

    /** True when the snapshot holds nothing: a template that was never seeded. */
    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** The entries {@code keep} accepts, as a snapshot with its own fingerprint. */
    public @NonNull AccountingTemplate only(@NonNull Predicate<Entry> keep) {
        return of(entries.stream().filter(keep).toList());
    }

    /** One template entry. */
    public sealed interface Entry
            permits Account,
                    Category,
                    Key,
                    GlMapping,
                    DefaultGlMapping,
                    StatementLine,
                    PettyExpenseCategory,
                    PettyExpenseTaxRecovery {

        /** The entry's kind. */
        @NonNull
        TemplateEntryKind kind();

        /** What identifies the entry within its kind: the part of the entry key after the kind. */
        @NonNull
        String naturalKey();

        /** Every value of the entry, in a fixed order; a change to any of them changes the fingerprint. */
        @NonNull
        String canonical();

        /** The entry in business words, for the status read and the logs. Never an id. */
        @NonNull
        String describe();

        /** {@code KIND:natural key}, e.g. {@code ACCOUNT:1000}. */
        default @NonNull String entryKey() {
            return kind().name() + ":" + naturalKey();
        }

        /** SHA-256 (hex) of the entry key and {@link #canonical()}. */
        default @NonNull String fingerprint() {
            return sha256(entryKey() + "\u001f" + canonical());
        }
    }

    /** A GL account. */
    public record Account(
            @NonNull String code,
            @NonNull String name,
            @NonNull AccountType type,
            @Nullable AccountSubtype subtype,
            boolean reconcilable,
            @Nullable String description,
            @Nullable LocalDateTime activationDate)
            implements Entry {

        /** The entry key of the account with this code. */
        public static @NonNull String keyOf(@NonNull String code) {
            return TemplateEntryKind.ACCOUNT.name() + ":" + code;
        }

        @Override
        public TemplateEntryKind kind() {
            return TemplateEntryKind.ACCOUNT;
        }

        @Override
        public String naturalKey() {
            return code;
        }

        @Override
        public String canonical() {
            return join(name, type, subtype, reconcilable, description, activationDate);
        }

        @Override
        public String describe() {
            return describeAccount(code, name, type);
        }
    }

    /** A posting category. */
    public record Category(@NonNull String name, @Nullable String description) implements Entry {

        @Override
        public TemplateEntryKind kind() {
            return TemplateEntryKind.CATEGORY;
        }

        @Override
        public String naturalKey() {
            return name;
        }

        @Override
        public String canonical() {
            return join(description);
        }

        @Override
        public String describe() {
            return "posting category " + name;
        }
    }

    /** A mapping key of a posting category. */
    public record Key(
            @NonNull String category,
            @NonNull String keyName,
            @Nullable String description) implements Entry {

        @Override
        public TemplateEntryKind kind() {
            return TemplateEntryKind.MAPPING_KEY;
        }

        @Override
        public String naturalKey() {
            return category + "/" + keyName;
        }

        @Override
        public String canonical() {
            return join(description);
        }

        @Override
        public String describe() {
            return "mapping key " + keyName + " of " + category;
        }
    }

    /** The GL mapping of a mapping key: the account its postings reach, without dimensions. */
    public record GlMapping(
            @NonNull String category,
            @NonNull String keyName,
            @NonNull String sourceSystem,
            @NonNull String externalCode,
            @NonNull String accountCode,
            @NonNull LocalDateTime effectiveStart,
            @Nullable LocalDateTime effectiveEnd)
            implements Entry {

        @Override
        public TemplateEntryKind kind() {
            return TemplateEntryKind.GL_MAPPING;
        }

        @Override
        public String naturalKey() {
            return category + "/" + keyName;
        }

        @Override
        public String canonical() {
            return join(sourceSystem, externalCode, accountCode, effectiveStart, effectiveEnd);
        }

        @Override
        public String describe() {
            return category + " / " + keyName + " posts to account " + accountCode;
        }
    }

    /** The default debit and credit accounts of an event type. */
    public record DefaultGlMapping(
            @NonNull String eventType,
            @NonNull String debitAccountCode,
            @NonNull String creditAccountCode,
            @Nullable String description)
            implements Entry {

        @Override
        public TemplateEntryKind kind() {
            return TemplateEntryKind.DEFAULT_GL_MAPPING;
        }

        @Override
        public String naturalKey() {
            return eventType;
        }

        @Override
        public String canonical() {
            return join(debitAccountCode, creditAccountCode, description);
        }

        @Override
        public String describe() {
            return eventType + " debits account " + debitAccountCode + " and credits account " + creditAccountCode;
        }
    }

    /** An account's line on a statement. One line per account per statement. */
    public record StatementLine(
            @NonNull StatementType statementType,
            @NonNull String accountCode,
            @NonNull String lineCode,
            @Nullable String parentLineCode,
            @Nullable String lineDescription,
            @Nullable Integer displayOrder,
            @NonNull OperationType operation)
            implements Entry {

        @Override
        public TemplateEntryKind kind() {
            return TemplateEntryKind.STATEMENT_LINE;
        }

        @Override
        public String naturalKey() {
            return statementType.name() + ":" + accountCode;
        }

        @Override
        public String canonical() {
            return join(lineCode, parentLineCode, lineDescription, displayOrder, operation);
        }

        @Override
        public String describe() {
            return "account " + accountCode + " on line " + lineCode
                    + (lineDescription == null ? "" : " " + lineDescription) + " of the "
                    + statementType.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        }
    }

    /**
     * A petty-expense category (#2511; SPEC-accounting-workspace §4.6): the label and examples of the
     * {@code REGISTER_CASH_MOVEMENT} key {@code PETTY_EXPENSE_<code>}. Matched in a tenant by code and
     * applied after its key and GL mapping.
     */
    public record PettyExpenseCategory(
            @NonNull String code,
            @NonNull String label,
            @Nullable String examples) implements Entry {

        /** The posting category every petty-expense key belongs to. */
        public static final String POSTING_CATEGORY = "REGISTER_CASH_MOVEMENT";

        /** The prefix of a petty-expense category's mapping key. */
        public static final String KEY_PREFIX = "PETTY_EXPENSE_";

        /** The mapping key of the category with this code. */
        public static @NonNull String keyNameOf(@NonNull String code) {
            return KEY_PREFIX + code;
        }

        /** The entry key of this category's GL mapping. */
        public @NonNull String glMappingEntryKey() {
            return TemplateEntryKind.GL_MAPPING.name() + ":" + POSTING_CATEGORY + "/" + keyNameOf(code);
        }

        @Override
        public TemplateEntryKind kind() {
            return TemplateEntryKind.PETTY_EXPENSE_CATEGORY;
        }

        @Override
        public String naturalKey() {
            return code;
        }

        @Override
        public String canonical() {
            return join(label, examples);
        }

        @Override
        public String describe() {
            return "petty-expense category " + code + " (" + label + ")";
        }
    }

    /**
     * A petty-expense category's tax recovery (CAP:550 S32d item 3): whether the tax stated on its receipts is
     * recovered, and which share. Matched in a tenant by category code and applied after the category, in force from
     * the template's date so it covers every movement. A tenant that has set its own keeps it.
     */
    public record PettyExpenseTaxRecovery(
            @NonNull String code,
            boolean taxRecoverable,
            @Nullable BigDecimal recoverablePercent) implements Entry {

        /** The entry key of the category this recovery belongs to. */
        public @NonNull String categoryEntryKey() {
            return TemplateEntryKind.PETTY_EXPENSE_CATEGORY.name() + ":" + code;
        }

        @Override
        public TemplateEntryKind kind() {
            return TemplateEntryKind.PETTY_EXPENSE_TAX_RECOVERY;
        }

        @Override
        public String naturalKey() {
            return code;
        }

        @Override
        public String canonical() {
            return join(
                    taxRecoverable,
                    recoverablePercent == null
                            ? null
                            : recoverablePercent.stripTrailingZeros().toPlainString());
        }

        @Override
        public String describe() {
            return "tax recovery of petty-expense category " + code
                    + (taxRecoverable
                            ? " at " + recoverablePercent.stripTrailingZeros().toPlainString() + " %"
                            : " off");
        }
    }

    /** An account in business words: {@code 6295 Staff Meals & Refreshments, expense}. */
    public static @NonNull String describeAccount(
            @NonNull String code, @NonNull String name, @NonNull AccountType type) {
        return code + " " + name.trim() + ", " + type.name().toLowerCase(Locale.ROOT);
    }

    private static String join(Object... values) {
        StringBuilder joined = new StringBuilder();
        for (Object value : values) {
            // Values are separated by the unit separator, and NUL stands for an absent one, so
            // ("a", null) and ("a", "") do not collide. No template value holds a control character.
            joined.append(value == null ? "\u0000" : value.toString()).append('\u001f');
        }
        return joined.toString();
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java runtime", e);
        }
    }
}
