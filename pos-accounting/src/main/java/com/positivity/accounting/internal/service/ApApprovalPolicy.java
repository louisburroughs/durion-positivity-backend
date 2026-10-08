package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.bankrec.service.FunctionalCurrency;
import com.positivity.accounting.internal.dto.VendorBillReview;
import com.positivity.accounting.internal.entity.AccountingConfiguration;
import com.positivity.accounting.internal.repository.AccountingConfigurationRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The tenant's AP approval policy (CAP:550 S13, #2510; SPEC-accounting-workspace §4.3, §5.5; AW4-AW6, AW33): five
 * {@code accounting_configuration} keys, their defaults when a key has no row, and the tier and automatic-approval
 * rules they set. {@code PUT /v1/accounting/ap-approval-policy} writes them ({@link ApApprovalPolicyService}); every
 * vendor-bill decision, read and payment reads them here, as one snapshot ({@code BankRecPolicy} is the precedent).
 *
 * <p>A stored value that cannot be read falls back to its default, which is the stricter rule (a limit of 0, a
 * switch off, {@code NET30}), and logs a warning.
 *
 * <p><b>Tier.</b> A bill is {@code CLERK}-tier only when the clerk limit is above 0 and the absolute value of its
 * stored {@code totalAmount} (the billed gross: an EDI bill's stated gross, a goods-receipt bill's billed total) is
 * at most the limit; every other bill is {@code OVER_LIMIT}. The absolute value makes a credit note compare like an
 * invoice, and a {@code difference} (AW47) never changes the tier. The tier is derived on every read and decision,
 * never stored, so a changed limit re-routes waiting bills at once.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApApprovalPolicy {

    /** {@code accounting_configuration} key: the clerk approval limit; absent means 0 (every bill over it). */
    public static final String CLERK_APPROVAL_LIMIT = "AP_CLERK_APPROVAL_LIMIT";

    /** {@code accounting_configuration} key: the automatic approval limit; absent means 0 (off). */
    public static final String AUTO_APPROVAL_LIMIT = "AP_AUTO_APPROVAL_LIMIT";

    /** {@code accounting_configuration} key: whether a bill's creator may approve it; absent means false. */
    public static final String ALLOW_CREATOR_APPROVAL = "AP_ALLOW_CREATOR_APPROVAL";

    /** {@code accounting_configuration} key: whether a bill's approver may pay it; absent means false. */
    public static final String ALLOW_APPROVER_PAYMENT = "AP_ALLOW_APPROVER_PAYMENT";

    /** {@code accounting_configuration} key: the default AP terms, S15's key (AW33). */
    public static final String DEFAULT_TERMS = CashAndPayablesSettings.AP_DEFAULT_TERMS;

    /** Every policy key, in the order the policy body lists them (§5.5). */
    public static final List<String> KEYS = List.of(
            CLERK_APPROVAL_LIMIT, AUTO_APPROVAL_LIMIT, ALLOW_CREATOR_APPROVAL, ALLOW_APPROVER_PAYMENT, DEFAULT_TERMS);

    private final AccountingConfigurationRepository configuration;
    private final FunctionalCurrency functionalCurrency;

    /** The five effective settings; the defaults stand in for a key with no row or an unreadable value. */
    public record Settings(
            @NonNull BigDecimal clerkApprovalLimit,
            @NonNull BigDecimal autoApprovalLimit,
            boolean allowCreatorApproval,
            boolean allowApproverPayment,
            @NonNull String defaultTerms) {

        /** The tier a bill of {@code totalAmount} needs under this policy. */
        public VendorBillReview.@NonNull RequiredTier tier(@Nullable BigDecimal totalAmount) {
            return ApApprovalPolicy.tier(totalAmount, clerkApprovalLimit);
        }

        /** The limit automatic approval applies: the smaller of the automatic and clerk limits. */
        public @NonNull BigDecimal automaticLimitApplied() {
            return autoApprovalLimit.min(clerkApprovalLimit);
        }

        /**
         * Whether a bill of {@code totalAmount} may be approved automatically (item 9): the automatic limit is above 0
         * and the absolute total is at most min(automatic limit, clerk limit).
         */
        public boolean automaticallyApprovable(@Nullable BigDecimal totalAmount) {
            return autoApprovalLimit.signum() > 0
                    && totalAmount != null
                    && totalAmount.abs().compareTo(automaticLimitApplied()) <= 0;
        }
    }

    /**
     * The tier of a bill of {@code totalAmount} against {@code clerkLimit}: {@code CLERK} only when the limit is above
     * 0 and the absolute total is at most the limit, else {@code OVER_LIMIT}. A bill without a total is {@code
     * OVER_LIMIT}.
     */
    public static VendorBillReview.@NonNull RequiredTier tier(
            @Nullable BigDecimal totalAmount, @NonNull BigDecimal clerkLimit) {
        return clerkLimit.signum() > 0
                        && totalAmount != null
                        && totalAmount.abs().compareTo(clerkLimit) <= 0
                ? VendorBillReview.RequiredTier.CLERK
                : VendorBillReview.RequiredTier.OVER_LIMIT;
    }

    /** The effective policy, read as one snapshot (one query), for a read. */
    public @NonNull Settings settings() {
        return parse(configuration.findByConfigKeyIn(KEYS));
    }

    /**
     * The effective policy for a decision (approve, {@code ACCEPT}, the void of an approved bill, automatic approval,
     * a payment): the same snapshot, its rows share-locked until the decision's transaction ends, so a policy PUT
     * racing the decision is applied wholly before it or wholly after it.
     */
    public @NonNull Settings forDecision() {
        return parse(configuration.findWithShareLockByConfigKeyIn(KEYS));
    }

    private Settings parse(List<AccountingConfiguration> rows) {
        Map<String, String> stored = new HashMap<>();
        for (AccountingConfiguration row : rows) {
            stored.put(row.getConfigKey(), row.getConfigValue());
        }
        return new Settings(
                scaled(parseAmount(CLERK_APPROVAL_LIMIT, stored.get(CLERK_APPROVAL_LIMIT))),
                scaled(parseAmount(AUTO_APPROVAL_LIMIT, stored.get(AUTO_APPROVAL_LIMIT))),
                parseSwitch(ALLOW_CREATOR_APPROVAL, stored.get(ALLOW_CREATOR_APPROVAL)),
                parseSwitch(ALLOW_APPROVER_PAYMENT, stored.get(ALLOW_APPROVER_PAYMENT)),
                CashAndPayablesSettings.parseTerms(stored.get(DEFAULT_TERMS)));
    }

    /**
     * An amount in the functional currency's minor unit, as the policy stores and returns it (ADR-0067); a stored value
     * finer than that is read rounded down, toward the stricter limit (#2622 review LOW-5).
     */
    public @NonNull BigDecimal scaled(@NonNull BigDecimal amount) {
        return amount.setScale(functionalCurrency.fractionDigits(), RoundingMode.DOWN);
    }

    /** A stored limit: a non-negative amount; absent is 0, anything else is 0 with a warning. */
    static @NonNull BigDecimal parseAmount(@NonNull String key, @Nullable String value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        try {
            BigDecimal amount = new BigDecimal(value.trim());
            if (amount.signum() >= 0) {
                return amount;
            }
        } catch (NumberFormatException e) {
            // falls through to the default below
        }
        log.warn("{} holds '{}', not an amount of at least 0; using 0", key, value);
        return BigDecimal.ZERO;
    }

    /** A stored switch: {@code true} or {@code false}, any case; absent is false, anything else false with a warning. */
    static boolean parseSwitch(@NonNull String key, @Nullable String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.trim();
        if ("true".equalsIgnoreCase(trimmed)) {
            return true;
        }
        if (!"false".equalsIgnoreCase(trimmed)) {
            log.warn("{} holds '{}', not true or false; using false", key, value);
        }
        return false;
    }
}
