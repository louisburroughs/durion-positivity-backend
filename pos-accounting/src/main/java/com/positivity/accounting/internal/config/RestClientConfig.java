package com.positivity.accounting.internal.config;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Configuration for Spring RestClient.
 * Provides RestClient bean for cross-service HTTP calls.
 *
 * **Usage:**
 * - Injected into service classes for making REST calls
 * - Used by InvoiceServiceClient for Invoice service integration
 * - Other services can create their own RestClient beans using the same generic
 * timeout properties
 *
 * **Configuration:**
 * - Generic timeout properties apply to all RestClient beans in this module
 * - Connect timeout: prevents indefinite hangs during connection establishment
 * (typically shorter)
 * - Read timeout: prevents indefinite hangs waiting for response data
 * (typically longer for slow APIs)
 *
 * @see <a href=
 *      "https://github.com/louisburroughs/durion-positivity-backend/issues/114">Issue
 *      #114</a>
 */
@Configuration
public class RestClientConfig {

    @Bean
    @Primary
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }

    @Bean
    @LoadBalanced
    public RestClient.Builder loadBalancedRestClientBuilder() {
        return RestClient.builder();
    }

    /**
     * Create RestClient bean for Invoice service with configured timeouts.
     * Uses Spring Boot's auto-configured RestClient.Builder with
     * SimpleClientHttpRequestFactory
     * to enforce connect and read timeouts, preventing threads from hanging
     * indefinitely
     * on downstream service issues.
     *
     * Timeout properties are generic (pos.restclient.*) and can be reused by other
     * RestClient beans in this module.
     *
     * Connect timeout (default 3s) is shorter since connection establishment
     * is typically fast. Read timeout (default 5s) is longer to accommodate
     * slower API processing.
     *
     * @param builder          Spring Boot auto-configured RestClient.Builder with
     *                         shared customizations
     * @param connectTimeoutMs connect timeout in milliseconds (default 3000ms)
     * @param readTimeoutMs    read timeout in milliseconds (default 5000ms)
     * @return configured RestClient instance with timeout protection
     */
    @Bean
    public RestClient invoiceServiceRestClient(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder builder,
            @Value("${pos.restclient.connect.timeout:3000}") int connectTimeoutMs,
            @Value("${pos.restclient.read.timeout:5000}") int readTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));

        return builder.requestFactory(factory).build();
    }
}
