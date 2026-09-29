package com.positivity.accounting.internal.bankrec.service;

import com.positivity.accounting.internal.bankrec.enums.BankRecClosePolicy;
import com.positivity.accounting.internal.bankrec.enums.BankRecCloseScope;
import com.positivity.accounting.internal.entity.AccountingConfiguration;
import com.positivity.accounting.internal.repository.AccountingConfigurationRepository;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The tenant's bank reconciliation policy (SPEC §4.7, §4.9, §5.2, §6.4; stories S4 #2303, S5 #2304, S6 #2305):
 * the five {@code accounting_configuration} keys, their defaults when a key has no row, and the one parser per
 * key. Story S6's {@code PUT /v1/accounting/periods/bank-reconciliation-policy} writes them (through {@code
 * AccountingConfigurationService}); the core reads them here.
 *
 * <p>A stored value the parser cannot read falls back to the key's default — for the threshold that is unset,
 * so the stricter rule applies.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BankRecPolicy {

    /** {@code accounting_configuration} key: the close policy (D4); absent means {@code REQUIRED_WITH_EXCEPTION}. */
    public static final String CLOSE_POLICY = "BANK_REC_CLOSE_POLICY";

    /** {@code accounting_configuration} key: the close scope (D5); absent means {@code BANK_CASH_SUBTYPE}. */
    public static final String CLOSE_SCOPE = "BANK_REC_CLOSE_SCOPE";

    /** {@code accounting_configuration} key: the coverage lag in days (§5.2, §5.7); absent means 0. */
    public static final String CLOSE_COVERAGE_LAG_DAYS = "BANK_REC_CLOSE_COVERAGE_LAG_DAYS";

    /** {@code accounting_configuration} key: the {@code OTHER} adjustment amount above which approve is needed. */
    public static final String OTHER_APPROVAL_THRESHOLD = "BANK_REC_OTHER_APPROVAL_THRESHOLD";

    /** {@code accounting_configuration} key: whether the submitter may approve (D3); absent means false. */
    public static final String ALLOW_SELF_APPROVAL = "BANK_REC_ALLOW_SELF_APPROVAL";

    /** Every policy key, in the order the policy body lists them (§5.2). */
    public static final List<String> KEYS =
            List.of(CLOSE_POLICY, CLOSE_SCOPE, CLOSE_COVERAGE_LAG_DAYS, ALLOW_SELF_APPROVAL, OTHER_APPROVAL_THRESHOLD);

    public static final BankRecClosePolicy DEFAULT_CLOSE_POLICY = BankRecClosePolicy.REQUIRED_WITH_EXCEPTION;
    public static final BankRecCloseScope DEFAULT_CLOSE_SCOPE = BankRecCloseScope.BANK_CASH_SUBTYPE;
    public static final int DEFAULT_COVERAGE_LAG_DAYS = 0;

    private final AccountingConfigurationRepository configuration;

    /** The five effective settings (§5.2); the defaults stand in for a key with no row. */
    public record Settings(
            @NonNull BankRecClosePolicy closePolicy,
            @NonNull BankRecCloseScope closeScope,
            int closeCoverageLagDays,
            boolean allowSelfApproval,
            @Nullable BigDecimal otherApprovalThreshold) {}

    /**
     * The tenant's effective policy (§5.2), read as one snapshot: the five keys come from a single query, so a
     * concurrent PUT — which replaces them together — is seen whole or not at all, never as a mix of old and new
     * values.
     */
    public @NonNull Settings settings() {
        Map<String, String> stored = new HashMap<>();
        for (AccountingConfiguration row : configuration.findByConfigKeyIn(KEYS)) {
            stored.put(row.getConfigKey(), row.getConfigValue());
        }
        return new Settings(
                parseClosePolicy(stored.get(CLOSE_POLICY)),
                parseCloseScope(stored.get(CLOSE_SCOPE)),
                parseLagDays(stored.get(CLOSE_COVERAGE_LAG_DAYS)),
                parseAllowSelfApproval(stored.get(ALLOW_SELF_APPROVAL)),
                parseThreshold(stored.get(OTHER_APPROVAL_THRESHOLD)).orElse(null));
    }

    /** The tenant's {@code BANK_REC_CLOSE_POLICY} (§5.2, D4). */
    public @NonNull BankRecClosePolicy closePolicy() {
        return parseClosePolicy(value(CLOSE_POLICY));
    }

    /** The tenant's {@code BANK_REC_CLOSE_SCOPE} (§5.2, D5). */
    public @NonNull BankRecCloseScope closeScope() {
        return parseCloseScope(value(CLOSE_SCOPE));
    }

    /** The tenant's {@code BANK_REC_CLOSE_COVERAGE_LAG_DAYS} (§5.2, §5.7). */
    public int coverageLagDays() {
        return parseLagDays(value(CLOSE_COVERAGE_LAG_DAYS));
    }

    /**
     * The tenant's {@code BANK_REC_ALLOW_SELF_APPROVAL} (§4.9, D3): true only for a row holding {@code true}
     * (any case); no row, or any other value, keeps preparer and approver separate.
     */
    public boolean allowSelfApproval() {
        return parseAllowSelfApproval(value(ALLOW_SELF_APPROVAL));
    }

    /**
     * The tenant's {@code BANK_REC_OTHER_APPROVAL_THRESHOLD}; empty when no row exists (unset). A value that is
     * not a non-negative amount reads as unset, so the stricter rule applies.
     */
    public @NonNull Optional<BigDecimal> otherApprovalThreshold() {
        return parseThreshold(value(OTHER_APPROVAL_THRESHOLD));
    }

    private @Nullable String value(String key) {
        return configuration
                .findByConfigKey(key)
                .map(AccountingConfiguration::getConfigValue)
                .orElse(null);
    }

    public static @NonNull BankRecClosePolicy parseClosePolicy(@Nullable String value) {
        return parseEnum(BankRecClosePolicy.class, CLOSE_POLICY, value, DEFAULT_CLOSE_POLICY);
    }

    public static @NonNull BankRecCloseScope parseCloseScope(@Nullable String value) {
        return parseEnum(BankRecCloseScope.class, CLOSE_SCOPE, value, DEFAULT_CLOSE_SCOPE);
    }

    public static int parseLagDays(@Nullable String value) {
        if (value == null) {
            return DEFAULT_COVERAGE_LAG_DAYS;
        }
        try {
            int days = Integer.parseInt(value.trim());
            if (days >= 0) {
                return days;
            }
        } catch (NumberFormatException e) {
            // falls through to the default below
        }
        log.warn("{} holds '{}', not a day count; using {}", CLOSE_COVERAGE_LAG_DAYS, value, DEFAULT_COVERAGE_LAG_DAYS);
        return DEFAULT_COVERAGE_LAG_DAYS;
    }

    public static boolean parseAllowSelfApproval(@Nullable String value) {
        return value != null && "true".equalsIgnoreCase(value.trim());
    }

    public static @NonNull Optional<BigDecimal> parseThreshold(@Nullable String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            BigDecimal amount = new BigDecimal(value.trim());
            return amount.signum() >= 0 ? Optional.of(amount) : Optional.empty();
        } catch (NumberFormatException e) {
            log.warn("{} holds '{}', not an amount; treating it as unset", OTHER_APPROVAL_THRESHOLD, value);
            return Optional.empty();
        }
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String key, @Nullable String value, E fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            log.warn("{} holds '{}', not a {}; using {}", key, value, type.getSimpleName(), fallback);
            return fallback;
        }
    }
}
