package com.positivity.domainevents.accounting;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Payload for {@code accounting.petty-expense-category.changed} v1 on {@code accounting.events.v1}
 * (SPEC-accounting-workspace §4.6 "Petty-expense categories"; AW18; story S15, #2511).
 *
 * <p>Published by pos-accounting through its transactional outbox after every category command
 * (create, relabel, deactivate, account change) and when the tenant template creates a category;
 * republished with the current state at every start (ADR-0044 §4). The envelope's aggregate is the
 * category row and its version. pos-order keeps the cashier's picker from it (S16, ADR-0044 R3); S32
 * adds the tax-recovery fields in a later version.
 *
 * @param code the permanent category code, e.g. {@code SHOP_SUPPLIES}
 * @param label the plain label the cashier sees
 * @param examples what belongs in the category, or null
 * @param status whether new movements may use it
 * @param accountCode the account the category posts to today, or null when it has none
 * @param accountName that account's name, or null when it has none
 */
public record PettyExpenseCategoryChangedV1(
        @NonNull String code,
        @NonNull String label,
        @Nullable String examples,
        @NonNull Status status,
        @Nullable String accountCode,
        @Nullable String accountName) {

    public static final String EVENT_TYPE = "accounting.petty-expense-category.changed";
    public static final int SCHEMA_VERSION = 1;

    /** A category's status; {@code INACTIVE} is terminal. */
    public enum Status {
        ACTIVE,
        INACTIVE
    }

    public PettyExpenseCategoryChangedV1 {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code must not be blank");
        }
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("label must not be blank");
        }
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
    }
}
