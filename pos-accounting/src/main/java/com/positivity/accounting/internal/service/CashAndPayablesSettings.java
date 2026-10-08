package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.entity.AccountingConfiguration;
import com.positivity.accounting.internal.repository.AccountingConfigurationRepository;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Two tenant settings of the accounting workspace (#2511; SPEC-accounting-workspace §7.1 "Seed"), read
 * from {@code accounting_configuration} as {@code BankRecPolicy} reads its keys: an absent row means
 * the key's default, so no row is seeded.
 *
 * <ul>
 *   <li>{@value #AP_DEFAULT_TERMS}: the payment terms a vendor bill gets when nothing else names
 *       them; default {@value #DEFAULT_AP_TERMS}, the form {@code BillingRuleRefResponse} documents.
 *   <li>{@value #CASH_SAFETY_CUSHION}: the cash the 30-day outlook keeps aside (S19); default unset.
 * </ul>
 *
 * <p>{@value #AP_DEFAULT_TERMS} is written through the AP approval policy (CAP:550 S13, #2510; AW33,
 * {@link ApApprovalPolicy}) in pos-supplier's vocabulary: {@code DUE_ON_RECEIPT} or {@code NET<n>}, n an
 * integer from 1 to 120, upper case, no spaces. Any other stored value reads as {@value #DEFAULT_AP_TERMS} and
 * logs a warning, so the outlook and the policy read one effective value. Who writes the cushion, and its
 * meaning (OI-6), are still open questions of the specification.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CashAndPayablesSettings {

    /** {@code accounting_configuration} key: default vendor payment terms. */
    public static final String AP_DEFAULT_TERMS = "AP_DEFAULT_TERMS";

    /** {@code accounting_configuration} key: the cash safety cushion, in functional currency. */
    public static final String CASH_SAFETY_CUSHION = "CASH_SAFETY_CUSHION";

    /** The default of {@link #AP_DEFAULT_TERMS}. */
    public static final String DEFAULT_AP_TERMS = "NET30";

    /** Terms due on receipt, the one value of the vocabulary that is not {@code NET<n>}. */
    public static final String DUE_ON_RECEIPT = "DUE_ON_RECEIPT";

    /** The longest {@code NET<n>} the vocabulary allows. */
    public static final int MAX_NET_DAYS = 120;

    private static final Pattern NET_TERMS = Pattern.compile("NET([1-9][0-9]{0,2})");

    private final AccountingConfigurationRepository configuration;

    /** The effective settings; the defaults stand in for absent rows. */
    public record Settings(
            @NonNull String apDefaultTerms, @Nullable BigDecimal cashSafetyCushion) {}

    /** Both settings, read in one query. */
    public @NonNull Settings settings() {
        Map<String, String> stored = new HashMap<>();
        for (AccountingConfiguration row :
                configuration.findByConfigKeyIn(List.of(AP_DEFAULT_TERMS, CASH_SAFETY_CUSHION))) {
            stored.put(row.getConfigKey(), row.getConfigValue());
        }
        return new Settings(
                parseTerms(stored.get(AP_DEFAULT_TERMS)),
                parseCushion(stored.get(CASH_SAFETY_CUSHION)).orElse(null));
    }

    /** The tenant's default vendor payment terms. */
    public @NonNull String apDefaultTerms() {
        return settings().apDefaultTerms();
    }

    /** The tenant's cash safety cushion; empty while unset. */
    public @NonNull Optional<BigDecimal> cashSafetyCushion() {
        return Optional.ofNullable(settings().cashSafetyCushion());
    }

    /**
     * The effective terms of a stored value: the value itself when it is in the vocabulary ({@link #isTerms}),
     * {@value #DEFAULT_AP_TERMS} when there is none, and {@value #DEFAULT_AP_TERMS} with a warning for anything else
     * (ruling 9 of #2510): {@code NET 30}, {@code net30} and pos-invoice's {@code NET_30} are not terms here.
     */
    public static @NonNull String parseTerms(@Nullable String value) {
        if (value == null) {
            return DEFAULT_AP_TERMS;
        }
        if (isTerms(value)) {
            return value;
        }
        log.warn(
                "{} holds '{}', not DUE_ON_RECEIPT or NET1..NET120; using {}",
                AP_DEFAULT_TERMS,
                value,
                DEFAULT_AP_TERMS);
        return DEFAULT_AP_TERMS;
    }

    /** Whether {@code value} is in the vocabulary: {@code DUE_ON_RECEIPT}, or {@code NET1} to {@code NET120}. */
    public static boolean isTerms(@Nullable String value) {
        if (value == null) {
            return false;
        }
        if (DUE_ON_RECEIPT.equals(value)) {
            return true;
        }
        Matcher net = NET_TERMS.matcher(value);
        return net.matches() && Integer.parseInt(net.group(1)) <= MAX_NET_DAYS;
    }

    static @NonNull Optional<BigDecimal> parseCushion(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            BigDecimal cushion = new BigDecimal(value.trim());
            return cushion.signum() < 0 ? Optional.empty() : Optional.of(cushion);
        } catch (NumberFormatException e) {
            log.warn("{} holds {}, which is not an amount; treated as unset", CASH_SAFETY_CUSHION, value);
            return Optional.empty();
        }
    }
}
