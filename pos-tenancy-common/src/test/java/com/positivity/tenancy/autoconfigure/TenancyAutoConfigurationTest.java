package com.positivity.tenancy.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.tenancy.RemoteTenantRegistry;
import com.positivity.tenancy.RemoteTenantRegistryMetrics;
import com.positivity.tenancy.StaticTenantRegistry;
import com.positivity.tenancy.TenantContextTaskDecorator;
import com.positivity.tenancy.TenantIterator;
import com.positivity.tenancy.TenantKeyGenerator;
import com.positivity.tenancy.TenantRegistry;
import com.positivity.tenancy.TenantResolver;
import com.positivity.tenancy.datasource.TenantAwareDataSource;
import com.positivity.tenancy.hibernate.TenantContextIdentifierResolver;
import com.positivity.tenancy.kafka.TenantRecordInterceptor;
import com.positivity.tenancy.web.TenantContextFilter;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.client.RestClient;

class TenancyAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    TenancyAutoConfiguration.class,
                    TenancyDataSourceAutoConfiguration.class,
                    TenancyHibernateAutoConfiguration.class,
                    TenancyKafkaAutoConfiguration.class));

    @Configuration(proxyBeanMethods = false)
    static class DataSources {
        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource("jdbc:h2:mem:tenancy;DB_CLOSE_DELAY=-1", "sa", "");
        }

        @Bean
        DataSource flywayDataSource() {
            return new DriverManagerDataSource("jdbc:h2:mem:tenancy;DB_CLOSE_DELAY=-1", "sa", "");
        }
    }

    @Test
    void registersTheRuntimeAndWrapsTheApplicationDataSource() {
        runner.withUserConfiguration(DataSources.class)
                .withPropertyValues("pos.tenancy.default-tenant-id=01900000-0000-7000-8000-000000000001")
                .run(context -> {
                    assertThat(context).hasSingleBean(TenantResolver.class);
                    assertThat(context).hasSingleBean(TenantRegistry.class);
                    assertThat(context).hasSingleBean(TenantIterator.class);
                    assertThat(context).hasSingleBean(TenantKeyGenerator.class);
                    assertThat(context.getBean(org.springframework.cache.annotation.CachingConfigurer.class)
                                    .keyGenerator())
                            .isSameAs(context.getBean(TenantKeyGenerator.class));
                    assertThat(context).hasSingleBean(TenantContextTaskDecorator.class);
                    assertThat(context).hasSingleBean(TenantContextIdentifierResolver.class);
                    assertThat(context).hasSingleBean(TenantRecordInterceptor.class);
                    assertThat(context.getBean("dataSource")).isInstanceOf(TenantAwareDataSource.class);
                    assertThat(context.getBean("flywayDataSource")).isNotInstanceOf(TenantAwareDataSource.class);
                    assertThat(context.getBean(TenantRegistry.class).activeTenantIds())
                            .containsExactly(UUID.fromString("01900000-0000-7000-8000-000000000001"));
                    assertThat(context.getBean(TenantResolver.class).hasDefault())
                            .isTrue();
                });
    }

    @Test
    void servletModulesGetTheRequestFilterAheadOfSecurity() {
        new WebApplicationContextRunner()
                .withConfiguration(
                        AutoConfigurations.of(TenancyAutoConfiguration.class, TenancyWebAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).hasSingleBean(TenantContextFilter.class);
                    assertThat(context.getBean("tenantContextFilterRegistration"))
                            .hasFieldOrPropertyWithValue("order", TenancyWebAutoConfiguration.FILTER_ORDER);
                });
        runner.run(context -> assertThat(context).doesNotHaveBean(TenantContextFilter.class));
    }

    @Test
    void datasourceWrappingCanBeDisabled() {
        runner.withUserConfiguration(DataSources.class)
                .withPropertyValues("pos.tenancy.datasource.enabled=false")
                .run(context -> {
                    assertThat(context.getBean("dataSource")).isNotInstanceOf(TenantAwareDataSource.class);
                    assertThat(context.getBean(TenantResolver.class).hasDefault())
                            .isFalse();
                });
    }

    @Test
    void registryIsStaticByDefault() {
        runner.withPropertyValues("pos.tenancy.tenants=01900000-0000-7000-8000-000000000001")
                .run(context -> {
                    assertThat(context).hasSingleBean(TenantRegistry.class);
                    assertThat(context.getBean(TenantRegistry.class)).isInstanceOf(StaticTenantRegistry.class);
                    assertThat(context).doesNotHaveBean(RemoteTenantRegistry.class);
                    assertThat(context).doesNotHaveBean(RemoteTenantRegistryMetrics.class);
                });
    }

    /**
     * A builder the test can watch, standing in for the module's RestClient.Builder bean: its
     * interceptor records every request the registry sends through it.
     */
    @Configuration(proxyBeanMethods = false)
    static class WatchedBuilder {
        static final List<String> SEEN_SECRETS = new CopyOnWriteArrayList<>();

        @Bean
        RestClient.Builder restClientBuilder() {
            return RestClient.builder().requestInterceptor((request, body, execution) -> {
                SEEN_SECRETS.add(request.getHeaders().getFirst(RemoteTenantRegistry.SECRET_HEADER));
                return execution.execute(request, body);
            });
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @Test
    void remoteModeReplacesTheStaticRegistryAndFetchesThroughTheModuleBuilder() throws IOException {
        UUID acme = UUID.fromString("01990000-0000-7000-8000-000000000a01");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/tenants", exchange -> {
            byte[] body = ("[{\"tenantId\":\"" + acme + "\",\"slug\":\"acme\",\"status\":\"ACTIVE\"}]")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        WatchedBuilder.SEEN_SECRETS.clear();
        try {
            runner.withUserConfiguration(WatchedBuilder.class)
                    .withPropertyValues(
                            "pos.tenancy.default-tenant-id=01900000-0000-7000-8000-000000000001",
                            "pos.tenancy.registry.mode=REMOTE",
                            "pos.tenancy.registry.secret=s3cret",
                            "pos.tenancy.registry.url=http://127.0.0.1:"
                                    + server.getAddress().getPort() + "/internal/v1/tenants")
                    .run(context -> {
                        assertThat(context).hasSingleBean(TenantRegistry.class);
                        TenantRegistry registry = context.getBean(TenantRegistry.class);
                        assertThat(registry).isInstanceOf(RemoteTenantRegistry.class);
                        assertThat(context.getBean(TenantIterator.class)).isNotNull();
                        assertThat(registry.activeTenantIds()).containsExactly(acme);
                        assertThat(WatchedBuilder.SEEN_SECRETS).containsExactly("s3cret");

                        assertThat(context).hasSingleBean(RemoteTenantRegistryMetrics.class);
                        MeterRegistry meters = context.getBean(MeterRegistry.class);
                        assertThat(meters.get("tenancy.registry.tenants")
                                        .gauge()
                                        .value())
                                .isEqualTo(1.0);
                        assertThat(meters.get("tenancy.registry.last_success_epoch_seconds")
                                        .gauge()
                                        .value())
                                .isPositive();
                    });
        } finally {
            server.stop(0);
        }
    }

    @Test
    void remoteModeWithoutABuilderBeanStillStartsOnTheStaticList() {
        runner.withPropertyValues(
                        "pos.tenancy.tenants=01900000-0000-7000-8000-000000000001",
                        "pos.tenancy.registry.mode=REMOTE",
                        "pos.tenancy.registry.url=http://127.0.0.1:1/internal/v1/tenants",
                        "pos.tenancy.registry.connect-timeout=PT0.2S")
                .run(context -> {
                    TenantRegistry registry = context.getBean(TenantRegistry.class);
                    assertThat(registry).isInstanceOf(RemoteTenantRegistry.class);
                    // Nothing listens on port 1: the fetch fails and the static snapshot stands.
                    assertThat(registry.activeTenantIds())
                            .containsExactly(UUID.fromString("01900000-0000-7000-8000-000000000001"));
                    assertThat(((RemoteTenantRegistry) registry).consecutiveFailures())
                            .isEqualTo(1);
                });
    }

    /**
     * The shape of a module with Spring Cloud on the classpath: a plain builder for direct calls,
     * marked {@code @Primary}, and the {@code @LoadBalanced} one that resolves Eureka service ids.
     */
    @Configuration(proxyBeanMethods = false)
    static class PlainAndLoadBalancedBuilders {
        static final RestClient.Builder PLAIN = RestClient.builder();
        static final RestClient.Builder LOAD_BALANCED = RestClient.builder();

        @Bean
        @Primary
        RestClient.Builder plainRestClientBuilder() {
            return PLAIN;
        }

        @Bean
        @LoadBalanced
        RestClient.Builder loadBalancedRestClientBuilder() {
            return LOAD_BALANCED;
        }
    }

    @Test
    void theLoadBalancedBuilderIsChosenOverThePrimaryPlainOne() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(PlainAndLoadBalancedBuilders.class);
            context.refresh();

            TenancyAutoConfiguration.RemoteRegistryConfiguration.ResolvedBuilder chosen =
                    TenancyAutoConfiguration.RemoteRegistryConfiguration.resolveBuilder(context.getBeanFactory());

            assertThat(chosen.builder())
                    .as("http://tenant/... must resolve through the load balancer, not DNS")
                    .isSameAs(PlainAndLoadBalancedBuilders.LOAD_BALANCED);
            assertThat(chosen.loadBalanced()).isTrue();
        }
    }

    @Test
    void aSinglePlainBuilderIsUsedWhenNoneIsLoadBalanced() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(WatchedBuilder.class);
            context.refresh();

            TenancyAutoConfiguration.RemoteRegistryConfiguration.ResolvedBuilder chosen =
                    TenancyAutoConfiguration.RemoteRegistryConfiguration.resolveBuilder(context.getBeanFactory());

            assertThat(chosen.builder()).isSameAs(context.getBean(RestClient.Builder.class));
            assertThat(chosen.loadBalanced()).isFalse();
        }
    }

    @Test
    @DisplayName("the service-id default URL is refused when no @LoadBalanced builder can resolve it")
    void theServiceIdDefaultUrlIsRefusedWithoutALoadBalancedBuilder() {
        runner.withUserConfiguration(WatchedBuilder.class)
                .withPropertyValues("pos.tenancy.registry.mode=REMOTE")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("pos.tenancy.registry.url=http://tenant/internal/v1/tenants")
                            .hasMessageContaining("@LoadBalanced");
                });
        runner.withPropertyValues("pos.tenancy.registry.mode=REMOTE")
                .run(context -> assertThat(context).hasFailed());
        runner.withUserConfiguration(PlainAndLoadBalancedBuilders.class)
                .withPropertyValues("pos.tenancy.registry.mode=REMOTE")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Configuration(proxyBeanMethods = false)
    static class OwnRegistry {
        static final UUID OWN = UUID.fromString("01990000-0000-7000-8000-00000000ff01");

        @Bean
        TenantRegistry extTenantRegistry() {
            return () -> List.of(OWN);
        }
    }

    @Test
    void aModuleOwnedRegistryWinsEvenInRemoteMode() {
        runner.withUserConfiguration(OwnRegistry.class)
                .withPropertyValues("pos.tenancy.registry.mode=REMOTE")
                .run(context -> {
                    assertThat(context).hasSingleBean(TenantRegistry.class);
                    assertThat(context).doesNotHaveBean(RemoteTenantRegistry.class);
                    assertThat(context.getBean(TenantRegistry.class).activeTenantIds())
                            .containsExactly(OwnRegistry.OWN);
                });
    }
}
