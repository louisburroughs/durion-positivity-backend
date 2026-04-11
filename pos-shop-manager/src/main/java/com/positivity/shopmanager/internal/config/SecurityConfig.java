package com.positivity.shopmanager.internal.config;

import com.positivity.security.common.GatewaySecurityConfig;
import java.time.Duration;
import java.time.Clock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Shop Manager Service Security Configuration.
 *
 * <p>
 * Imports {@link GatewaySecurityConfig} which provides:
 * </p>
 * <ul>
 * <li>Gateway-based authentication via X-Authorities and X-User headers</li>
 * <li>Stateless session management (JWT-based)</li>
 * <li>Method-level security (@PreAuthorize support)</li>
 * <li>Public access to actuator, swagger endpoints</li>
 * </ul>
 *
 * @see GatewaySecurityConfig
 */
@Slf4j
@Configuration
@Import(GatewaySecurityConfig.class)
public class SecurityConfig {

    @Bean
    public RestClient restClient() {
        return RestClient.create();
    }

    @Bean(name = "crmRestClient")
    public RestClient crmRestClient(
            RestClient.Builder builder,
            @Value("${pos.crm.connect-timeout-ms:200}") int connectTimeoutMs,
            @Value("${pos.crm.read-timeout-ms:2000}") int readTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        return builder.requestFactory(factory).build();
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
