package com.positivity.tax.internal.config;

import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Configuration for the tax service.
 */
@Configuration
public class TaxConfiguration {

    private final TaxProperties properties;

    public TaxConfiguration(TaxProperties properties) {
        this.properties = properties;
    }

    /**
     * System UTC clock used for effective-date resolution and {@code calculatedAt}
     * timestamps. Declared {@link ConditionalOnMissingBean} so a shared library or test
     * can supply a fixed clock instead.
     *
     * @return the system UTC clock
     */
    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * Creates a RestClient for external tax service API calls.
     *
     * @return configured RestClient
     */
    @Bean
    RestClient taxServiceRestClient() {
        return RestClient.builder()
                .baseUrl(properties.getExternalService().getBaseUrl())
                .requestFactory(clientHttpRequestFactory())
                .defaultHeader("X-API-Key", properties.getExternalService().getApiKey())
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    /**
     * Creates a ClientHttpRequestFactory with configured timeouts.
     *
     * @return configured request factory
     */
    private ClientHttpRequestFactory clientHttpRequestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getExternalService().getConnectTimeout());
        factory.setReadTimeout(properties.getExternalService().getReadTimeout());
        return factory;
    }

    /**
     * Creates a Resilience4J Retry configuration for external service calls.
     *
     * @return configured Retry instance
     */
    @Bean
    Retry taxServiceRetry() {
        TaxProperties.Retry retryProps = properties.getRetry();

        RetryConfig config = RetryConfig.custom()
                .maxAttempts(retryProps.getMaxAttempts())
                .intervalFunction(attempt ->
                        (long) (retryProps.getInitialBackoff() * Math.pow(retryProps.getMultiplier(), attempt - 1)))
                .build();

        return Retry.of("taxService", config);
    }
}
