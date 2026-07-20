package com.positivity.tax.internal.config;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for the tax service.
 */
@Data
@Component
@ConfigurationProperties(prefix = "pos.tax")
public class TaxProperties {

    /**
     * Test mode configuration.
     */
    private TestMode testMode = new TestMode();

    /**
     * External tax service configuration.
     */
    private ExternalService externalService = new ExternalService();

    /**
     * Retry configuration for external service calls.
     */
    private Retry retry = new Retry();

    @Data
    public static class TestMode {
        /**
         * Whether test mode is enabled.
         * <p>
         * When true, tax calculations use internal test logic instead of external service.
         */
        private boolean enabled = true;

        /**
         * Default tax rates by jurisdiction type.
         * <p>
         * Example: STATE=0.0725, COUNTY=0.01, CITY=0.0025
         */
        private Map<String, BigDecimal> defaultRates = new HashMap<>();

        /**
         * Effective-dated override schedule for test-mode rates.
         * <p>
         * Ordered list of {@link RateScheduleEntry}. When resolving rates for a
         * transaction, the entry with the greatest {@code effectiveFrom} that is not
         * after the transaction date is selected. When empty (the default) or when no
         * entry is effective on or before the transaction date, {@link #defaultRates}
         * is used, preserving prior behavior.
         * <p>
         * Never {@code null}; defaults to an empty list.
         */
        private List<RateScheduleEntry> rateSchedule = new ArrayList<>();

        /**
         * Address-driven, effective-dated jurisdiction rate rules (story T7).
         * <p>
         * Each rule matches on {@code destinationAddress} facets (state, city,
         * postal-code prefix) and carries its own rates and optional per-category
         * exemptions. For a given transaction the rule is selected from those that match
         * the address and whose {@code effectiveFrom} is not after the transaction date;
         * ties are broken by greatest {@code effectiveFrom}, then most-specific match, then
         * configured order. When no rule matches, resolution falls back to
         * {@link #rateSchedule} then {@link #defaultRates}, preserving prior behavior.
         * <p>
         * Never {@code null}; defaults to an empty list.
         */
        private List<JurisdictionRule> jurisdictions = new ArrayList<>();
    }

    /**
     * An address-driven, effective-dated jurisdiction rate rule (story T7).
     */
    @Data
    public static class JurisdictionRule {
        /**
         * Address facets this rule matches on. All populated facets must match.
         */
        private JurisdictionMatch match = new JurisdictionMatch();

        /**
         * Tax rates by jurisdiction type code for this rule (e.g. STATE=0.0625).
         */
        private Map<String, BigDecimal> rates = new HashMap<>();

        /**
         * Inclusive date on which this rule becomes effective. When {@code null} the rule is
         * treated as always effective.
         */
        private LocalDate effectiveFrom;

        /**
         * Tax categories that are exempt within this rule (story T7 category override).
         * <p>
         * A line whose {@code taxCategory} is listed here is treated as a (rule-based)
         * exempt line: it emits zero-rate jurisdiction rows and is excluded from the taxable
         * base — the minimal config-only sliver of a fiscal position (e.g. {@code LABOR}
         * exempt in some states). Matching is case-insensitive.
         */
        private Set<String> exemptCategories = new LinkedHashSet<>();
    }

    /**
     * Address facets a {@link JurisdictionRule} matches on. A {@code null}/blank facet is a
     * wildcard; a populated facet must equal (case-insensitively) the corresponding request
     * value, except {@code postalCodePrefix} which matches by prefix.
     */
    @Data
    public static class JurisdictionMatch {
        /** State/region subdivision code (e.g. CA, TX). */
        private String stateCode;

        /** City/locality name. */
        private String city;

        /** Postal-code prefix (matches when the request postal code starts with this value). */
        private String postalCodePrefix;
    }

    /**
     * A single effective-dated set of test-mode tax rates.
     * <p>
     * {@code effectiveFrom} is the inclusive start date on which {@code rates}
     * become applicable; an entry applies to any transaction date on or after it,
     * until a later entry supersedes it.
     */
    @Data
    public static class RateScheduleEntry {
        /**
         * Inclusive date on which this rate set becomes effective.
         */
        private LocalDate effectiveFrom;

        /**
         * Tax rates by jurisdiction type code for this effective period.
         * <p>
         * Example: STATE=0.0725, COUNTY=0.01, CITY=0.0025
         */
        private Map<String, BigDecimal> rates = new HashMap<>();
    }

    @Data
    public static class ExternalService {
        /**
         * Base URL for the external tax service API.
         */
        private String baseUrl = "https://api.taxservice.example.com";

        /**
         * API key for authentication.
         */
        private String apiKey;

        /**
         * Connection timeout in milliseconds.
         */
        private int connectTimeout = 5000;

        /**
         * Read timeout in milliseconds.
         */
        private int readTimeout = 10000;
    }

    @Data
    public static class Retry {
        /**
         * Maximum number of retry attempts.
         */
        private int maxAttempts = 3;

        /**
         * Initial backoff duration in milliseconds.
         */
        private long initialBackoff = 500;

        /**
         * Backoff multiplier for exponential backoff.
         */
        private double multiplier = 2.0;
    }
}
