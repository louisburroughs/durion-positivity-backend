package com.positivity.order.internal.config;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * REST client for pos-tax's plausibility check and evidence-rules read (CAP:550 S32d). pos-tax is internal-only and
 * not on Eureka (#641), so it is reached on its fixed base URL. Unlike {@link TaxClientConfig}'s client, this one
 * has explicit connect and read timeouts ({@link TaxCheckSettings}), so the 503's {@code Retry-After} is never shorter
 * than the wait already spent.
 */
@Configuration
public class TaxCheckClientConfig {

    @Bean
    public TaxCheckSettings taxCheckSettings(
            @Value("${pos.order.tax-check.connect-timeout-ms:1000}") long connectTimeoutMs,
            @Value("${pos.order.tax-check.read-timeout-ms:3000}") long readTimeoutMs,
            @Value("${" + TaxCheckSettings.RETRY_AFTER_PROPERTY + ":5}") long retryAfterSeconds) {
        return new TaxCheckSettings(
                Duration.ofMillis(connectTimeoutMs), Duration.ofMillis(readTimeoutMs), retryAfterSeconds);
    }

    @Bean
    public RestClient taxCheckRestClient(
            RestClient.Builder restClientBuilder,
            @Value("${pos.tax.base-url:http://pos-tax:8091}") String taxServiceBaseUrl,
            TaxCheckSettings settings) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(settings.connectTimeout());
        factory.setReadTimeout(settings.readTimeout());
        return restClientBuilder
                .clone()
                .requestFactory(factory)
                .baseUrl(taxServiceBaseUrl)
                .build();
    }
}
