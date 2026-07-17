package com.positivity.peoplecontact.internal.config;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    /**
     * Static service-to-service grant for every {@code SecurityServiceClient} operation
     * (#880). Endpoint → authority, as enforced by pos-security-service:
     *
     * <ul>
     * <li>{@code GET /v1/users} → {@code security:user:view}</li>
     * <li>{@code GET /v1/roles}, {@code GET /v1/roles/by-name/{name}},
     * {@code GET /v1/roles/assignments/user/{userId}} → {@code security:role:view}</li>
     * <li>{@code POST /v1/roles/assignments}, {@code DELETE /v1/roles/assignments/{assignmentId}}
     * (revoke) → {@code security:role:assign}</li>
     * </ul>
     *
     * <p>pos-security-service defines no separate revoke authority — assignment removal is
     * guarded by {@code security:role:assign}. {@code SecurityServiceAuthoritiesContractTest}
     * (here) and {@code PeopleContactClientAuthoritiesTest} (pos-security-service) pin both
     * sides of this contract.
     */
    public static final String SECURITY_SERVICE_AUTHORITIES =
            "security:user:view,security:role:view,security:role:assign";

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

    @Bean
    public RestClient securityServiceRestClient(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder builder,
            @Value("${pos.security-service.service-id:security-service}") String serviceId,
            @Value("${pos.restclient.connect.timeout:3000}") int connectTimeoutMs,
            @Value("${pos.restclient.read.timeout:5000}") int readTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));

        return builder.requestFactory(factory)
                .baseUrl("http://" + serviceId)
                .defaultHeader("X-User", "pos-people-contact")
                .defaultHeader("X-Authorities", SECURITY_SERVICE_AUTHORITIES)
                .build();
    }
}
