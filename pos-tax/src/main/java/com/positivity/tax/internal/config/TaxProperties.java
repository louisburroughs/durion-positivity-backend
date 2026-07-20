package com.positivity.tax.internal.config;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
         * Postal code to jurisdiction mapping for test mode.
         * <p>
         * Example: 90001={state=CA, county=LA}
         */
        private Map<String, Map<String, String>> postalCodeMapping = new HashMap<>();

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
