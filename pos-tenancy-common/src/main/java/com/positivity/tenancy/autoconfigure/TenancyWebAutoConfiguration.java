package com.positivity.tenancy.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.tenancy.TenancyProperties;
import com.positivity.tenancy.web.TenantContextFilter;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Registers {@link TenantContextFilter} ahead of the Spring Security filter chain (which Boot
 * registers at order {@code -100}) in every servlet module.
 */
@AutoConfiguration(after = TenancyAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass({OncePerRequestFilter.class, ObjectMapper.class})
public class TenancyWebAutoConfiguration {

    /** Before Spring Security's {@code -100}, after request-id/log filters that use lower numbers. */
    public static final int FILTER_ORDER = -110;

    @Bean
    @ConditionalOnMissingBean
    public TenantContextFilter tenantContextFilter(
            TenancyProperties properties, ObjectProvider<ObjectMapper> objectMapper, ObjectProvider<Clock> clock) {
        return new TenantContextFilter(
                properties, objectMapper.getIfAvailable(ObjectMapper::new), clock.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    public FilterRegistrationBean<TenantContextFilter> tenantContextFilterRegistration(TenantContextFilter filter) {
        FilterRegistrationBean<TenantContextFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(FILTER_ORDER);
        registration.setName("tenantContextFilter");
        return registration;
    }
}
