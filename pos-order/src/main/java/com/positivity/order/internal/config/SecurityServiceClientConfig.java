package com.positivity.order.internal.config;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * REST client for pos-security-service's internal step-up check (CAP:550 S16, #2512; AW31).
 * pos-security-service is an ADR-0044 utility module; the call resolves its Eureka service id through
 * the load-balanced builder and never leaves the mesh. A cashier waits on it at the register, so it
 * gets short connect and read timeouts of its own; the shared builder is cloned so they do not leak
 * into the module's other clients.
 */
@Configuration
public class SecurityServiceClientConfig {

    @Bean
    public RestClient securityServiceRestClient(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("${pos.security-service.service-id:security-service}") String serviceId,
            @Value("${pos.security-service.connect-timeout:PT2S}") Duration connectTimeout,
            @Value("${pos.security-service.read-timeout:PT5S}") Duration readTimeout) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(connectTimeout).build());
        requestFactory.setReadTimeout(readTimeout);
        return restClientBuilder
                .clone()
                .baseUrl("http://" + serviceId)
                .requestFactory(requestFactory)
                .build();
    }
}
