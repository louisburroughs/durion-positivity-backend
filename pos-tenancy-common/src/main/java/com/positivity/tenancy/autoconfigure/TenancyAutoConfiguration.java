package com.positivity.tenancy.autoconfigure;

import com.positivity.tenancy.RemoteTenantRegistry;
import com.positivity.tenancy.RemoteTenantRegistryMetrics;
import com.positivity.tenancy.StaticTenantRegistry;
import com.positivity.tenancy.TenancyProperties;
import com.positivity.tenancy.TenantContextTaskDecorator;
import com.positivity.tenancy.TenantIterator;
import com.positivity.tenancy.TenantKeyGenerator;
import com.positivity.tenancy.TenantRegistry;
import com.positivity.tenancy.TenantResolver;
import io.micrometer.core.instrument.MeterRegistry;
import java.lang.annotation.Annotation;
import java.time.Clock;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.ClassUtils;
import org.springframework.web.client.RestClient;

/** Core tenancy beans: properties, resolver, registry, iterator, cache keys, executor propagation. */
@AutoConfiguration
@EnableConfigurationProperties(TenancyProperties.class)
public class TenancyAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(TenancyAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public TenantResolver tenantResolver(TenancyProperties properties) {
        properties
                .getDefaultTenantId()
                .ifPresentOrElse(
                        id -> log.warn(
                                "pos.tenancy.default-tenant-id={} is set: unbound work runs as that tenant"
                                        + " (transitional, ADR-0062 section 9)",
                                id),
                        () -> log.info("Tenancy is strict: unbound work carries no tenant and RLS fails closed"));
        return new TenantResolver(properties);
    }

    /**
     * The static registry, unless the module declares its own (pos-security-service's {@code
     * ext_tenant}-backed one) or {@link RemoteRegistryConfiguration} registered the remote one
     * first (nested configurations are processed ahead of these bean methods).
     */
    @Bean
    @ConditionalOnMissingBean
    public TenantRegistry tenantRegistry(TenancyProperties properties) {
        return new StaticTenantRegistry(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public TenantIterator tenantIterator(TenantRegistry registry) {
        return new TenantIterator(registry);
    }

    @Bean(TenantKeyGenerator.BEAN_NAME)
    @ConditionalOnMissingBean(name = TenantKeyGenerator.BEAN_NAME)
    public TenantKeyGenerator tenantKeyGenerator(TenantResolver tenantResolver) {
        return new TenantKeyGenerator(tenantResolver);
    }

    /** Makes {@link TenantKeyGenerator} the default for every {@code @Cacheable} that names no generator. */
    @Bean
    @ConditionalOnMissingBean(CachingConfigurer.class)
    public CachingConfigurer tenantCachingConfigurer(TenantKeyGenerator tenantKeyGenerator) {
        return new CachingConfigurer() {
            @Override
            public KeyGenerator keyGenerator() {
                return tenantKeyGenerator;
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean
    public TenantContextTaskDecorator tenantContextTaskDecorator() {
        return new TenantContextTaskDecorator();
    }

    /**
     * {@code pos.tenancy.registry.mode=REMOTE}: {@link RemoteTenantRegistry} replaces the static
     * registry (plan WS4-2). Still {@code @ConditionalOnMissingBean}, so a module with its own
     * {@link TenantRegistry} bean keeps it.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(RestClient.class)
    @ConditionalOnProperty(prefix = "pos.tenancy.registry", name = "mode", havingValue = "REMOTE")
    public static class RemoteRegistryConfiguration {

        private static final String LOAD_BALANCED = "org.springframework.cloud.client.loadbalancer.LoadBalanced";

        @Bean
        @ConditionalOnMissingBean(TenantRegistry.class)
        public RemoteTenantRegistry remoteTenantRegistry(
                TenancyProperties properties,
                ConfigurableListableBeanFactory beanFactory,
                ObjectProvider<Clock> clock) {
            TenancyProperties.Registry config = properties.getRegistry();
            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
            requestFactory.setConnectTimeout(config.getConnectTimeout());
            requestFactory.setReadTimeout(config.getReadTimeout());
            RestClient restClient = resolveBuilder(beanFactory)
                    .clone()
                    .requestFactory(requestFactory)
                    .build();
            log.info(
                    "TenantRegistry is remote: {} refreshed at most every {} (plan WS4-2)",
                    config.getUrl(),
                    config.getRefresh());
            return new RemoteTenantRegistry(properties, restClient, clock.getIfAvailable(Clock::systemUTC));
        }

        /**
         * The module's {@code @LoadBalanced RestClient.Builder} when it declares one (a {@code
         * http://tenant/...} URL then resolves through Eureka), else its single or primary builder,
         * else a plain one.
         */
        static RestClient.Builder resolveBuilder(ConfigurableListableBeanFactory beanFactory) {
            String[] names = beanFactory.getBeanNamesForType(RestClient.Builder.class);
            Class<? extends Annotation> loadBalanced = loadBalancedAnnotation(beanFactory.getBeanClassLoader());
            if (loadBalanced != null) {
                for (String name : names) {
                    if (beanFactory.findAnnotationOnBean(name, loadBalanced) != null) {
                        return beanFactory.getBean(name, RestClient.Builder.class);
                    }
                }
            }
            if (names.length == 0) {
                return RestClient.builder();
            }
            try {
                return beanFactory.getBean(RestClient.Builder.class);
            } catch (NoUniqueBeanDefinitionException e) {
                log.warn("Several RestClient.Builder beans and none is @LoadBalanced or @Primary; the tenant"
                        + " registry uses a plain builder, so pos.tenancy.registry.url must be a"
                        + " resolvable host");
                return RestClient.builder();
            }
        }

        @SuppressWarnings("unchecked")
        private static @Nullable Class<? extends Annotation> loadBalancedAnnotation(@Nullable ClassLoader classLoader) {
            if (!ClassUtils.isPresent(LOAD_BALANCED, classLoader)) {
                return null;
            }
            try {
                return (Class<? extends Annotation>) ClassUtils.forName(LOAD_BALANCED, classLoader);
            } catch (ClassNotFoundException | LinkageError e) {
                return null;
            }
        }

        /** Gauges over the remote registry when Micrometer is present. */
        @Configuration(proxyBeanMethods = false)
        @ConditionalOnClass(MeterRegistry.class)
        public static class MetricsConfiguration {

            @Bean
            @ConditionalOnMissingBean
            public RemoteTenantRegistryMetrics remoteTenantRegistryMetrics(
                    ObjectProvider<RemoteTenantRegistry> registry, ObjectProvider<MeterRegistry> meterRegistry) {
                return new RemoteTenantRegistryMetrics(registry, meterRegistry);
            }
        }
    }
}
