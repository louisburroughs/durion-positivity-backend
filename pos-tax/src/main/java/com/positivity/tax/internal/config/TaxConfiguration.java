package com.positivity.tax.internal.config;

import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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
     * Dedicated RestClient for the Avalara AvaTax REST v2 adapter (story T6-external).
     * <p>
     * Authenticates with HTTP Basic ({@code accountId:licenseKey}). The license key is a
     * secret and is deliberately only ever placed into the pre-encoded {@code Authorization}
     * header value here — it is never logged.
     *
     * @return configured Avalara RestClient
     */
    @Bean
    RestClient avalaraRestClient() {
        TaxProperties.Avalara avalara = properties.getAvalara();
        String credentials = avalara.getAccountId() + ":" + avalara.getLicenseKey();
        String basicAuth = "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        return RestClient.builder()
                .baseUrl(avalara.getBaseUrl())
                .requestFactory(avalaraRequestFactory())
                .defaultHeader(HttpHeaders.AUTHORIZATION, basicAuth)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    private ClientHttpRequestFactory avalaraRequestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getAvalara().getConnectTimeout());
        factory.setReadTimeout(properties.getAvalara().getReadTimeout());
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
                // ADR-0021 (story T6): retry only transient failures (5xx / timeouts / connect
                // errors); never retry 4xx — a 400 is a deterministic data error.
                .retryOnException(new TaxRetryPredicate())
                .build();

        return Retry.of("taxService", config);
    }
}
