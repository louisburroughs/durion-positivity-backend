package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.VendorBillReview;
import com.positivity.accounting.internal.enums.VendorBillAction;
import com.positivity.accounting.internal.exception.VendorBillException;
import com.positivity.accounting.internal.security.AccountingPermissions;
import com.positivity.security.common.SecurityContextHelper;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.security.access.AccessDeniedException;

/**
 * Who may take which vendor-bill decision, and the justification rule (CAP:550 S12, #2509, and S13, #2510;
 * SPEC-accounting-workspace §4.3 "Permissions", AW4-AW6). The endpoints gate on any permission that could allow the
 * call; each action is checked again here, against the caller in the security context (ADR-0018).
 *
 * <p>{@link #mayTake} is the permission only. Approving, and {@code ACCEPT}, need either approve permission; voiding an
 * approved bill needs {@code accounting:ap:reject} and either approve permission. The bill's tier ({@link
 * ApApprovalPolicy#tier}) is then a bill-level guard ({@link #mayDecideTier}): an {@code OVER_LIMIT} bill needs {@code
 * accounting:ap:approve_over_limit} (S13 guard order, step 2). Voiding a goods-receipt bill no invoice will match
 * needs {@code accounting:ap:reject} alone (AW45). Entering the real due date needs {@code accounting:ap:approve}.
 */
final class VendorBillDecisions {

    /** The 10-character rule of every justification and reason (§4.3; the bank reconciliation precedent). */
    static final int MIN_JUSTIFICATION = 10;

    static final String SYSTEM = "SYSTEM";

    private static final String ROLE_PREFIX = "ROLE_";

    private VendorBillDecisions() {}

    /** Whether the caller holds the permission {@code action} needs. */
    static boolean mayTake(@NonNull VendorBillAction action) {
        return switch (action) {
            case SUBMIT_FOR_APPROVAL, CORRECT_EXCEPTION, SELECT_CANDIDATE ->
                has(AccountingPermissions.AP_APPROVE) || has(AccountingPermissions.AP_APPROVE_OVER_LIMIT);
            case APPROVE, ACCEPT_EXCEPTION ->
                has(AccountingPermissions.AP_APPROVE) || has(AccountingPermissions.AP_APPROVE_OVER_LIMIT);
            case REJECT, VOID_EXCEPTION, VOID_UNMATCHED -> has(AccountingPermissions.AP_REJECT);
            case VOID_APPROVED ->
                has(AccountingPermissions.AP_REJECT)
                        && (has(AccountingPermissions.AP_APPROVE) || has(AccountingPermissions.AP_APPROVE_OVER_LIMIT));
            case SET_DUE_DATE -> has(AccountingPermissions.AP_APPROVE);
        };
    }

    /**
     * Whether the caller may decide a bill of {@code tier} (S13 guard order, step 2): a {@code CLERK}-tier bill takes
     * either approve permission, which {@link #mayTake} already required; an {@code OVER_LIMIT} bill needs {@code
     * accounting:ap:approve_over_limit}.
     */
    static boolean mayDecideTier(VendorBillReview.@NonNull RequiredTier tier) {
        return tier == VendorBillReview.RequiredTier.CLERK || has(AccountingPermissions.AP_APPROVE_OVER_LIMIT);
    }

    /** The caller's name when there is one, else null: the reads' creator rule never throws for an unnamed caller. */
    static @Nullable String callerOrNull() {
        if (!SecurityContextHelper.isAuthenticated()) {
            return null;
        }
        String name = SecurityContextHelper.getCurrentUsernameOrDefault("").trim();
        return name.isEmpty() ? null : name;
    }

    /** The caller's roles (the {@code ROLE_} authorities, without the prefix), sorted, for an audit row. */
    static @NonNull List<String> callerRoles() {
        if (!SecurityContextHelper.isAuthenticated()) {
            return List.of();
        }
        return SecurityContextHelper.getAuthorities().stream()
                .filter(authority -> authority.startsWith(ROLE_PREFIX))
                .map(authority -> authority.substring(ROLE_PREFIX.length()))
                .sorted()
                .toList();
    }

    /** Refuses with 403 {@code FORBIDDEN} when the caller does not hold the permission {@code action} needs. */
    static void require(@NonNull VendorBillAction action) {
        if (!mayTake(action)) {
            throw new AccessDeniedException("The caller may not " + action + " vendor bills");
        }
    }

    static boolean justificationRequired(@NonNull VendorBillAction action) {
        return switch (action) {
            case APPROVE, SELECT_CANDIDATE, SET_DUE_DATE -> false;
            case SUBMIT_FOR_APPROVAL,
                    REJECT,
                    ACCEPT_EXCEPTION,
                    CORRECT_EXCEPTION,
                    VOID_EXCEPTION,
                    VOID_APPROVED,
                    VOID_UNMATCHED -> true;
        };
    }

    /**
     * A justification the command requires: absent, blank or under 10 characters is one condition, 400 {@code
     * JUSTIFICATION_REQUIRED}. Returns it trimmed.
     */
    static @NonNull String required(@Nullable String value, @NonNull String field) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.length() < MIN_JUSTIFICATION) {
            throw new VendorBillException(
                    VendorBillException.Code.JUSTIFICATION_REQUIRED,
                    field + " of at least " + MIN_JUSTIFICATION + " characters is required");
        }
        return trimmed;
    }

    /** An optional justification: absent or blank is none; one that is given is held to the 10-character rule. */
    static @Nullable String optional(@Nullable String value, @NonNull String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return required(value, field);
    }

    /**
     * The caller, from the security context (ADR-0018); never a body field. A decision is a person's: a caller
     * without a name is refused with 403 rather than recorded as {@value #SYSTEM}, which only a HIGH match writes.
     */
    static @NonNull String actor() {
        String name = SecurityContextHelper.isAuthenticated()
                ? SecurityContextHelper.getCurrentUsernameOrDefault("").trim()
                : "";
        if (name.isEmpty() || SYSTEM.equalsIgnoreCase(name)) {
            throw new AccessDeniedException("A vendor-bill decision needs a named caller");
        }
        return name;
    }

    private static boolean has(String authority) {
        return SecurityContextHelper.isAuthenticated() && SecurityContextHelper.hasAuthority(authority);
    }
}
