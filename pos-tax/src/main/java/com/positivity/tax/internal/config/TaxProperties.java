package com.positivity.tax.internal.config;

import java.math.BigDecimal;
import java.util.HashMap;
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
