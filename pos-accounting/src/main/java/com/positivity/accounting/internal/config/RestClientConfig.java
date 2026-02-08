package com.positivity.accounting.internal.config;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Configuration for Spring RestClient.
 * Provides RestClient bean for cross-service HTTP calls.
 *
 * **Usage:**
 * - Injected into service classes for making REST calls
 * - Used by InvoiceServiceClient for Invoice service integration
 *
 * **Configuration:**
 * - Connect timeout: prevents indefinite hangs during connection establishment (typically shorter)
 * - Read timeout: prevents indefinite hangs waiting for response data (typically longer for slow APIs)
 *
 * @see <a href=
 *      "https://github.com/louisburroughs/durion-positivity-backend/issues/114">Issue
 *      #114</a>
 */
@Configuration
public class RestClientConfig {

    /**
     * Create RestClient bean with configured timeouts.
     * Uses SimpleClientHttpRequestFactory to enforce connect and read timeouts,
     * preventing threads from hanging indefinitely on downstream service issues.
     *
     * Connect timeout (default 3s) is shorter since connection establishment
     * is typically fast. Read timeout (default 5s) is longer to accommodate
     * slower API processing.
     *
     * @param connectTimeoutMs connect timeout in milliseconds (default 3000ms)
     * @param readTimeoutMs read timeout in milliseconds (default 5000ms)
     * @return configured RestClient instance with timeout protection
     */
    @Bean
    public RestClient restClient(
            @Value("${pos.invoice.service.connect.timeout:3000}") int connectTimeoutMs,
            @Value("${pos.invoice.service.read.timeout:5000}") int readTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));

        return RestClient.builder()
                .requestFactory(factory)
                .build();
    }
}
