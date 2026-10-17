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
 * Tenant settings of the accounting workspace (#2511; SPEC-accounting-workspace §7.1 "Seed"), read
 * from {@code accounting_configuration} as {@code BankRecPolicy} reads its keys: an absent row means
 * the key's default, so no row is seeded.
 *
 * <ul>
 *   <li>{@value #AP_DEFAULT_TERMS}: the payment terms a vendor bill gets when nothing else names
 *       them; default {@value #DEFAULT_AP_TERMS}, the form {@code BillingRuleRefResponse} documents.
 *   <li>{@value #CASH_SAFETY_CUSHION}: the cash the 30-day outlook keeps aside (S19); default unset.
 *   <li>The three aged-item thresholds of S19 (#2515): {@value #UNDEPOSITED_CASH_AGED_DAYS} (default 3, 1..30),
 *       {@value #VENDOR_COD_UNAPPLIED_AGED_DAYS} (14, 1..90) and {@value #DELIVERIES_NOT_BILLED_DAYS} (30, 7..120).
 *       A stored value outside its range reads as the default, with a warning.
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

    /** {@code accounting_configuration} key: days an undeposited register session may wait before it is aged. */
    public static final String UNDEPOSITED_CASH_AGED_DAYS = "UNDEPOSITED_CASH_AGED_DAYS";

    /** {@code accounting_configuration} key: days a vendor cash-on-delivery payment may sit unapplied. */
    public static final String VENDOR_COD_UNAPPLIED_AGED_DAYS = "VENDOR_COD_UNAPPLIED_AGED_DAYS";

    /** {@code accounting_configuration} key: days a goods receipt may wait for its approved bill. */
    public static final String DELIVERIES_NOT_BILLED_DAYS = "DELIVERIES_NOT_BILLED_DAYS";

    /** Default and range of {@link #UNDEPOSITED_CASH_AGED_DAYS}. */
    public static final int DEFAULT_UNDEPOSITED_CASH_AGED_DAYS = 3;

    public static final int MIN_UNDEPOSITED_CASH_AGED_DAYS = 1;
    public static final int MAX_UNDEPOSITED_CASH_AGED_DAYS = 30;

    /** Default and range of {@link #VENDOR_COD_UNAPPLIED_AGED_DAYS}. */
    public static final int DEFAULT_VENDOR_COD_UNAPPLIED_AGED_DAYS = 14;

    public static final int MIN_VENDOR_COD_UNAPPLIED_AGED_DAYS = 1;
    public static final int MAX_VENDOR_COD_UNAPPLIED_AGED_DAYS = 90;

    /** Default and range of {@link #DELIVERIES_NOT_BILLED_DAYS}. */
    public static final int DEFAULT_DELIVERIES_NOT_BILLED_DAYS = 30;

    public static final int MIN_DELIVERIES_NOT_BILLED_DAYS = 7;
    public static final int MAX_DELIVERIES_NOT_BILLED_DAYS = 120;

    private static final Pattern NET_TERMS = Pattern.compile("NET([1-9][0-9]{0,2})");

    private final AccountingConfigurationRepository configuration;

    /** The effective settings; the defaults stand in for absent rows. */
    public record Settings(
            @NonNull String apDefaultTerms,
            @Nullable BigDecimal cashSafetyCushion,
            int undepositedCashAgedDays,
            int vendorCodUnappliedAgedDays,
            int deliveriesNotBilledDays) {}

    /** All settings, read in one query. */
    public @NonNull Settings settings() {
        Map<String, String> stored = new HashMap<>();
        for (AccountingConfiguration row : configuration.findByConfigKeyIn(List.of(
                AP_DEFAULT_TERMS,
                CASH_SAFETY_CUSHION,
                UNDEPOSITED_CASH_AGED_DAYS,
                VENDOR_COD_UNAPPLIED_AGED_DAYS,
                DELIVERIES_NOT_BILLED_DAYS))) {
            stored.put(row.getConfigKey(), row.getConfigValue());
        }
        return new Settings(
                parseTerms(stored.get(AP_DEFAULT_TERMS)),
                parseCushion(stored.get(CASH_SAFETY_CUSHION)).orElse(null),
                parseDays(
                        UNDEPOSITED_CASH_AGED_DAYS,
                        stored.get(UNDEPOSITED_CASH_AGED_DAYS),
                        DEFAULT_UNDEPOSITED_CASH_AGED_DAYS,
                        MIN_UNDEPOSITED_CASH_AGED_DAYS,
                        MAX_UNDEPOSITED_CASH_AGED_DAYS),
                parseDays(
                        VENDOR_COD_UNAPPLIED_AGED_DAYS,
                        stored.get(VENDOR_COD_UNAPPLIED_AGED_DAYS),
                        DEFAULT_VENDOR_COD_UNAPPLIED_AGED_DAYS,
                        MIN_VENDOR_COD_UNAPPLIED_AGED_DAYS,
                        MAX_VENDOR_COD_UNAPPLIED_AGED_DAYS),
                parseDays(
                        DELIVERIES_NOT_BILLED_DAYS,
                        stored.get(DELIVERIES_NOT_BILLED_DAYS),
                        DEFAULT_DELIVERIES_NOT_BILLED_DAYS,
                        MIN_DELIVERIES_NOT_BILLED_DAYS,
                        MAX_DELIVERIES_NOT_BILLED_DAYS));
    }

    /** Days an undeposited session may wait before it ages (S19 work items); default 3, range 1..30. */
    public int undepositedCashAgedDays() {
        return settings().undepositedCashAgedDays();
    }

    /** Days a vendor cash-on-delivery payment may sit unapplied; default 14, range 1..90. */
    public int vendorCodUnappliedAgedDays() {
        return settings().vendorCodUnappliedAgedDays();
    }

    /** Days a goods receipt may wait for its approved bill; default 30, range 7..120. */
    public int deliveriesNotBilledDays() {
        return settings().deliveriesNotBilledDays();
    }

    /**
     * The effective day count of a stored value: the value when it is an integer within {@code min..max}, the default
     * when there is none, and the default with a warning for anything else, as {@link #parseTerms} treats a bad value.
     */
    static int parseDays(@NonNull String key, @Nullable String value, int defaultDays, int min, int max) {
        if (value == null || value.isBlank()) {
            return defaultDays;
        }
        try {
            int days = Integer.parseInt(value.trim());
            if (days >= min && days <= max) {
                return days;
            }
        } catch (NumberFormatException e) {
            // falls through to the warning
        }
        log.warn("{} holds '{}', not an integer from {} to {}; using {}", key, value, min, max, defaultDays);
        return defaultDays;
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
