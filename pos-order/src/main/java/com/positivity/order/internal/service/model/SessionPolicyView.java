package com.positivity.order.internal.service.model;

import com.positivity.order.internal.entity.SessionPolicyType;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * The tenant's drawer policy in effect (CAP:550 S16, #2512; SPEC-accounting-workspace §4.6 "Drawer
 * limits", AW19), the defaults when the tenant never changed it. Amounts are in the functional currency.
 *
 * @param version the stored policy's version, or null while the defaults apply
 * @param pettyExpenseAllowed whether cashiers may record petty expenses
 * @param pettyExpenseLimit the cashier limit on a session's running total of petty expenses
 * @param vendorCodAllowed whether cashiers may pay vendors cash on delivery
 * @param vendorCodLimit the cashier limit on a session's running total of vendor cash on delivery
 * @param overShortTolerance the over/short above which a close needs {@code order:session:approve_variance}
 */
public record SessionPolicyView(
        @Nullable Long version,
        boolean pettyExpenseAllowed,
        @Nullable BigDecimal pettyExpenseLimit,
        boolean vendorCodAllowed,
        @Nullable BigDecimal vendorCodLimit,
        @NonNull BigDecimal overShortTolerance) {

    /** Whether a movement of {@code type} may be recorded now; a bank drop and a float change always may. */
    public boolean allowed(@NonNull SessionPolicyType type) {
        return switch (type) {
            case PETTY_EXPENSE -> pettyExpenseAllowed;
            case VENDOR_COD -> vendorCodAllowed;
            case BANK_DROP, FLOAT_CHANGE -> true;
        };
    }

    /** The cashier limit on the session's running total of {@code type}, or null when there is none. */
    public @Nullable BigDecimal cashierLimit(@NonNull SessionPolicyType type) {
        return switch (type) {
            case PETTY_EXPENSE -> pettyExpenseLimit;
            case VENDOR_COD -> vendorCodLimit;
            case BANK_DROP, FLOAT_CHANGE -> null;
        };
    }

    /** Whether every movement of {@code type} needs a manager, whatever its amount: only a float change. */
    public boolean alwaysNeedsManager(@NonNull SessionPolicyType type) {
        return type == SessionPolicyType.FLOAT_CHANGE;
    }

    /** One row per movement type, in the order of {@link SessionPolicyType}, for the policy read. */
    public @NonNull List<TypeRow> rows() {
        return Arrays.stream(SessionPolicyType.values())
                .map(type -> new TypeRow(
                        type.name(), allowed(type), cashierLimit(type), alwaysNeedsManager(type), type.configurable()))
                .toList();
    }

    /** One movement type's policy as the read shows it; {@code editable} is false for the fixed rows. */
    public record TypeRow(
            @NonNull String type,
            boolean allowed,
            @Nullable BigDecimal cashierLimit,
            boolean alwaysNeedsManager,
            boolean editable) {}
}
