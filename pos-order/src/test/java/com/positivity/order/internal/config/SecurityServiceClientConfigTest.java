package com.positivity.order.internal.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.cloud.client.loadbalancer.LoadBalancerAutoConfiguration;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.cloud.client.loadbalancer.LoadBalancerRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * CAP:550 S16 (#2512, round 2 LOW-3): the step-up client clones the {@code @LoadBalanced} builder to set
 * its own timeouts. The clone must keep the load-balancer interceptor that Spring Cloud adds to the
 * builder bean, or {@code http://security-service} would be resolved by DNS instead of Eureka.
 */
@DisplayName("SecurityServiceClientConfig — the cloned builder still load-balances")
class SecurityServiceClientConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            // The application's conversions (the timeouts are ISO-8601 durations, as in application.yml).
            .withInitializer(context ->
                    context.getBeanFactory().setConversionService(ApplicationConversionService.getSharedInstance()))
            .withConfiguration(AutoConfigurations.of(LoadBalancerAutoConfiguration.class))
            .withUserConfiguration(LoadBalancedBuilder.class, SecurityServiceClientConfig.class);

    /** The same bean as {@code SecurityConfig#loadBalancedRestClientBuilder}, plus a stand-in balancer. */
    @Configuration(proxyBeanMethods = false)
    static class LoadBalancedBuilder {

        static final LoadBalancerClient BALANCER = mock(LoadBalancerClient.class);

        @Bean
        @LoadBalanced
        RestClient.Builder loadBalancedRestClientBuilder() {
            return RestClient.builder();
        }

        @Bean
        LoadBalancerClient loadBalancerClient() {
            return BALANCER;
        }
    }

    @Test
    @DisplayName("a call on the step-up client is resolved through the load balancer by service id")
    @SuppressWarnings("unchecked")
    void stepUpClientResolvesTheServiceIdThroughTheLoadBalancer() throws IOException {
        when(LoadBalancedBuilder.BALANCER.execute(eq("security-service"), any(LoadBalancerRequest.class)))
                .thenThrow(new IOException("resolved by the load balancer"));

        contextRunner.run(context -> {
            RestClient client = context.getBean("securityServiceRestClient", RestClient.class);

            assertThatThrownBy(() -> client.post()
                            .uri("/internal/v1/auth/step-up")
                            .retrieve()
                            .toBodilessEntity())
                    .isInstanceOf(ResourceAccessException.class)
                    .hasMessageContaining("resolved by the load balancer");
            verify(LoadBalancedBuilder.BALANCER).execute(eq("security-service"), any(LoadBalancerRequest.class));
        });
    }
}
