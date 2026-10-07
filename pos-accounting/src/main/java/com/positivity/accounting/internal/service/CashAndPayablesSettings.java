package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.entity.AccountingConfiguration;
import com.positivity.accounting.internal.repository.AccountingConfigurationRepository;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * <p>Only defaults are declared here. Who writes either key, the cushion's meaning (OI-6) and opening
 * bank balances (OI-10) are open questions of the specification, so there is no write contract.
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

    static @NonNull String parseTerms(@Nullable String value) {
        return value == null || value.isBlank() ? DEFAULT_AP_TERMS : value.trim();
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
