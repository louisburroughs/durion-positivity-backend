package com.positivity.securityservice.internal.config;

import com.positivity.tenancy.TenantContextTaskDecorator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * The executor audit exports run on (#2408). Its tasks carry the submitting thread's tenant through
 * pos-tenancy-common's {@link TenantContextTaskDecorator}, so the worker reads and writes as the
 * tenant that requested the export and nothing else.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuditExportProperties.class)
public class AuditExportConfig {

    public static final String EXECUTOR_BEAN = "auditExportExecutor";

    @Bean(name = EXECUTOR_BEAN)
    public ThreadPoolTaskExecutor auditExportExecutor(TenantContextTaskDecorator tenantContextTaskDecorator) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("audit-export-");
        executor.setTaskDecorator(tenantContextTaskDecorator);
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }
}
