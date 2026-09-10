package com.positivity.tenancy.autoconfigure;

import com.positivity.tenancy.TenancyProperties;
import com.positivity.tenancy.kafka.TenantRecordInterceptor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.listener.RecordInterceptor;

/**
 * Publishes {@link TenantRecordInterceptor} as the module's {@link RecordInterceptor} bean. Spring
 * Boot's auto-configured {@code ConcurrentKafkaListenerContainerFactory} injects that bean into
 * every container it builds (verified against {@code spring-boot-kafka} 4.1.1); a module that builds
 * its own factory must call {@code setRecordInterceptor} with it.
 */
@AutoConfiguration(after = TenancyAutoConfiguration.class)
@ConditionalOnClass(RecordInterceptor.class)
public class TenancyKafkaAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(RecordInterceptor.class)
    public TenantRecordInterceptor<Object, Object> tenantRecordInterceptor(TenancyProperties properties) {
        return new TenantRecordInterceptor<>(properties);
    }
}
