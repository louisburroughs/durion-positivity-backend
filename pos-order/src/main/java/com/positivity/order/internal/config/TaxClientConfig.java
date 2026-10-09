package com.positivity.order.internal.config;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * REST client for pos-tax (ADR-0044 utility module — synchronous REST permitted). Same default
 * base URL as pos-workorder's tax client (pos-tax is internal-only on 8091).
 *
 * <p>Direct-call exception to the load-balanced client rule (#641): pos-tax sets
 * {@code register-with-eureka: false}, so its address cannot be discovered and stays an explicit
 * base URL on the plain builder.
 *
 * <p>Every call has explicit connect and read timeouts ({@code pos.tax.connect-timeout},
 * {@code pos.tax.read-timeout}), so a slow pos-tax never holds an order request thread, and
 * forwards the inbound {@code X-Correlation-Id} so pos-tax logs under the same id (ADR-0017 §4;
 * CAP:550 S32d).
 */
@Configuration
public class TaxClientConfig {

    static final String CORRELATION_HEADER = "X-Correlation-Id";

    @Bean
    public RestClient taxServiceRestClient(
            RestClient.Builder restClientBuilder,
            @Value("${pos.tax.base-url:http://pos-tax:8091}") String taxServiceBaseUrl,
            @Value("${pos.tax.connect-timeout:2s}") Duration connectTimeout,
            @Value("${pos.tax.read-timeout:5s}") Duration readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        return restClientBuilder
                .clone()
                .baseUrl(taxServiceBaseUrl)
                .requestFactory(factory)
                .requestInterceptor(forwardCorrelationId())
                .build();
    }

    /** Copies the inbound request's {@code X-Correlation-Id}, when there is one, onto the call. */
    static ClientHttpRequestInterceptor forwardCorrelationId() {
        return (request, body, execution) -> {
            if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                    && request.getHeaders().getFirst(CORRELATION_HEADER) == null) {
                String correlationId = attributes.getRequest().getHeader(CORRELATION_HEADER);
                if (correlationId != null && !correlationId.isBlank()) {
                    request.getHeaders().set(CORRELATION_HEADER, correlationId.trim());
                }
            }
            return execution.execute(request, body);
        };
    }
}
