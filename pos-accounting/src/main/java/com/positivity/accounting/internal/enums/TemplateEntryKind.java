package com.positivity.accounting.internal.enums;

/**
 * The kinds of entry the accounting tenant template holds (#2526), in the order the applier walks
 * them: an entry may only refer to kinds declared before its own.
 *
 * <p>The name is the first part of an entry key ({@code ACCOUNT:1000}) and of the template row's
 * id expression in {@code R__seed_reference_accounting.sql}; renaming a constant orphans every
 * recorded entry of that kind.
 */
public enum TemplateEntryKind {
    /** A GL account, matched in a tenant by account code. */
    ACCOUNT,
    /** A posting category, matched by name. */
    CATEGORY,
    /** A mapping key, matched by category and key name. */
    MAPPING_KEY,
    /** The GL mapping of a mapping key, matched by any mapping of that key without dimensions. */
    GL_MAPPING,
    /** A default GL mapping, matched by event type. */
    DEFAULT_GL_MAPPING,
    /** An account's line on a statement, matched by statement type and account. */
    STATEMENT_LINE,
    /**
     * A petty-expense category (#2511), matched by code; applied after its mapping key and GL mapping.
     */
    PETTY_EXPENSE_CATEGORY
}
