package com.positivity.supplier.internal.config;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * RestClient builder for pos-supplier <strong>platform</strong> registrations only: event types with
 * pos-event-receiver, permissions with pos-security-service, against fixed in-cluster base URLs. Mirrors
 * pos-warranty {@code RestClientConfig}.
 *
 * <p>Bounded connect/read timeouts prevent a hung platform service from blocking a worker thread
 * indefinitely. The defaults (2s / 5s) are sized for a service on the same network.
 *
 * <h2>Nothing vendor-facing may use this builder</h2>
 *
 * Vendor transport lives in {@code SupplierHttpClients}: per-profile timeouts, a request factory that
 * distinguishes a connect timeout from a read timeout, and redirects explicitly disabled.
 *
 * <p>This javadoc previously said vendor transport "is a later CAP-317 concern and does not run through this
 * builder", and by the time the OAuth2 strategy shipped that was false — the token leg took this builder,
 * which quietly applied 2s/5s platform budgets to a third-party token endpoint and put the one leg that
 * fetches a credential on a request factory that cannot tell a safe-to-retry connect timeout from an
 * unsafe-to-retry read timeout. The sentence read as a guarantee and was doing no work.
 *
 * <p>So, concretely: if a new outbound call is to a vendor, it belongs in {@code SupplierHttpClients}. If it
 * is to a platform service at a fixed in-cluster URL, it belongs here.
 */
@Configuration
public class RestClientConfig {

    @Value("${pos.restclient.connect.timeout:2000}")
    private int connectTimeoutMs;

    @Value("${pos.restclient.read.timeout:5000}")
    private int readTimeoutMs;

    @Bean
    @Primary
    public RestClient.Builder restClientBuilder() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        return RestClient.builder().requestFactory(factory);
    }

    /**
     * Eureka-resolved builder for calls to sibling domain services — today the pos-catalog
     * product-code lookup PRICAT matching depends on (ADR-0053 §5).
     *
     * <p>Injected by qualifier, never by type: {@link #restClientBuilder()} stays {@code @Primary}
     * so the platform registrations keep the builder they were written against, and a
     * service-to-service caller has to say which one it means.
     *
     * <p>The same timeouts apply. A sibling service is on the same network as the platform
     * services, and a slow catalog must not hold a PRICAT import thread indefinitely — an
     * unreachable catalog quarantines the line as re-appliable, which is a better outcome than a
     * hung import.
     */
    @Bean
    @LoadBalanced
    public RestClient.Builder loadBalancedRestClientBuilder() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        return RestClient.builder().requestFactory(factory);
    }
}
